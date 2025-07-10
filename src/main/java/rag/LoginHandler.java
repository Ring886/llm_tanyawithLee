package rag;

import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.regex.*;

public class LoginHandler extends HttpServlet {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";       // 替换成你的用户名
    private static final String DB_PASSWORD = "root"; // 替换成你的密码

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

        boolean isValid = checkCredentials(phone, code);

        String result = "{\"isValid\": " + isValid + "}";
        resp.getWriter().write(result);
    }

    private boolean checkCredentials(String phone, String password) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                String sql = "SELECT * FROM users WHERE phone = ? AND password = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, phone);
                    stmt.setString(2, password);
                    ResultSet rs = stmt.executeQuery();
                    return rs.next(); // 如果查询到记录，说明验证成功
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        Matcher matcher = Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
