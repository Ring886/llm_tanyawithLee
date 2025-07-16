package rag;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.sql.Timestamp;

@WebServlet("/api/feedback")
public class FeedbackServlet extends HttpServlet {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "200402135734";
    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    static {
        try {
            Class.forName(JDBC_DRIVER);
            System.out.println("✅ JDBC Driver Loaded: " + JDBC_DRIVER);
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Failed to load JDBC Driver: " + JDBC_DRIVER);
            throw new RuntimeException(e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // 定义一个内部类表示 JSON 数据结构
    public static class Feedback {
        public int user_id;
        public String content;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        PrintWriter out = resp.getWriter();

        try {
            // 读取 JSON 请求体
            StringBuilder jsonBuilder = new StringBuilder();
            BufferedReader reader = req.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            String json = jsonBuilder.toString();
            Feedback feedback = gson.fromJson(json, Feedback.class);

            if (feedback == null || feedback.content == null || feedback.content.trim().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print("{\"message\": \"无效的请求内容\"}");
                return;
            }

            Timestamp now = new Timestamp(System.currentTimeMillis());

            try (
                    Connection conn = getConnection();
                    PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO feedback (user_id, content, time) VALUES (?, ?, ?)"
                    )
            ) {
                stmt.setInt(1, feedback.user_id);
                stmt.setString(2, feedback.content);
                stmt.setTimestamp(3, now);

                int rows = stmt.executeUpdate();

                if (rows > 0) {
                    resp.setStatus(HttpServletResponse.SC_OK);
                    out.print("{\"message\": \"反馈提交成功\"}");
                } else {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    out.print("{\"message\": \"反馈提交失败\"}");
                }
            }

        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"message\": \"服务器错误: " + e.getMessage() + "\"}");
            e.printStackTrace();
        }
    }
}
