package rag;

import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.regex.*;

public class LoginHandler extends HttpServlet {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "root";

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setContentType("application/json;charset=utf-8");

        String body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", (acc, line) -> acc + line);

        String phone = extractJsonField(body, "phone");
        String code = extractJsonField(body, "code");

        int userId = getUserId(phone, code); // ✅ 替代原来的布尔值判断

        String result;
        if (userId != -1) {
            result = "{\"isValid\": true, \"user_id\": " + userId + "}";
        } else {
            result = "{\"isValid\": false}";
        }

        resp.getWriter().write(result);
    }

    // ✅ 新增：验证并返回 user_id，失败返回 -1（代替原 checkCredentials）
    private int getUserId(String phone, String password) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                String sql = "SELECT id FROM users WHERE phone = ? AND password = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, phone);
                    stmt.setString(2, password);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return rs.getInt("id");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1; // 登录失败
    }

    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        Matcher matcher = Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}