package rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp; // 引入 Timestamp 类
import java.sql.Statement; // 引入 Statement 类
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors; // 引入 Collectors

public class ChatHandler extends HttpServlet {

    // 数据库连接常量，供所有内部工具类和本 Servlet 使用
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8";
    private static final String DB_USER = "root"; // 您的数据库用户名
    private static final String DB_PASSWORD = "root"; // 您的数据库密码
    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver";

    private VectorStore vectorStore; // 用于RAG的知识库
    private LLMAgentClient llmAgentClient; // 与大模型交互的客户端
    private Map<String, Tool> tools; // Agent可用的工具集合
    private ObjectMapper objectMapper; // JSON处理工具

    @Override
    public void init() {
        this.objectMapper = new ObjectMapper();
        this.llmAgentClient = new LLMAgentClient(); // 初始化LLM客户端

        try {
            // 确保JDBC驱动已加载，以便所有工具中的数据库连接正常工作
            Class.forName(JDBC_DRIVER);

            // 1. 初始化 VectorStore 并加载所有知识
            this.vectorStore = new VectorStore();
            vectorStore.loadDoctors();            // 加载医生信息
            vectorStore.loadDrugs();              // 加载药品信息
            vectorStore.loadHospitalDepartments();// 加载科室信息
            vectorStore.loadDoctorSchedules();    // 加载医生排班信息
            vectorStore.loadRagKnowledge();       // 加载通用医学知识、健康科普等非结构化内容

            System.out.println("✅ VectorStore 初始化完成，所有知识条目加载成功。总条目数: " + vectorStore.entries.size());

            // 2. 初始化 Agent 可用的工具
            this.tools = new HashMap<>();
            // RAG 搜索现在作为 Agent 的一个工具
            this.tools.put("rag_search", new RagSearchTool(vectorStore));
            this.tools.put("get_doctor_info", new DoctorInfoTool());
            this.tools.put("get_drug_info", new DrugInfoTool());
            this.tools.put("get_department_info", new HospitalDepartmentInfoTool());
            this.tools.put("get_doctor_schedule", new DoctorScheduleTool());

        } catch (Exception e) {
            System.err.println("❌ Agent/RAG 系统初始化失败");
            e.printStackTrace();
            this.vectorStore = null;
            this.tools = null;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        if (vectorStore == null || tools == null) {
            resp.getWriter().write("出错：系统未正确初始化");
            return;
        }

        String requestBody = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
        JsonNode jsonRequest;
        try {
            jsonRequest = objectMapper.readTree(requestBody);
        } catch (Exception e) {
            resp.getWriter().write("出错：无法解析请求JSON: " + e.getMessage());
            return;
        }

        String question = jsonRequest.has("question") ? jsonRequest.get("question").asText() : null;
        String sessionId = jsonRequest.has("session_id") ? jsonRequest.get("session_id").asText() : null; // 获取 session_id

        if (question == null || question.isEmpty()) {
            resp.getWriter().write("出错：未提供问题字段");
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            resp.getWriter().write("出错：未提供会话ID (session_id)");
            return;
        }

        PrintWriter writer = resp.getWriter();

        try {
            // 1. 保存用户消息到数据库 (调用本类内部的 saveMessage)
            saveMessage(sessionId, "user", question);

            // 2. 调用 Agent 运行的核心逻辑，并传入 session_id
            runAgent(question, sessionId, writer);

        } catch (Exception e) {
            e.printStackTrace();
            // 如果发生异常，向客户端写入错误信息
            writer.write("出错：" + e.getMessage());
        } finally {
            // 确保写入器被关闭，以刷新所有内容
            writer.close();
        }
    }

    // --- Agent 运行的核心方法 ---
    // 现在接收 session_id 并用于获取历史上下文
    private void runAgent(String userQuestion, String sessionId, PrintWriter finalOutputWriter) throws IOException {
        // 构建 Agent 的系统提示，告诉LLM它是什么，有什么工具
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("你是一个专业的医疗AI助手。你的任务是根据用户的提问，通过思考、工具调用和最终回答来提供帮助。\n");
        systemPromptBuilder.append("请严格遵循以下输出格式和流程：\n\n");

        systemPromptBuilder.append("--- 每次响应的固定格式 ---\n");
        systemPromptBuilder.append("1. **思考 (Thought):** 你的每次响应都必须以“Thought:”开头，随后是你的推理过程。请详细说明：\n");
        systemPromptBuilder.append("   - 你对用户意图的理解。\n");
        systemPromptBuilder.append("   - 为了回答问题，你可能需要哪些信息。\n");
        systemPromptBuilder.append("   - 如果需要工具，解释为什么选择这个工具，以及如何构建参数。\n");
        systemPromptBuilder.append("   - 如果一个问题需要多个步骤或多个工具来回答，请规划你的步骤，并先调用第一个必要的工具。\n\n");

        systemPromptBuilder.append("2. **工具调用 (Tool Call) 或 最终回答 (Final Answer):** 在“Thought:”之后，根据你的思考结果，选择以下两种格式之一输出：\n");
        systemPromptBuilder.append("   a) **如果需要调用工具：** 在思考结束后，紧接着输出一个JSON格式的工具调用。JSON前后可以有空行，但JSON本身必须完整且符合Schema：\n");
        systemPromptBuilder.append("      ```json\n");
        systemPromptBuilder.append("      {\n");
        systemPromptBuilder.append("        \"tool_name\": \"<工具名称>\",\n");
        systemPromptBuilder.append("        \"parameters\": {\n");
        systemPromptBuilder.append("          \"param1\": \"value1\",\n");
        systemPromptBuilder.append("          \"param2\": \"value2\"\n");
        systemPromptBuilder.append("        }\n");
        systemPromptBuilder.append("      }\n");
        systemPromptBuilder.append("      ```\n");
        systemPromptBuilder.append("      我（系统）将执行此工具，并将结果作为“Observation:”提供给你。\n\n");

        systemPromptBuilder.append("   b) **如果可以直接给出最终回答：** 在思考结束后，紧接着输出“Final Answer:”，随后是你的最终答案。最终答案是直接的、人类可读的回复，不包含任何JSON或技术细节：\n");
        systemPromptBuilder.append("      Final Answer: [你的最终答案内容]\n\n");

        systemPromptBuilder.append("--- 每次迭代的上下文 ---\n");
        systemPromptBuilder.append("- 每次迭代，你都将收到新的Prompt，其中可能包含你之前的思考、工具调用以及我（系统）提供的“Observation:”。\n");
        systemPromptBuilder.append("- 请基于所有历史信息，继续你的思考、工具调用或给出最终答案。\n\n");

        systemPromptBuilder.append("你可以使用的工具及其描述如下：\n");
        for (Tool tool : tools.values()) {
            systemPromptBuilder.append("  - 工具名称: `").append(tool.getName()).append("`\n");
            systemPromptBuilder.append("    描述: ").append(tool.getDescription()).append("\n");
            systemPromptBuilder.append("    参数Schema: ").append(tool.getParametersSchema()).append("\n"); // 包含参数Schema
        }
        systemPromptBuilder.append("\n");

        // --- 获取并格式化历史对话 ---
        List<Message> historyMessages = getMessagesBySessionId(sessionId); // <-- 调用本类内部的 getMessagesBySessionId
        StringBuilder conversationHistoryBuilder = new StringBuilder();
        if (historyMessages != null && !historyMessages.isEmpty()) {
            conversationHistoryBuilder.append("--- 历史对话 ---\n");
            // 限制历史消息数量，防止超出Token限制，这里取最近的10条（5轮对话）
            int historyLimit = 10;
            for (int i = Math.max(0, historyMessages.size() - historyLimit); i < historyMessages.size(); i++) {
                Message msg = historyMessages.get(i);
                // 仅包含用户和助手的最终回答作为历史上下文，不包含中间的工具执行日志
                conversationHistoryBuilder.append(msg.role.equals("user") ? "用户: " : "助手: ");
                conversationHistoryBuilder.append(msg.content).append("\n");
            }
            conversationHistoryBuilder.append("----------------\n\n");
        }


        // 初始Prompt，包含系统指令、历史对话和当前用户问题
        String currentPrompt = systemPromptBuilder.toString() +
                conversationHistoryBuilder.toString() + // <-- 重新加入历史对话到 Prompt
                "用户问题: " + userQuestion + "\n";


        int maxIterations = 5; // 防止Agent陷入无限循环，限制最大交互次数
        for (int i = 0; i < maxIterations; i++) {
            System.out.println("\n--- Agent 迭代 " + (i + 1) + " ---");
            System.out.println("发送给 LLM 的 Prompt:\n" + currentPrompt);
            // finalOutputWriter.write("\n"); // 客户端不显示迭代信息
            // finalOutputWriter.flush();


            // Collect LLM response internally for parsing tool calls.
            // AND stream ALL raw LLM output to System.out for debugging.
            StringBuilder llmResponseBuilder = new StringBuilder();
            try {
                llmAgentClient.getAgentResponse(currentPrompt, delta -> {
                    llmResponseBuilder.append(delta); // Collect for internal parsing
                    System.out.print(delta);          // Stream ALL raw LLM output to console for debugging
                    System.out.flush();               // Flush console output
                    finalOutputWriter.write(delta);   // <-- 关键：恢复流式传输到客户端！
                    finalOutputWriter.flush();        // 刷新客户端输出
                });
            } catch (Exception e) {
                System.err.println("Error during LLM streaming for Agent: " + e.getMessage());
                e.printStackTrace();
                // If an error occurs during LLM call, write to final output and exit
                finalOutputWriter.write("抱歉，AI服务在处理过程中出现错误：" + e.getMessage() + "\n");
                finalOutputWriter.flush();
                return;
            }
            String llmResponse = llmResponseBuilder.toString();
            System.out.println("\nLLM 原始响应 (Collected for parsing):\n" + llmResponse); // This log is for debugging collected full response

            // Attempt to parse tool call or final answer from LLM response
            try {
                // Check if the LLM's response contains "Final Answer:"
                if (llmResponse.contains("Final Answer:")) {
                    String finalAnswerContent = llmResponse.substring(llmResponse.indexOf("Final Answer:") + "Final Answer:".length()).trim();

                    // 保存 Agent 的最终回复到数据库 (调用本类内部的 saveMessage)
                    saveMessage(sessionId, "assistant", finalAnswerContent);

                    // finalOutputWriter.write("\n\n=== 最终回答 ===\n"); // 这个标记已经在LLM的原始输出中，无需重复
                    // finalOutputWriter.write(finalAnswerContent); // 最终回答内容也已在流中，无需重复
                    // finalOutputWriter.write("\n================\n"); // 这个标记也已在LLM的原始输出中，无需重复
                    // finalOutputWriter.flush(); // 已经在回调中刷新

                    return; // 最终回答已发送，退出 runAgent
                }

                // 如果不是最终回答，尝试解析工具调用
                int jsonStartIndex = llmResponse.indexOf("{");
                int jsonEndIndex = llmResponse.lastIndexOf("}");
                String potentialToolCallJson = null;

                if (jsonStartIndex != -1 && jsonEndIndex != -1 && jsonEndIndex > jsonStartIndex) {
                    potentialToolCallJson = llmResponse.substring(jsonStartIndex, jsonEndIndex + 1);
                }

                if (potentialToolCallJson != null && potentialToolCallJson.contains("\"tool_name\":")) { // 简单检查是否是工具JSON
                    JsonNode toolCallNode = objectMapper.readTree(potentialToolCallJson);
                    String toolName = toolCallNode.has("tool_name") ? toolCallNode.get("tool_name").asText() : null;
                    JsonNode parametersNode = toolCallNode.has("parameters") ? toolCallNode.get("parameters") : null;

                    if (toolName != null && tools.containsKey(toolName) && parametersNode != null) {
                        // 这是一个工具调用
                        Tool toolToExecute = tools.get(toolName);
                        String parametersJson = parametersNode.toString();
                        System.out.println("Agent 决定调用工具: " + toolName + "，参数: " + parametersJson);

                        // 向客户端流式传输格式化的工具执行信息
                        finalOutputWriter.write("\n\n--- Agent 正在执行工具 ---\n");
                        finalOutputWriter.write("工具名称: `" + toolName + "`\n");
                        finalOutputWriter.write("参数: " + parametersJson + "\n");
                        finalOutputWriter.write("-----------------------\n");
                        finalOutputWriter.flush();

                        String toolOutput = toolToExecute.execute(parametersJson); // 执行工具
                        System.out.println("工具 `" + toolName + "` 返回结果:\n" + toolOutput);

                        // 向客户端流式传输格式化的工具返回结果
                        finalOutputWriter.write("\n--- 工具返回结果 ---\n");
                        finalOutputWriter.write(toolOutput + "\n");
                        finalOutputWriter.write("--------------------\n");
                        finalOutputWriter.flush();

                        // 使用工具输出作为新的上下文，重新构建 Prompt，发送给 LLM 生成最终回答
                        // 确保包含原始问题和所有历史信息。添加“Observation”标签。
                        currentPrompt = systemPromptBuilder.toString() +
                                conversationHistoryBuilder.toString() + // <-- 重新加入历史对话到 Prompt
                                "用户问题: " + userQuestion + "\n\n" + // 保留原始用户问题作为上下文
                                "你之前的思考和工具调用:\n" + llmResponse + "\n" + // 包含 LLM 之前的输出 (思考 + 工具调用)
                                "观察 (Observation):\n" + toolOutput + "\n\n" + // 添加观察结果
                                "请根据以上信息，给出最终回答或决定下一步操作。\n";
                        // 继续下一轮迭代，期望 LLM 提供最终回答
                        continue; // 跳过当前循环的其余部分，进入下一次迭代
                    }
                }
                // 如果既不是最终回答也不是有效的工具调用，则发生了一些意外情况。
                // 备用方案：向客户端流式传输一个通用错误消息。
                finalOutputWriter.write("抱歉，AI未能理解您的请求或生成有效工具调用。");
                finalOutputWriter.flush();
                return; // 退出 runAgent

            } catch (Exception e) {
                // 解析 LLM 响应或执行工具失败，向客户端流式传输错误消息
                System.err.println("解析 LLM 响应或执行工具出错: " + e.getMessage());
                finalOutputWriter.write("抱歉，处理AI响应时发生错误：" + e.getMessage() + "\n");
                finalOutputWriter.flush();
                return; // 退出 runAgent
            }
        }
        // 达到最大迭代次数，仍然无法提供答案
        finalOutputWriter.write("抱歉，我未能找到合适的答案或完成操作。请尝试尝试换个问法。");
        finalOutputWriter.flush();
    }

    // --- 以下是直接集成到 Servlet 内部的数据库访问逻辑 (原 ChatSessionServlet 和 MessageService 中的方法) ---

    // 获取数据库连接
    private Connection getConnection() throws SQLException {
        try {
            Class.forName(JDBC_DRIVER); // 加载MySQL驱动
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new SQLException("MySQL JDBC Driver not found. Please add mysql-connector-java to your classpath.");
        }
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // --- 数据模型类 (POJO)，严格对应数据库字段 ---
    // 这些类将用于在Java代码中表示数据库行
    public static class Message {
        public int id;
        public String sessionId;
        public int messageIndex;
        public String role; // 'user' 或 'assistant'
        public String content;
        public Timestamp timestamp;

        public Message(int id, String sessionId, int messageIndex, String role, String content, Timestamp timestamp) {
            this.id = id;
            this.sessionId = sessionId;
            this.messageIndex = messageIndex;
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    // --- 消息相关操作 ---

    /**
     * 获取某个会话的所有消息。
     * @param sessionId 会话ID
     * @return 消息列表，按 message_index 升序排列
     */
    private List<Message> getMessagesBySessionId(String sessionId) {
        List<Message> messages = new ArrayList<>();
        // 只获取用户和助手的最终回复作为历史，不包含中间的思考和工具调用过程
        String sql = "SELECT id, session_id, message_index, role, content, timestamp FROM dialog_messages WHERE session_id = ? ORDER BY message_index ASC";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new Message(
                            rs.getInt("id"),
                            rs.getString("session_id"),
                            rs.getInt("message_index"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getTimestamp("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting messages for session " + sessionId + ": " + e.getMessage());
            e.printStackTrace();
        }
        return messages;
    }

    /**
     * 保存一条消息到数据库，并更新对应会话的 updated_at 时间。
     * 这是一个事务性操作，确保消息和会话更新的原子性。
     * @param sessionId 消息所属的会话ID
     * @param role 消息角色 ('user' 或 'assistant')
     * @param content 消息内容
     * @return 新插入消息的ID，如果失败返回-1
     */
    private int saveMessage(String sessionId, String role, String content) {
        int messageId = -1;
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false); // 开启事务，确保操作原子性

            // 1. 获取当前 session 的最大 message_index，用于新消息的排序
            String getMaxIndexSql = "SELECT COALESCE(MAX(message_index), 0) FROM dialog_messages WHERE session_id = ?";
            int nextMessageIndex = 0;
            try (PreparedStatement pstmt = conn.prepareStatement(getMaxIndexSql)) {
                pstmt.setString(1, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        nextMessageIndex = rs.getInt(1) + 1; // 新消息的 index
                    }
                }
            }

            // 2. 插入新消息到 dialog_messages 表
            String insertMessageSql = "INSERT INTO dialog_messages (session_id, message_index, role, content) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertMessageSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, sessionId);
                pstmt.setInt(2, nextMessageIndex);
                pstmt.setString(3, role);
                pstmt.setString(4, content);
                pstmt.executeUpdate();

                // 获取新插入消息的自增ID
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        messageId = generatedKeys.getInt(1);
                    }
                }
            }

            // 3. 更新对应会话的 updated_at 时间，使其在会话列表中排到前面
            String updateSessionSql = "UPDATE dialog_sessions SET updated_at = CURRENT_TIMESTAMP WHERE session_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSessionSql)) {
                pstmt.setString(1, sessionId);
                pstmt.executeUpdate();
            }

            conn.commit(); // 提交事务
        } catch (SQLException e) {
            System.err.println("Error saving message and updating session: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback(); // 发生异常时回滚事务
                } catch (SQLException ex) {
                    System.err.println("Error rolling back transaction: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // 恢复自动提交模式
                    conn.close(); // 关闭连接
                } catch (SQLException e) {
                    System.err.println("Error closing connection: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        return messageId;
    }


    // --- 定义 Agent 可以使用的工具接口 (保留，因为工具是 Agent 的一部分) ---
    public interface Tool {
        String getName(); // Tool name for LLM recognition and invocation
        String getDescription(); // Tool description, explaining its function and use cases for LLM understanding
        String getParametersSchema(); // JSON Schema description of tool parameters, helping LLM build parameters correctly
        String execute(String parametersJson); // Execute the tool, pass parameters in JSON format, return execution result (string)
    }

    // --- RAG Search Tool (作为 ChatHandler 的静态嵌套类) ---
    public static class RagSearchTool implements Tool {
        private VectorStore vectorStore;
        private ObjectMapper objectMapper = new ObjectMapper();

        public RagSearchTool(VectorStore store) {
            this.vectorStore = store;
        }

        @Override
        public String getName() { return "rag_search"; }

        @Override
        public String getDescription() {
            return "Used to retrieve relevant text information from general knowledge bases such as medical popular science, medication instructions, common cold information, and medical guidelines. Use when the user asks about disease symptoms, health common sense, general drug uses, and other non-specific structured information.";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\": \"object\", \"properties\": {\"question\": {\"type\": \"string\", \"description\": \"The complete question asked by the user\"}}, \"required\": [\"question\"]}";
        }

        @Override
        public String execute(String parametersJson) {
            try {
                JsonNode node = objectMapper.readTree(parametersJson);
                String question = node.has("question") ? node.get("question").asText() : "";
                if (question.isEmpty()) {
                    return "Error: 'rag_search' tool requires 'question' parameter.";
                }
                return vectorStore.retrieveRelevant(question);
            } catch (Exception e) {
                System.err.println("Failed to execute 'rag_search' tool: " + e.getMessage());
                e.printStackTrace();
                return "Failed to execute 'rag_search' tool, please try again later.";
            }
        }
    }

    // --- Doctor Information Query Tool (作为 ChatHandler 的静态嵌套类) ---
    public static class DoctorInfoTool implements Tool {
        private ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String getName() { return "get_doctor_info"; }

        @Override
        public String getDescription() {
            return "Used to query detailed information about doctors, including name, department, title, specialty, gender, and bio. Use when the user asks about a specific doctor or needs to find a doctor that meets certain criteria. Input parameters can include 'name' (doctor's name), 'department' (doctor's department name), 'specialty' (doctor's specialty). Can be used in combination.";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\", \"description\": \"Doctor's name\"}, \"department\": {\"type\": \"string\", \"description\": \"Doctor's department\"}, \"specialty\": {\"type\": \"string\", \"description\": \"Doctor's specialty\"}}}";
        }

        @Override
        public String execute(String parametersJson) {
            try {
                JsonNode params = objectMapper.readTree(parametersJson);
                String name = params.has("name") ? params.get("name").asText() : null;
                String department = params.has("department") ? params.get("department").asText() : null;
                String specialty = params.has("specialty") ? params.get("specialty").asText() : null;

                StringBuilder sqlBuilder = new StringBuilder("SELECT name, department, title, specialty, gender, bio FROM doctors WHERE 1=1");
                if (name != null && !name.isEmpty()) sqlBuilder.append(" AND name LIKE ?");
                if (department != null && !department.isEmpty()) sqlBuilder.append(" AND department LIKE ?");
                if (specialty != null && !specialty.isEmpty()) sqlBuilder.append(" AND specialty LIKE ?");

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); // Use ChatHandler's constant
                     PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {

                    int paramIndex = 1;
                    if (name != null && !name.isEmpty()) pstmt.setString(paramIndex++, "%" + name + "%");
                    if (department != null && !department.isEmpty()) pstmt.setString(paramIndex++, "%" + department + "%");
                    if (specialty != null && !specialty.isEmpty()) pstmt.setString(paramIndex++, "%" + specialty + "%");

                    ResultSet rs = pstmt.executeQuery();
                    StringBuilder result = new StringBuilder("Query results:\n");
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        result.append("Doctor Name: ").append(rs.getString("name"))
                                .append(", Department: ").append(rs.getString("department"))
                                .append(", Title: ").append(rs.getString("title"))
                                .append(", Specialty: ").append(rs.getString("specialty"))
                                .append(", Gender: ").append(rs.getString("gender"))
                                .append(", Bio: ").append(rs.getString("bio")).append("\n");
                    }
                    return found ? result.toString() : "No matching doctor information found.";
                }
            } catch (Exception e) {
                System.err.println("Failed to execute 'get_doctor_info' tool: " + e.getMessage());
                e.printStackTrace();
                return "Failed to execute 'get_doctor_info' tool, please try again later.";
            }
        }
    }

    // --- Drug Information Query Tool (作为 ChatHandler 的静态嵌套类) ---
    public static class DrugInfoTool implements Tool {
        private ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String getName() { return "get_drug_info"; }

        @Override
        public String getDescription() {
            return "Used to query detailed information about drugs, including name, drug code, specification, manufacturer, indications, usage and dosage, and drug type. Use when the user asks about specific drug information. Input parameters can include 'name' (drug name), 'indications' (indications) or 'drug_code' (drug code).";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\", \"description\": \"Drug name\"}, \"indications\": {\"type\": \"string\", \"description\": \"Drug indications\"}, \"drug_code\": {\"type\": \"string\", \"description\": \"Drug code\"}}}";
        }

        @Override
        public String execute(String parametersJson) {
            try {
                JsonNode params = objectMapper.readTree(parametersJson);
                String name = params.has("name") ? params.get("name").asText() : null;
                String indications = params.has("indications") ? params.get("indications").asText() : null;
                String drugCode = params.has("drug_code") ? params.get("drug_code").asText() : null;

                StringBuilder sqlBuilder = new StringBuilder("SELECT name, drug_code, specification, manufacturer, indications, usage_and_dosage, drug_type FROM drugs WHERE 1=1");
                if (name != null && !name.isEmpty()) sqlBuilder.append(" AND name LIKE ?");
                if (indications != null && !indications.isEmpty()) sqlBuilder.append(" AND indications LIKE ?");
                if (drugCode != null && !drugCode.isEmpty()) sqlBuilder.append(" AND drug_code = ?");

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); // Use ChatHandler's constant
                     PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {

                    int paramIndex = 1;
                    if (name != null && !name.isEmpty()) pstmt.setString(paramIndex++, "%" + name + "%");
                    if (indications != null && !indications.isEmpty()) pstmt.setString(paramIndex++, "%" + indications + "%");
                    if (drugCode != null && !drugCode.isEmpty()) pstmt.setString(paramIndex++, drugCode);

                    ResultSet rs = pstmt.executeQuery();
                    StringBuilder result = new StringBuilder("Query results:\n");
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        result.append("Drug Name: ").append(rs.getString("name"))
                                .append(", Code: ").append(rs.getString("drug_code"))
                                .append(", Specification: ").append(rs.getString("specification"))
                                .append(", Manufacturer: ").append(rs.getString("manufacturer"))
                                .append(", Indications: ").append(rs.getString("indications"))
                                .append(", Usage and Dosage: ").append(rs.getString("usage_and_dosage"))
                                .append(", Drug Type: ").append(rs.getString("drug_type")).append("\n");
                    }
                    return found ? result.toString() : "No matching drug information found.";
                }
            } catch (Exception e) {
                System.err.println("Failed to execute 'get_drug_info' tool: " + e.getMessage());
                e.printStackTrace();
                return "Failed to execute 'get_drug_info' tool, please try again later.";
            }
        }
    }

    // --- Hospital Department Information Query Tool (作为 ChatHandler 的静态嵌套类) ---
    public static class HospitalDepartmentInfoTool implements Tool {
        private ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String getName() { return "get_department_info"; }

        @Override
        public String getDescription() {
            return "Used to query detailed information about hospital departments, including name, description, location, and contact phone number. Use when the user asks about department details, location, or contact information. Input parameters can include 'name' (department name).";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\", \"description\": \"Department name\"}}}";
        }

        @Override
        public String execute(String parametersJson) {
            try {
                JsonNode params = objectMapper.readTree(parametersJson);
                String name = params.has("name") ? params.get("name").asText() : null;

                StringBuilder sqlBuilder = new StringBuilder("SELECT name, description, location, contact_phone FROM hospital_departments WHERE 1=1");
                if (name != null && !name.isEmpty()) sqlBuilder.append(" AND name LIKE ?");

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); // Use ChatHandler's constant
                     PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {

                    int paramIndex = 1;
                    if (name != null && !name.isEmpty()) pstmt.setString(paramIndex++, "%" + name + "%");

                    ResultSet rs = pstmt.executeQuery();
                    StringBuilder result = new StringBuilder("Query results:\n");
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        result.append("Department Name: ").append(rs.getString("name"))
                                .append(", Description: ").append(rs.getString("description"))
                                .append(", Location: ").append(rs.getString("location"))
                                .append(", Contact Phone: ").append(rs.getString("contact_phone")).append("\n");
                    }
                    return found ? result.toString() : "No matching department information found.";
                }
            } catch (Exception e) {
                System.err.println("Failed to execute 'get_department_info' tool: " + e.getMessage());
                e.printStackTrace();
                return "Failed to execute 'get_department_info' tool, please try again later.";
            }
        }
    }

    // --- Doctor Schedule Query Tool (作为 ChatHandler 的静态嵌套类) ---
    public static class DoctorScheduleTool implements Tool {
        private ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String getName() { return "get_doctor_schedule"; }

        @Override
        public String getDescription() {
            return "Used to query the schedule information of a specific doctor. " +
                    "If 'schedule_date' is provided (format:YYYY-MM-DD), it queries the schedule for that specific date. " +
                    "If 'schedule_date' is NOT provided, it queries all available schedule information for the doctor. " +
                    "Input parameters require 'doctor_name' (doctor's name). 'schedule_date' is optional.";
        }

        @Override
        public String getParametersSchema() {
            // schedule_date 现在是可选参数
            return "{\"type\": \"object\", \"properties\": {\"doctor_name\": {\"type\": \"string\", \"description\": \"Doctor's name\"}, \"schedule_date\": {\"type\": \"string\", \"format\": \"date\", \"description\": \"Optional: Query date, format:YYYY-MM-DD\"}}, \"required\": [\"doctor_name\"]}";
        }

        @Override
        public String execute(String parametersJson) {
            try {
                JsonNode params = objectMapper.readTree(parametersJson);
                String doctorName = params.has("doctor_name") ? params.get("doctor_name").asText() : null;
                String scheduleDate = params.has("schedule_date") ? params.get("schedule_date").asText() : null; // scheduleDate现在可以是null

                if (doctorName == null || doctorName.isEmpty()) {
                    return "Error: 'get_doctor_schedule' tool requires 'doctor_name' parameter.";
                }

                StringBuilder sqlBuilder = new StringBuilder(
                        "SELECT d.name AS doctor_name, ds.schedule_date, ds.start_time, ds.end_time, ds.location, ds.available_slots, ds.booked_slots " +
                                "FROM doctor_schedules ds " +
                                "JOIN doctors d ON ds.doctor_id = d.id " +
                                "WHERE d.name LIKE ?"
                );

                // 根据 scheduleDate 是否存在来构建SQL查询
                if (scheduleDate != null && !scheduleDate.isEmpty()) {
                    sqlBuilder.append(" AND ds.schedule_date = ?");
                }
                sqlBuilder.append(" ORDER BY ds.schedule_date ASC, ds.start_time ASC"); // 始终按日期和时间排序

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); // Use ChatHandler's constant
                     PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {

                    int paramIndex = 1;
                    pstmt.setString(1, "%" + doctorName + "%");
                    if (scheduleDate != null && !scheduleDate.isEmpty()) {
                        pstmt.setString(2, scheduleDate);
                    }

                    ResultSet rs = pstmt.executeQuery();
                    StringBuilder result = new StringBuilder("Query results:\n");
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        result.append("Doctor: ").append(rs.getString("doctor_name"))
                                .append(", Date: ").append(rs.getString("schedule_date"))
                                .append(", Time: ").append(rs.getString("start_time")).append("-").append(rs.getString("end_time"))
                                .append(", Location: ").append(rs.getString("location"))
                                .append(", Available Slots: ").append(rs.getInt("available_slots"))
                                .append(", Booked Slots: ").append(rs.getInt("booked_slots")).append("\n");
                    }

                    if (!found) {
                        if (scheduleDate != null && !scheduleDate.isEmpty()) {
                            return "No schedule information found for doctor " + doctorName + " on " + scheduleDate + ".";
                        } else {
                            return "No schedule information found for doctor " + doctorName + ".";
                        }
                    } else {
                        return result.toString();
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to execute 'get_doctor_schedule' tool: " + e.getMessage());
                e.printStackTrace();
                return "Failed to execute 'get_doctor_schedule' tool, please try again later.";
            }
        }
    }
}
