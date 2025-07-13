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
import java.util.HashMap;
import java.util.Map;

public class ChatHandler extends HttpServlet {

    // Database connection constants, for all internal tool classes to use
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "200402135734";
    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver";

    private VectorStore vectorStore; // Knowledge base for RAG
    private LLMAgentClient llmAgentClient; // Client for interacting with the large language model
    private Map<String, Tool> tools; // Collection of tools available to the Agent
    private ObjectMapper objectMapper; // JSON processing tool

    @Override
    public void init() {
        this.objectMapper = new ObjectMapper();
        this.llmAgentClient = new LLMAgentClient(); // Initialize LLM client

        try {
            // Ensure JDBC driver is loaded so that database connections in all tool classes work correctly
            Class.forName(JDBC_DRIVER);

            // 1. Initialize VectorStore and load all knowledge
            this.vectorStore = new VectorStore();
            vectorStore.loadDoctors();            // Load doctor information
            vectorStore.loadDrugs();              // Load drug information
            vectorStore.loadHospitalDepartments();// Load hospital department information
            vectorStore.loadDoctorSchedules();    // Load doctor schedule information
            //vectorStore.loadRagKnowledge();       // Load general medical knowledge, health popularization, etc. unstructured content

            System.out.println("✅ VectorStore initialization complete, all knowledge entries loaded successfully. Total entries: " + vectorStore.entries.size());

            // 2. Initialize tools available to the Agent
            this.tools = new HashMap<>();
            // RAG search is now a tool for the Agent
            this.tools.put("rag_search", new RagSearchTool(vectorStore));
            this.tools.put("get_doctor_info", new DoctorInfoTool());
            this.tools.put("get_drug_info", new DrugInfoTool());
            this.tools.put("get_department_info", new HospitalDepartmentInfoTool());
            this.tools.put("get_doctor_schedule", new DoctorScheduleTool());

        } catch (Exception e) {
            System.err.println("❌ Agent/RAG system initialization failed");
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
            resp.getWriter().write("Error: System not initialized correctly");
            return;
        }

        String body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", (acc, line) -> acc + line);

        String question = extractQuestion(body);

        if (question == null || question.isEmpty()) {
            resp.getWriter().write("Error: Question field not provided");
            return;
        }

        PrintWriter writer = resp.getWriter();

        try {
            // Call the core logic of Agent operation, passing the PrintWriter for streaming
            runAgent(question, writer);
        } catch (Exception e) {
            e.printStackTrace();
            // If an exception occurs, write an error message to the client
            writer.write("Error: " + e.getMessage());
        } finally {
            // Ensure the writer is closed to flush all content
            writer.close();
        }
    }

    private String extractQuestion(String json) {
        try {
            JsonNode rootNode = objectMapper.readTree(json);
            if (rootNode.has("question")) {
                return rootNode.get("question").asText();
            }
            return null;
        } catch (Exception e) {
            System.err.println("Failed to parse JSON: " + e.getMessage());
            return null;
        }
    }

    // --- Core method for Agent operation ---
    // Now takes a PrintWriter to stream the final output directly to the client
    private void runAgent(String userQuestion, PrintWriter finalOutputWriter) throws IOException {
        // Build the Agent's system prompt, telling the LLM what it is and what tools it has
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("你是一个专业的医疗AI助手。你需要根据用户的提问，决定是使用工具来获取信息，还是直接回答。\n");
        systemPromptBuilder.append("请严格遵循以下思考和工具调用流程：\n");
        systemPromptBuilder.append("1. **思考 (Thought):** 首先思考用户的意图，以及为了回答问题可能需要哪些信息。\n");
        systemPromptBuilder.append("   - 如果一个问题需要多个步骤或多个工具来回答，请先调用第一个必要的工具。\n");
        systemPromptBuilder.append("   - 你的思考过程应该清晰、逐步，并解释为什么选择某个工具或如何得出最终答案。\n");
        systemPromptBuilder.append("2. **工具调用 (Tool Call):** 如果你需要使用工具，请严格以以下JSON格式返回。在JSON之前可以有思考内容：\n");
        systemPromptBuilder.append("   ```json\n");
        systemPromptBuilder.append("   {\n");
        systemPromptBuilder.append("     \"tool_name\": \"<工具名称>\",\n");
        systemPromptBuilder.append("     \"parameters\": {\n");
        systemPromptBuilder.append("       \"param1\": \"value1\",\n");
        systemPromptBuilder.append("       \"param2\": \"value2\"\n");
        systemPromptBuilder.append("     }\n");
        systemPromptBuilder.append("   }\n");
        systemPromptBuilder.append("   ```\n");
        systemPromptBuilder.append("3. **观察 (Observation):** (这个步骤由系统完成，你不需要输出，但你需要理解它的作用)\n");
        systemPromptBuilder.append("   当你返回一个工具调用后，我（系统）会执行该工具，并将工具的**返回结果**作为“Observation”提供给你。你将收到包含此Observation的新Prompt。\n");
        systemPromptBuilder.append("4. **继续思考/工具调用/最终回答:**\n");
        systemPromptBuilder.append("   - **如果 Observation 提供了足够的信息来回答用户问题，请直接给出最终答案。**\n");
        systemPromptBuilder.append("   - **如果 Observation 不足以回答用户问题，但你需要进一步的信息，请继续你的“思考”过程，并决定调用下一个工具。**\n");
        systemPromptBuilder.append("   - **如果所有工具都无法回答，请直接给出最终答案，说明你无法提供帮助。**\n\n");
        systemPromptBuilder.append("重要提示：\n");
        systemPromptBuilder.append("- 每次迭代，你都将收到新的上下文，包括用户问题、你之前的思考、工具调用以及工具的观察结果。\n");
        systemPromptBuilder.append("- 你必须在每次响应中明确地输出“思考 (Thought)”、一个“工具调用 (Tool Call)”或一个“最终回答 (Final Answer)”。\n\n");

        systemPromptBuilder.append("你可以使用的工具及其描述如下：\n");
        for (Tool tool : tools.values()) {
            systemPromptBuilder.append("  - 工具名称: `").append(tool.getName()).append("`\n");
            systemPromptBuilder.append("    描述: ").append(tool.getDescription()).append("\n");
            systemPromptBuilder.append("    参数Schema: ").append(tool.getParametersSchema()).append("\n"); // 包含参数Schema
        }
        systemPromptBuilder.append("\n");
        // Initial Prompt, including user question
        String currentPrompt = systemPromptBuilder.toString() + "用户问题: " + userQuestion + "\n";


        int maxIterations = 5; // Limit maximum iterations to prevent Agent from falling into an infinite loop (increased for chaining)
        for (int i = 0; i < maxIterations; i++) {
            System.out.println("\n--- Agent 迭代 " + (i + 1) + " ---");
            System.out.println("发送给 LLM 的 Prompt:\n" + currentPrompt);

            // Collect LLM response internally for parsing tool calls AND stream to client
            StringBuilder llmResponseBuilder = new StringBuilder();
            try {
                llmAgentClient.getAgentResponse(currentPrompt, delta -> {
                    llmResponseBuilder.append(delta); // Collect for internal parsing
                    finalOutputWriter.write(delta);   // Stream directly to client
                    finalOutputWriter.flush();        // Flush immediately to ensure real-time appearance
                });
            } catch (Exception e) {
                System.err.println("Error during LLM streaming for Agent: " + e.getMessage());
                e.printStackTrace();
                // If an error occurs during LLM call, write to final output and exit
                finalOutputWriter.write("\n抱歉，AI服务在处理过程中出现错误：" + e.getMessage() + "\n");
                finalOutputWriter.flush();
                return;
            }
            String llmResponse = llmResponseBuilder.toString();
            System.out.println("LLM 原始响应 (Collected from stream):\n" + llmResponse); // This log is now for debugging collected full response

            // Attempt to parse tool call from LLM response
            try {
                // LLM may output thought process before/after JSON, need to extract JSON part
                int jsonStartIndex = llmResponse.indexOf("{");
                int jsonEndIndex = llmResponse.lastIndexOf("}");
                String potentialToolCallJson = null;

                if (jsonStartIndex != -1 && jsonEndIndex != -1 && jsonEndIndex > jsonStartIndex) {
                    potentialToolCallJson = llmResponse.substring(jsonStartIndex, jsonEndIndex + 1);
                }

                if (potentialToolCallJson != null) {
                    JsonNode toolCallNode = objectMapper.readTree(potentialToolCallJson);
                    String toolName = toolCallNode.has("tool_name") ? toolCallNode.get("tool_name").asText() : null;
                    JsonNode parametersNode = toolCallNode.has("parameters") ? toolCallNode.get("parameters") : null;

                    if (toolName != null && tools.containsKey(toolName) && parametersNode != null) {
                        // This is a tool call
                        Tool toolToExecute = tools.get(toolName);
                        String parametersJson = parametersNode.toString();
                        System.out.println("Agent decided to call tool: " + toolName + ", parameters: " + parametersJson);

                        // Stream a message to the client indicating tool execution
                        finalOutputWriter.write("\n[Agent 正在执行工具: `" + toolName + "`，参数: " + parametersJson + "...]\n");
                        finalOutputWriter.flush();

                        String toolOutput = toolToExecute.execute(parametersJson); // Execute the tool
                        System.out.println("工具 `" + toolName + "` 返回结果:\n" + toolOutput);

                        // Stream tool output to the client for transparency
                        finalOutputWriter.write("\n[工具 `" + toolName + "` 返回结果:\n" + toolOutput + "]\n");
                        finalOutputWriter.flush();

                        // Use tool output as new context, rebuild Prompt, send to LLM to generate final answer
                        // Ensure original question and all history are included. Add "Observation" tag.
                        currentPrompt = systemPromptBuilder.toString() +
                                "用户问题: " + userQuestion + "\n\n" + // Keep original user question for context
                                "你之前的思考和工具调用:\n" + llmResponse + "\n" + // Include previous LLM output (Thought + Tool Call)
                                "观察 (Observation):\n" + toolOutput + "\n\n" + // Add observation
                                "请根据以上信息，给出最终回答或决定下一步操作。\n";
                        // Continue to the next iteration, expecting LLM to provide the final answer
                        continue; // Skip the rest of the current loop, proceed to the next iteration
                    }
                }
                // If no valid tool call is parsed, or tool call failed, then this is the final answer
                // The llmResponse was already streamed by the onDelta consumer, so just return.
                return; // Exit runAgent after streaming the final answer

            } catch (Exception e) {
                // Failed to parse LLM response or execute tool, assume LLM's original response was the final answer
                System.err.println("Failed to parse LLM response or execute tool, streaming LLM original response as final answer: " + e.getMessage());
                // The llmResponse was already streamed, so just return.
                return; // Exit runAgent
            }
        }
        // Reached maximum iterations, still unable to provide an answer
        finalOutputWriter.write("抱歉，我未能找到合适的答案或完成操作。请尝试换个问法。");
        finalOutputWriter.flush();
    }

    // --- Define the Tool interface that Agent can use ---
    public interface Tool {
        String getName(); // Tool name for LLM recognition and invocation
        String getDescription(); // Tool description, explaining its function and use cases for LLM understanding
        String getParametersSchema(); // JSON Schema description of tool parameters, helping LLM build parameters correctly
        String execute(String parametersJson); // Execute the tool, pass parameters in JSON format, return execution result (string)
    }

    // --- RAG Search Tool (as a static nested class of ChatHandler) ---
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

    // --- Doctor Information Query Tool (as a static nested class of ChatHandler) ---
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

    // --- Drug Information Query Tool (as a static nested class of ChatHandler) ---
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

    // --- Hospital Department Information Query Tool (as a static nested class of ChatHandler) ---
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

    // --- Doctor Schedule Query Tool (as a static nested class of ChatHandler) ---
    public static class DoctorScheduleTool implements Tool {
        private ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String getName() { return "get_doctor_schedule"; }

        @Override
        public String getDescription() {
            return "Used to query the schedule information of a specific doctor. " +
                    "If 'schedule_date' is provided (format: YYYY-MM-DD), it queries the schedule for that specific date. " +
                    "If 'schedule_date' is NOT provided, it queries all available schedule information for the doctor. " +
                    "Input parameters require 'doctor_name' (doctor's name). 'schedule_date' is optional.";
        }

        @Override
        public String getParametersSchema() {
            // schedule_date 现在是可选参数
            return "{\"type\": \"object\", \"properties\": {\"doctor_name\": {\"type\": \"string\", \"description\": \"Doctor's name\"}, \"schedule_date\": {\"type\": \"string\", \"format\": \"date\", \"description\": \"Optional: Query date, format: YYYY-MM-DD\"}}, \"required\": [\"doctor_name\"]}";
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
