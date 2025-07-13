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
import java.util.UUID; // 用来生成会话ID (session_id)
import java.util.stream.Collectors;


public class ChatSessionServlet extends HttpServlet {

    private Gson gson = new Gson(); // JSON 处理工具

    // 数据库连接信息（请替换为您的实际信息）
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
    private static final String DB_USER = "root"; // 您的数据库用户名
    private static final String DB_PASSWORD = "200402135734"; // 您的数据库密码

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取 Servlet 的映射路径，例如 "/api/chat/sessions"
        String servletPath = req.getServletPath();
        // 获取请求路径中 Servlet 路径之后的部分，例如 "/new" 或 null
        String pathInfo = req.getPathInfo();

        // === 调试信息：显示接收到的请求路径 ===
        System.out.println("调试: 收到 POST 请求，servletPath: " + servletPath + ", pathInfo: " + pathInfo);

        // 设置 HTTP 响应的内容类型为 JSON，并使用 UTF-8 编码
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter(); // 获取用于发送响应给客户端的写入器

        try {
            // 从请求体中读取所有内容，并将其拼接成一个完整的 JSON 字符串
            String requestBody = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            // 使用 Gson 库解析 JSON 字符串为 JsonObject 对象
            JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();

            // 创建新会话
            if ("/api/chat/sessions".equals(servletPath) && "/new".equals(pathInfo)) {
                // --- 处理创建新会话的请求：/api/chat/sessions/new ---
                System.out.println("调试: 正在处理 /api/chat/sessions/new 请求。");
                int userId = jsonRequest.get("user_id").getAsInt(); // 获取用户ID
                // 获取初始消息，如果请求中没有提供，则默认为“新对话”
                String initialMessage = jsonRequest.has("initial_message") ? jsonRequest.get("initial_message").getAsString() : "新对话";
                // 根据初始消息生成会话标题，如果消息太长则截断
                String sessionTitle = initialMessage.isEmpty() ? "新对话" :
                        (initialMessage.length() > 20 ? initialMessage.substring(0, 20) + "..." : initialMessage);

                // 调用本类内部方法来创建新的会话并保存到数据库
                Session newSession = createNewSession(userId, sessionTitle);
                if (newSession != null) {
                    // 如果提供了初始消息，则将其作为用户消息保存到新创建的会话中
                    if (!initialMessage.isEmpty()) {
                        saveMessage(newSession.sessionId, "user", initialMessage);
                    }
                    // 将新会话对象转换成 JSON 格式并发送回客户端
                    out.print(gson.toJson(newSession));
                    resp.setStatus(HttpServletResponse.SC_OK); // 设置 HTTP 状态码为 200 OK
                } else {
                    // 如果创建失败，返回 500 内部服务器错误
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    out.print("{\"message\": \"创建新会话失败\"}");
                }
            } else {
                // 如果请求路径不匹配任何已知的 POST API，返回 404 未找到
                System.out.println("调试: doPost 中未找到路径。servletPath: " + servletPath + ", pathInfo: " + pathInfo);
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.print("{\"message\": \"API 未找到\"}");
            }
        } catch (Exception e) {
            // 捕获所有处理过程中的异常，打印错误日志，并向客户端返回 500 内部服务器错误
            System.err.println("ChatSessionServlet POST 请求出错: " + e.getMessage());
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"message\": \"内部服务器错误: " + e.getMessage() + "\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        System.out.println("调试: 收到 GET 请求，servletPath: " + servletPath + ", pathInfo: " + pathInfo);

        // 设置 HTTP 响应的内容类型为 JSON，并使用 UTF-8 编码
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter(); // 获取用于发送响应给客户端的写入器

        try {
            // --- 处理获取所有会话列表的请求：/api/chat/sessions?user_id=... ---
            if ("/api/chat/sessions".equals(servletPath) && (pathInfo == null || "/".equals(pathInfo))) {
                System.out.println("调试: 正在处理 /api/chat/sessions (GET) 请求。");
                String userIdParam = req.getParameter("user_id"); // 从 URL 查询参数中获取用户ID
                if (userIdParam == null || userIdParam.isEmpty()) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST); // 400 错误请求
                    out.print("{\"message\": \"缺少 user_id 参数\"}");
                    return;
                }
                int userId = Integer.parseInt(userIdParam); // 将用户ID字符串转换为整数
                // 调用本类内部方法获取该用户的所有会话
                List<Session> sessions = getSessionsByUserId(userId);
                out.print(gson.toJson(sessions)); // 将会话列表转换成 JSON 格式并发送回客户端
                resp.setStatus(HttpServletResponse.SC_OK); // 设置 HTTP 状态码为 200 OK
            }
            // --- 处理获取特定会话消息历史的请求：/api/chat/sessions/{session_id}/messages?user_id=... ---
            else if ("/api/chat/sessions".equals(servletPath) && pathInfo != null && pathInfo.endsWith("/messages")) {
                System.out.println("调试: 正在处理 /api/chat/sessions/{id}/messages (GET) 请求。");
                String[] pathParts = pathInfo.split("/"); // 将路径信息按 '/' 分割
                // 例如，如果 pathInfo 是 /a1b2c3d4/messages，那么 pathParts 会是 ["", "a1b2c3d4", "messages"]
                if (pathParts.length >= 3) {
                    String sessionId = pathParts[pathParts.length - 2]; // 提取会话ID，它是倒数第二个部分

                    String userIdParam = req.getParameter("user_id"); // 从 URL 查询参数中获取用户ID
                    if (userIdParam == null || userIdParam.isEmpty()) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        out.print("{\"message\": \"获取消息时缺少 user_id 参数\"}");
                        return;
                    }
                    int userId = Integer.parseInt(userIdParam);

                    // 权限检查：验证会话ID和用户ID是否匹配
                    if (!isValidSessionAndUser(sessionId, userId)) {
                        resp.setStatus(HttpServletResponse.SC_FORBIDDEN); // 返回 403 禁止访问
                        out.print("{\"message\": \"禁止访问: 获取消息时会话ID或用户ID无效\"}");
                        return;
                    }

                    // 调用本类内部方法获取该会话的所有消息
                    List<Message> messages = getMessagesBySessionId(sessionId);
                    out.print(gson.toJson(messages)); // 将消息列表转换成 JSON 格式并发送回客户端
                    resp.setStatus(HttpServletResponse.SC_OK); // 设置 HTTP 状态码为 200 OK
                } else {
                    // 路径格式不正确
                    System.out.println("调试: GET /api/chat/sessions/{id}/messages 的路径信息无效: " + pathInfo);
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST); // 400 错误请求
                    out.print("{\"message\": \"消息路径格式无效\"}");
                }
            }
            else {
                // 如果请求路径不匹配任何已知的 GET API，返回 404 未找到
                System.out.println("调试: doGet 中未找到路径。servletPath: " + servletPath + ", pathInfo: " + pathInfo);
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.print("{\"message\": \"API 未找到\"}");
            }
        } catch (NumberFormatException e) {
            // 如果 user_id 参数不是有效的数字格式
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"message\": \"user_id 格式无效\"}");
        } catch (Exception e) {
            // 捕获所有处理过程中的异常，打印错误日志，并向客户端返回 500 内部服务器错误
            System.err.println("ChatSessionServlet GET 请求出错: " + e.getMessage());
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"message\": \"内部服务器错误: " + e.getMessage() + "\"}");
        }
    }

    // --- 以下是直接集成到 Servlet 内部的数据库访问逻辑 ---

    // 获取数据库连接
    private Connection getConnection() throws SQLException {
        try {
            // 加载 MySQL JDBC 驱动
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            // 如果找不到驱动，抛出 SQL 异常，并提示需要添加 mysql-connector-java 依赖
            throw new SQLException("未找到 MySQL JDBC 驱动。请确保您的 classpath 中包含 mysql-connector-java。");
        }
        // 使用 DriverManager 获取数据库连接，传入 URL、用户名和密码
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // --- 数据模型类 (POJO)，严格对应数据库字段 ---
    // 这些类用于在 Java 代码中表示数据库表中的一行数据
    public static class Session {
        public String sessionId; // 会话ID
        public int userId;       // 用户ID
        public String title;     // 会话标题
        public Timestamp createdAt; // 会话创建时间 (对应数据库表的 created_at 字段)
        public Timestamp updatedAt; // 会话更新时间 (对应数据库表的 updated_at 字段)

        // 构造函数，用于创建 Session 对象
        public Session(String sessionId, int userId, String title, Timestamp createdAt, Timestamp updatedAt) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static class Message {
        public int id;           // 消息ID
        public String sessionId; // 所属会话ID
        public int messageIndex; // 消息在会话中的顺序
        public String role;      // 消息角色，'user' (用户) 或 'assistant' (助手)
        public String content;   // 消息内容
        public Timestamp timestamp; // 消息时间戳

        // 构造函数，用于创建 Message 对象
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
     * 创建一个新会话并将其保存到数据库中。
     * @param userId 用户ID
     * @param initialTitle 会话的初始标题
     * @return 新创建的 Session 对象；如果失败，则返回 null
     */
    private Session createNewSession(int userId, String initialTitle) {
        String sessionId = UUID.randomUUID().toString(); // 生成一个全球唯一的会话 ID
        String sql = "INSERT INTO dialog_sessions (session_id, user_id, title) VALUES (?, ?, ?)"; // SQL 插入语句
        try (Connection conn = getConnection(); // 获取数据库连接
             PreparedStatement pstmt = conn.prepareStatement(sql)) { // 准备 SQL 语句
            pstmt.setString(1, sessionId);     // 设置 session_id 参数
            pstmt.setInt(2, userId);          // 设置 user_id 参数
            pstmt.setString(3, initialTitle); // 设置 title 参数
            int affectedRows = pstmt.executeUpdate(); // 执行插入操作，返回受影响的行数
            if (affectedRows > 0) {
                // 如果插入成功，返回一个 Session 对象，包含当前时间戳作为创建和更新时间
                return new Session(sessionId, userId, initialTitle, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis()));
            }
        } catch (SQLException e) {
            System.err.println("创建新会话出错: " + e.getMessage());
            e.printStackTrace();
        }
        return null; // 如果出现异常或插入失败，返回 null
    }

    /**
     * 获取指定用户的所有聊天会话列表。
     * @param userId 用户ID
     * @return 会话列表，按更新时间 (updated_at) 降序排列（最新活跃的在前面）
     */
    private List<Session> getSessionsByUserId(int userId) {
        List<Session> sessions = new ArrayList<>();
        String sql = "SELECT session_id, user_id, title, created_at, updated_at FROM dialog_sessions WHERE user_id = ? ORDER BY updated_at DESC"; // SQL 查询语句
        try (Connection conn = getConnection(); // 获取数据库连接
             PreparedStatement pstmt = conn.prepareStatement(sql)) { // 准备 SQL 语句
            pstmt.setInt(1, userId); // 设置 user_id 参数
            try (ResultSet rs = pstmt.executeQuery()) { // 执行查询并获取结果集
                while (rs.next()) { // 遍历结果集中的每一行
                    sessions.add(new Session( // 根据结果创建 Session 对象并添加到列表中
                            rs.getString("session_id"),
                            rs.getInt("user_id"),
                            rs.getString("title"),
                            rs.getTimestamp("created_at"),
                            rs.getTimestamp("updated_at")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("获取用户 " + userId + " 的会话出错: " + e.getMessage());
            e.printStackTrace();
        }
        return sessions; // 返回会话列表
    }

    /**
     * 验证会话ID和用户ID是否匹配，用于权限检查，确保用户只能访问自己的会话。
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 如果会话存在且属于该用户，则返回 true；否则返回 false
     */
    private boolean isValidSessionAndUser(String sessionId, int userId) {
        String sql = "SELECT COUNT(*) FROM dialog_sessions WHERE session_id = ? AND user_id = ?"; // SQL 查询语句，统计匹配的行数
        try (Connection conn = getConnection(); // 获取数据库连接
             PreparedStatement pstmt = conn.prepareStatement(sql)) { // 准备 SQL 语句
            pstmt.setString(1, sessionId); // 设置 session_id 参数
            pstmt.setInt(2, userId);      // 设置 user_id 参数
            try (ResultSet rs = pstmt.executeQuery()) { // 执行查询
                if (rs.next()) {
                    return rs.getInt(1) > 0; // 如果匹配的行数大于 0，则表示有效
                }
            }
        } catch (SQLException e) {
            System.err.println("验证用户 " + userId + " 的会话出错: " + e.getMessage());
            e.printStackTrace();
        }
        return false; // 默认返回 false
    }

    // --- 消息相关操作 ---

    /**
     * 获取指定会话中的所有消息。
     * @param sessionId 会话ID
     * @return 消息列表，按 message_index 升序排列（按发送顺序）
     */
    private List<Message> getMessagesBySessionId(String sessionId) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT id, session_id, message_index, role, content, timestamp FROM dialog_messages WHERE session_id = ? ORDER BY message_index ASC"; // SQL 查询语句
        try (Connection conn = getConnection(); // 获取数据库连接
             PreparedStatement pstmt = conn.prepareStatement(sql)) { // 准备 SQL 语句
            pstmt.setString(1, sessionId); // 设置 session_id 参数
            try (ResultSet rs = pstmt.executeQuery()) { // 执行查询
                while (rs.next()) { // 遍历结果集
                    messages.add(new Message( // 创建 Message 对象并添加到列表中
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
            System.err.println("获取会话 " + sessionId + " 的消息出错: " + e.getMessage());
            e.printStackTrace();
        }
        return messages; // 返回消息列表
    }

    /**
     * 保存一条消息到数据库，并同时更新对应会话的“最近更新时间”。
     * 这是一个**事务性操作**，确保消息的保存和会话时间的更新要么都成功，要么都失败，保证数据一致性。
     * @param sessionId 消息所属的会话ID
     * @param role 消息角色 ('user' 表示用户，'assistant' 表示助手)
     * @param content 消息的实际内容
     * @return 新插入消息的数据库ID；如果失败，则返回 -1
     */
    private int saveMessage(String sessionId, String role, String content) {
        int messageId = -1; // 默认消息ID为 -1
        Connection conn = null; // 声明数据库连接对象，以便在 finally 块中关闭
        try {
            conn = getConnection(); // 获取数据库连接
            conn.setAutoCommit(false); // **关闭自动提交：开始事务**

            // 1. 获取当前会话中最大的消息索引，以便为新消息分配下一个顺序
            String getMaxIndexSql = "SELECT COALESCE(MAX(message_index), 0) FROM dialog_messages WHERE session_id = ?"; // SQL 查询最大索引
            int nextMessageIndex = 0;
            try (PreparedStatement pstmt = conn.prepareStatement(getMaxIndexSql)) {
                pstmt.setString(1, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        nextMessageIndex = rs.getInt(1) + 1; // 新消息的索引是当前最大索引加 1
                    }
                }
            }

            // 2. 将新消息插入到 dialog_messages 表中
            String insertMessageSql = "INSERT INTO dialog_messages (session_id, message_index, role, content) VALUES (?, ?, ?, ?)"; // SQL 插入语句
            try (PreparedStatement pstmt = conn.prepareStatement(insertMessageSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, sessionId);     // 设置 session_id
                pstmt.setInt(2, nextMessageIndex); // 设置消息顺序
                pstmt.setString(3, role);          // 设置消息角色
                pstmt.setString(4, content);       // 设置消息内容
                pstmt.executeUpdate(); // 执行插入操作

                // 获取数据库为新插入消息生成的自增 ID
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        messageId = generatedKeys.getInt(1);
                    }
                }
            }

            // 3. 更新对应会话的 updated_at 时间，使其在会话列表中排在最前面
            String updateSessionSql = "UPDATE dialog_sessions SET updated_at = CURRENT_TIMESTAMP WHERE session_id = ?"; // SQL 更新语句
            try (PreparedStatement pstmt = conn.prepareStatement(updateSessionSql)) {
                pstmt.setString(1, sessionId); // 设置 session_id
                pstmt.executeUpdate(); // 执行更新操作
            }

            conn.commit(); // **提交事务：如果所有操作都成功，则永久保存更改**
        } catch (SQLException e) {
            System.err.println("保存消息并更新会话出错: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback(); // **回滚事务：如果发生任何 SQL 异常，撤销所有更改**
                } catch (SQLException ex) {
                    System.err.println("回滚事务出错: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // **恢复连接的自动提交模式**
                    conn.close(); // 关闭数据库连接，释放资源
                } catch (SQLException e) {
                    System.err.println("关闭数据库连接出错: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        return messageId; // 返回消息ID
    }
}
