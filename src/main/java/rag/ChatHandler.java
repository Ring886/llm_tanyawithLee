// src/main/java/rag/ChatHandler.java
package rag;

import jakarta.servlet.http.*;
import jakarta.servlet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ChatHandler extends HttpServlet {
    private RagChat ragChat;
    private ObjectMapper objectMapper;

    @Override
    public void init() {
        this.objectMapper = new ObjectMapper();
        try {
            VectorStore store = new VectorStore();

            // *** 调用所有数据库表的加载方法 ***
            store.loadMedicalArticles();
            store.loadDoctors();
            store.loadDrugs();
            store.loadHospitalDepartments();
            store.loadDoctorSchedules(); // 加载排班信息

            this.ragChat = new RagChat(store);
            System.out.println("✅ RAG 系统初始化完成，所有知识条目加载成功。总条目数: " + store.entries.size());
        } catch (Exception e) {
            System.err.println("❌ RAG 系统初始化失败");
            e.printStackTrace();
            this.ragChat = null;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=UTF-8");

        if (ragChat == null) {
            resp.getWriter().write("出错：RAG 系统未正确初始化");
            return;
        }

        BufferedReader reader = req.getReader();
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        String body = sb.toString();
        String question = extractQuestion(body);

        if (question == null || question == null || question.isEmpty()) { // 修复了重复的 null 检查
            resp.getWriter().write("出错：未提供问题字段");
            return;
        }

        try {
            String reply = ragChat.ask(question);
            resp.getWriter().write(reply);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("出错：" + e.getMessage());
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
            System.err.println("解析JSON请求体失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}