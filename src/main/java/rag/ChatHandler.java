package rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class ChatHandler extends HttpServlet {
    private RagChat ragChat;
    private ObjectMapper objectMapper;

    @Override
    public void init() {
        this.objectMapper = new ObjectMapper();
        try {
            VectorStore store = new VectorStore();

            // ✅ 加载数据库知识库内容
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
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        if (ragChat == null) {
            resp.getWriter().write("出错：RAG 系统未正确初始化");
            return;
        }

        // 读取 JSON 请求体
        String body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", (acc, line) -> acc + line);

        String question = extractQuestion(body);

        if (question == null || question.isEmpty()) {
            resp.getWriter().write("出错：未提供问题字段");
            return;
        }

        PrintWriter writer = resp.getWriter();

        try {
            // ✅ 调用流式回答方法
            ragChat.streamAsk(question, delta -> {
                writer.write(delta);
                writer.flush(); // 实时刷新
            });
        } catch (Exception e) {
            e.printStackTrace();
            writer.write("出错：" + e.getMessage());
        } finally {
            writer.close();
        }
    }

    // ✅ 使用 Jackson 提取 JSON 字段，更安全可靠
    private String extractQuestion(String json) {
        try {
            JsonNode rootNode = objectMapper.readTree(json);
            if (rootNode.has("question")) {
                return rootNode.get("question").asText();
            }
            return null;
        } catch (Exception e) {
            System.err.println("解析JSON失败: " + e.getMessage());
            return null;
        }
    }
}
