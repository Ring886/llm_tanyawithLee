package rag;

import jakarta.servlet.http.*;
import jakarta.servlet.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;


public class RegisterHandler extends HttpServlet {
    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/hospital?useSSL=false&serverTimezone=UTC";
    private static final String JDBC_USER = "root";
    private static final String JDBC_PASSWORD = "root";

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 读取请求体中的 JSON 数据
        String body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", (acc, line) -> acc + line);

        // 简单提取手机号和验证码字段（其实这里不会做校验，只是模拟一下）
        String phone = extractJsonField(body, "phone");
        String password = extractJsonField(body, "password");

        System.out.println("收到注册请求: phone = " + phone + ", password = " + password);

        /*数据库写入*/
        boolean success = false;

        try {
            // 加载MySQL驱动（部分环境可以省略）
            Class.forName("com.mysql.cj.jdbc.Driver");

            // 建立连接
            try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
                String sql = "INSERT INTO users (phone, password) VALUES (?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, phone);
                    stmt.setString(2, password);
                    int rows = stmt.executeUpdate();
                    success = rows > 0;
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
        }


        // 设置响应头
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        // 模拟注册
        String result = "{\"success\": " + success + "}";
        resp.getWriter().write(result);
    }

    // 简单的 JSON 字段提取方法（与 LoginHandler 相同）
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
