// src/main/java/rag/ChatSessionServlet.java
package rag;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID; // 用于生成 session_id
import java.util.stream.Collectors;

// 引入 Main 类中的 LLM 异常
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;

public class ChatSessionServlet extends HttpServlet {

    private Gson gson = new Gson();
    // private SessionService sessionService = new SessionService(); // 不再需要独立的 SessionService 实例

    // 数据库连接信息（请替换为你的实际信息）
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
    private static final String DB_USER = "root"; // 替换为你的数据库用户名
    private static final String DB_PASSWORD = "root"; // 替换为你的数据库密码

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String servletPath = req.getServletPath(); // 获取Servlet的映射路径，如 "/api/chat/sessions" 或 "/api/chat/message"
        String pathInfo = req.getPathInfo();      // 获取Servlet路径后的额外路径信息，如 "/new" 或 null

        // === 调试打印：显示收到的请求路径信息 ===
        System.out.println("Debug: Received POST request for servletPath: " + servletPath + ", pathInfo: " + pathInfo);

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();

        try {
            String requestBody = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();

            // 根据 servletPath 和 pathInfo 判断具体的API
            if ("/api/chat/sessions".equals(servletPath) && "/new".equals(pathInfo)) {
                System.out.println("Debug: Handling /api/chat/sessions/new request.");
                int userId = jsonRequest.get("user_id").getAsInt();
                String initialMessage = jsonRequest.has("initial_message") ? jsonRequest.get("initial_message").getAsString() : "新对话";
                String sessionTitle = initialMessage.isEmpty() ? "新对话" :
                        (initialMessage.length() > 20 ? initialMessage.substring(0, 20) + "..." : initialMessage);

                // 直接调用本类中的方法
                Session newSession = createNewSession(userId, sessionTitle);
                if (newSession != null) {
                    if (!initialMessage.isEmpty()) {
                        saveMessage(newSession.sessionId, "user", initialMessage);
                    }
                    out.print(gson.toJson(newSession));
                    resp.setStatus(HttpServletResponse.SC_OK);
                } else {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    out.print("{\"message\": \"Failed to create new session\"}");
                }
            } else if ("/api/chat/message".equals(servletPath) && pathInfo == null) {
                // 当映射为 /api/chat/message 且请求URL精确匹配时，pathInfo 为 null
                System.out.println("Debug: Handling /api/chat/message request.");
                String sessionId = jsonRequest.get("session_id").getAsString();
                int userId = jsonRequest.get("user_id").getAsInt();
                String userContent = jsonRequest.get("content").getAsString();

                // 直接调用本类中的方法
                if (!isValidSessionAndUser(sessionId, userId)) {
                    resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    out.print("{\"message\": \"Forbidden: Invalid session or user ID\"}");
                    return;
                }

                // 1. 保存用户消息
                // 直接调用本类中的方法
                saveMessage(sessionId, "user", userContent);

                // 2. 调用大模型获取回复并收集流式片段
                StringBuilder aiReplyBuilder = new StringBuilder(); // 用于拼接所有流式片段
                try {
                    Main.streamCallWithHandler(userContent, delta -> {
                        aiReplyBuilder.append(delta); // 收集每个片段
                    });
                } catch (NoApiKeyException | ApiException | InputRequiredException e) {
                    System.err.println("Error calling LLM: " + e.getMessage());
                    e.printStackTrace();
                    aiReplyBuilder.append("AI服务暂时不可用，请稍后再试。"); // 友好提示
                }
                String aiReplyContent = aiReplyBuilder.toString(); // 获取完整的AI回复

                // 3. 保存AI回复
                // 直接调用本类中的方法
                int aiMessageId = saveMessage(sessionId, "assistant", aiReplyContent);

                // 4. 返回AI回复及相关信息
                JsonObject responseJson = new JsonObject();
                responseJson.addProperty("ai_reply", aiReplyContent);
                responseJson.addProperty("message_id", aiMessageId);
                responseJson.addProperty("timestamp", new java.util.Date().toInstant().toString());
                out.print(gson.toJson(responseJson));
                resp.setStatus(HttpServletResponse.SC_OK);

            } else {
                System.out.println("Debug: Path not found in doPost. ServletPath: " + servletPath + ", PathInfo: " + pathInfo);
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.print("{\"message\": \"API Not Found\"}");
            }
        } catch (Exception e) {
            System.err.println("Error in ChatSessionServlet POST: " + e.getMessage());
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"message\": \"Internal server error: " + e.getMessage() + "\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        System.out.println("Debug: Received GET request for servletPath: " + servletPath + ", pathInfo: " + pathInfo);

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();

        try {
            // 获取所有会话列表 /api/chat/sessions?user_id=...
            if ("/api/chat/sessions".equals(servletPath) && (pathInfo == null || "/".equals(pathInfo))) {
                System.out.println("Debug: Handling /api/chat/sessions (GET) request.");
                String userIdParam = req.getParameter("user_id");
                if (userIdParam == null || userIdParam.isEmpty()) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    out.print("{\"message\": \"Missing user_id parameter\"}");
                    return;
                }
                int userId = Integer.parseInt(userIdParam);
                // 直接调用本类中的方法
                List<Session> sessions = getSessionsByUserId(userId);
                out.print(gson.toJson(sessions));
                resp.setStatus(HttpServletResponse.SC_OK);
            }
            // 获取特定会话的消息 /api/chat/sessions/{session_id}/messages?user_id=...
            else if ("/api/chat/sessions".equals(servletPath) && pathInfo != null && pathInfo.endsWith("/messages")) {
                System.out.println("Debug: Handling /api/chat/sessions/{id}/messages (GET) request.");
                String[] pathParts = pathInfo.split("/");
                // pathInfo for /api/chat/sessions/{sessionId}/messages will be /{sessionId}/messages
                // pathParts will be ["", "{sessionId}", "messages"]
                if (pathParts.length >= 3) {
                    String sessionId = pathParts[pathParts.length - 2];

                    String userIdParam = req.getParameter("user_id");
                    if (userIdParam == null || userIdParam.isEmpty()) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        out.print("{\"message\": \"Missing user_id parameter for message retrieval\"}");
                        return;
                    }
                    int userId = Integer.parseInt(userIdParam);

                    // 直接调用本类中的方法
                    if (!isValidSessionAndUser(sessionId, userId)) {
                        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        out.print("{\"message\": \"Forbidden: Invalid session or user ID for messages\"}");
                        return;
                    }

                    // 直接调用本类中的方法
                    List<Message> messages = getMessagesBySessionId(sessionId);
                    out.print(gson.toJson(messages));
                    resp.setStatus(HttpServletResponse.SC_OK);
                } else {
                    System.out.println("Debug: Invalid pathInfo for GET /api/chat/sessions/{id}/messages: " + pathInfo);
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST); // Path format invalid
                    out.print("{\"message\": \"Invalid path format for messages\"}");
                }
            }
            else {
                System.out.println("Debug: Path not found in doGet. ServletPath: " + servletPath + ", PathInfo: " + pathInfo);
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.print("{\"message\": \"API Not Found\"}");
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"message\": \"Invalid user_id format\"}");
        } catch (Exception e) {
            System.err.println("Error in ChatSessionServlet GET: " + e.getMessage());
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"message\": \"Internal server error: " + e.getMessage() + "\"}");
        }
    }

    // --- 以下是直接集成到 Servlet 内部的数据库访问逻辑 (原 SessionService 中的方法) ---

    // 获取数据库连接
    private Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver"); // 加载MySQL驱动
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new SQLException("MySQL JDBC Driver not found. Please add mysql-connector-java to your classpath.");
        }
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // --- 数据模型类 (POJO)，严格对应数据库字段 ---
    // 这些类将用于在Java代码中表示数据库行
    public static class Session {
        public String sessionId;
        public int userId;
        public String title;
        public Timestamp createdAt; // 对应 dialog_sessions 表的 created_at
        public Timestamp updatedAt; // 对应 dialog_sessions 表的 updated_at

        public Session(String sessionId, int userId, String title, Timestamp createdAt, Timestamp updatedAt) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static class Message {
        public int id;
        public String sessionId;
        public int messageIndex;
        public String role;
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

    // --- 会话相关操作 ---

    /**
     * 创建一个新会话并保存到数据库。
     * @param userId 用户ID
     * @param initialTitle 会话的初始标题
     * @return 新创建的会话对象，如果失败返回null
     */
    private Session createNewSession(int userId, String initialTitle) {
        String sessionId = UUID.randomUUID().toString(); // 生成唯一Session ID
        String sql = "INSERT INTO dialog_sessions (session_id, user_id, title) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setInt(2, userId);
            pstmt.setString(3, initialTitle);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                // 返回完整的Session对象，包括数据库自动生成的 created_at 和 updated_at
                // 这里需要重新查询一次来获取完整的时间戳，或者假设默认行为
                // 为了简化，这里直接返回一个包含当前时间的Session对象
                return new Session(sessionId, userId, initialTitle, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis()));
            }
        } catch (SQLException e) {
            System.err.println("Error creating new session: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取指定用户的所有会话列表。
     * @param userId 用户ID
     * @return 会话列表，按更新时间降序排列
     */
    private List<Session> getSessionsByUserId(int userId) {
        List<Session> sessions = new ArrayList<>();
        String sql = "SELECT session_id, user_id, title, created_at, updated_at FROM dialog_sessions WHERE user_id = ? ORDER BY updated_at DESC";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    sessions.add(new Session(
                            rs.getString("session_id"),
                            rs.getInt("user_id"),
                            rs.getString("title"),
                            rs.getTimestamp("created_at"),
                            rs.getTimestamp("updated_at")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting sessions for user " + userId + ": " + e.getMessage());
            e.printStackTrace();
        }
        return sessions;
    }

    /**
     * 验证会话ID和用户ID是否匹配，用于权限校验。
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 如果会话存在且属于该用户，返回true；否则返回false
     */
    private boolean isValidSessionAndUser(String sessionId, int userId) {
        String sql = "SELECT COUNT(*) FROM dialog_sessions WHERE session_id = ? AND user_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setInt(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error validating session for user " + userId + ": " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    // --- 消息相关操作 ---

    /**
     * 获取某个会话的所有消息。
     * @param sessionId 会话ID
     * @return 消息列表，按 message_index 升序排列
     */
    private List<Message> getMessagesBySessionId(String sessionId) {
        List<Message> messages = new ArrayList<>();
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
}