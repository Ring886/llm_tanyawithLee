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
import java.util.UUID; // Used to generate session_id
import java.util.stream.Collectors;

// Removed LLM-related imports, as this servlet will no longer call the LLM directly.
// import com.alibaba.dashscope.exception.ApiException;
// import com.alibaba.dashscope.exception.InputRequiredException;
// import com.alibaba.dashscope.exception.NoApiKeyException;

public class ChatSessionServlet extends HttpServlet {

    private Gson gson = new Gson();
    // private SessionService sessionService = new SessionService(); // No longer needs a separate SessionService instance

    // Database connection information (please replace with your actual information)
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
    private static final String DB_USER = "root"; // Replace with your database username
    private static final String DB_PASSWORD = "root"; // Replace with your database password

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String servletPath = req.getServletPath(); // Get Servlet's mapping path, e.g., "/api/chat/sessions" or "/api/chat/message"
        String pathInfo = req.getPathInfo();      // Get additional path information after Servlet path, e.g., "/new" or null

        // === Debug print: Show received request path information ===
        System.out.println("Debug: Received POST request for servletPath: " + servletPath + ", pathInfo: " + pathInfo);

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();

        try {
            String requestBody = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();

            // Determine the specific API based on servletPath and pathInfo
            if ("/api/chat/sessions".equals(servletPath) && "/new".equals(pathInfo)) {
                System.out.println("Debug: Handling /api/chat/sessions/new request.");
                int userId = jsonRequest.get("user_id").getAsInt();
                String initialMessage = jsonRequest.has("initial_message") ? jsonRequest.get("initial_message").getAsString() : "New conversation";
                String sessionTitle = initialMessage.isEmpty() ? "New conversation" :
                        (initialMessage.length() > 20 ? initialMessage.substring(0, 20) + "..." : initialMessage);

                // Call method within this class directly
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
                // When mapped as /api/chat/message and request URL exactly matches, pathInfo is null
                System.out.println("Debug: Handling /api/chat/message request.");
                String sessionId = jsonRequest.get("session_id").getAsString();
                int userId = jsonRequest.get("user_id").getAsInt();
                String userContent = jsonRequest.get("content").getAsString();

                // Call method within this class directly
                if (!isValidSessionAndUser(sessionId, userId)) {
                    resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    out.print("{\"message\": \"Forbidden: Invalid session or user ID\"}");
                    return;
                }

                // 1. Save user message
                // Call method within this class directly
                int userMessageId = saveMessage(sessionId, "user", userContent);

                // 2. Removed LLM call logic from here.
                // This servlet now only saves the message, it does NOT interact with the LLM.
                // The LLM interaction (Agent logic) should happen via ChatHandler.

                // 3. Return confirmation that message was saved
                JsonObject responseJson = new JsonObject();
                responseJson.addProperty("message", "User message saved successfully.");
                responseJson.addProperty("message_id", userMessageId);
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
            // Get all sessions list /api/chat/sessions?user_id=...
            if ("/api/chat/sessions".equals(servletPath) && (pathInfo == null || "/".equals(pathInfo))) {
                System.out.println("Debug: Handling /api/chat/sessions (GET) request.");
                String userIdParam = req.getParameter("user_id");
                if (userIdParam == null || userIdParam.isEmpty()) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    out.print("{\"message\": \"Missing user_id parameter\"}");
                    return;
                }
                int userId = Integer.parseInt(userIdParam);
                // Call method within this class directly
                List<Session> sessions = getSessionsByUserId(userId);
                out.print(gson.toJson(sessions));
                resp.setStatus(HttpServletResponse.SC_OK);
            }
            // Get messages for a specific session /api/chat/sessions/{session_id}/messages?user_id=...
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

                    // Call method within this class directly
                    if (!isValidSessionAndUser(sessionId, userId)) {
                        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        out.print("{\"message\": \"Forbidden: Invalid session or user ID for messages\"}");
                        return;
                    }

                    // Call method within this class directly
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

    // --- Below are database access logic directly integrated into the Servlet (originally from SessionService) ---

    // Get database connection
    private Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver"); // Load MySQL driver
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new SQLException("MySQL JDBC Driver not found. Please add mysql-connector-java to your classpath.");
        }
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // --- Data Model Classes (POJO), strictly corresponding to database fields ---
    // These classes will be used to represent database rows in Java code
    public static class Session {
        public String sessionId;
        public int userId;
        public String title;
        public Timestamp createdAt; // Corresponds to created_at in dialog_sessions table
        public Timestamp updatedAt; // Corresponds to updated_at in dialog_sessions table

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

    // --- Session related operations ---

    /**
     * Create a new session and save it to the database.
     * @param userId User ID
     * @param initialTitle Initial title of the session
     * @return The newly created session object, null if failed
     */
    private Session createNewSession(int userId, String initialTitle) {
        String sessionId = UUID.randomUUID().toString(); // Generate unique Session ID
        String sql = "INSERT INTO dialog_sessions (session_id, user_id, title) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setInt(2, userId);
            pstmt.setString(3, initialTitle);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                // Return a complete Session object, including auto-generated created_at and updated_at from the database
                // Here, you might need to query again to get the full timestamps, or assume default behavior
                // For simplification, here we directly return a Session object with current timestamps
                return new Session(sessionId, userId, initialTitle, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis()));
            }
        } catch (SQLException e) {
            System.err.println("Error creating new session: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Get a list of all sessions for a specified user.
     * @param userId User ID
     * @return List of sessions, ordered by updated_at in descending order
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
     * Validate if session ID and user ID match, for permission checking.
     * @param sessionId Session ID
     * @param userId User ID
     * @return True if session exists and belongs to the user; otherwise, false
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

    // --- Message related operations ---

    /**
     * Get all messages for a given session.
     * @param sessionId Session ID
     * @return List of messages, ordered by message_index in ascending order
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
     * Save a message to the database and update the updated_at time of the corresponding session.
     * This is a transactional operation to ensure atomicity of message and session updates.
     * @param sessionId Session ID of the message
     * @param role Message role ('user' or 'assistant')
     * @param content Message content
     * @return ID of the newly inserted message, -1 if failed
     */
    private int saveMessage(String sessionId, String role, String content) {
        int messageId = -1;
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false); // Start transaction to ensure atomicity of operations

            // 1. Get the maximum message_index for the current session, for sorting new messages
            String getMaxIndexSql = "SELECT COALESCE(MAX(message_index), 0) FROM dialog_messages WHERE session_id = ?";
            int nextMessageIndex = 0;
            try (PreparedStatement pstmt = conn.prepareStatement(getMaxIndexSql)) {
                pstmt.setString(1, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        nextMessageIndex = rs.getInt(1) + 1; // Index for the new message
                    }
                }
            }

            // 2. Insert new message into dialog_messages table
            String insertMessageSql = "INSERT INTO dialog_messages (session_id, message_index, role, content) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertMessageSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, sessionId);
                pstmt.setInt(2, nextMessageIndex);
                pstmt.setString(3, role);
                pstmt.setString(4, content);
                pstmt.executeUpdate();

                // Get the auto-generated ID of the newly inserted message
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        messageId = generatedKeys.getInt(1);
                    }
                }
            }

            // 3. Update the updated_at time of the corresponding session, so it appears at the top of the session list
            String updateSessionSql = "UPDATE dialog_sessions SET updated_at = CURRENT_TIMESTAMP WHERE session_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSessionSql)) {
                pstmt.setString(1, sessionId);
                pstmt.executeUpdate();
            }

            conn.commit(); // Commit transaction
        } catch (SQLException e) {
            System.err.println("Error saving message and updating session: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback(); // Rollback transaction on exception
                } catch (SQLException ex) {
                    System.err.println("Error rolling back transaction: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // Restore auto-commit mode
                    conn.close(); // Close connection
                } catch (SQLException e) {
                    System.err.println("Error closing connection: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        return messageId;
    }
}
