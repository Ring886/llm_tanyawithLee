package rag;

import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class ChatHandler extends HttpServlet {
    private RagChat ragChat;

    @Override
    public void init() {
        try {
            InputStream in = getClass().getClassLoader().getResourceAsStream("docs/faq.txt");
            if (in == null) {
                throw new FileNotFoundException("找不到 docs/faq.txt 文件！");
            }
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.toList());

            VectorStore store = new VectorStore();
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    store.addEntry(line);
                }
            }

            this.ragChat = new RagChat(store);
            System.out.println("✅ RAG 初始化完成，知识条目加载数量: " + store.entries.size());
        } catch (Exception e) {
            System.err.println("❌ RAG 初始化失败");
            e.printStackTrace();
            this.ragChat = null;
        }
    }

//    @Override
//    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
//        resp.setContentType("text/plain; charset=UTF-8");
//
//        if (ragChat == null) {
//            resp.getWriter().write("出错：RAG 系统未正确初始化");
//            return;
//        }
//
//        BufferedReader reader = req.getReader();
//        StringBuilder sb = new StringBuilder();
//        String line;
//        while ((line = reader.readLine()) != null) sb.append(line);
//
//        String body = sb.toString();
//        String question = extractQuestion(body);
//
//        if (question == null || question.isEmpty()) {
//            resp.getWriter().write("出错：未提供问题字段");
//            return;
//        }
//
//        try {
//            String reply = ragChat.ask(question);
//            resp.getWriter().write(reply);
//        } catch (Exception e) {
//            e.printStackTrace();
//            resp.getWriter().write("出错：" + e.getMessage());
//        }
//    }


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");

        if (ragChat == null) {
            resp.getWriter().write("出错：RAG 系统未正确初始化");
            return;
        }

        // 读取 JSON 请求体
        BufferedReader reader = req.getReader();
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        String body = sb.toString();
        String question = extractQuestion(body);

        if (question == null || question.isEmpty()) {
            resp.getWriter().write("出错：未提供问题字段");
            return;
        }

        PrintWriter writer = resp.getWriter();

        try {
            // ✅ 调用流式问答方法，边生成边写入响应
            ragChat.streamAsk(question, delta -> {
                writer.write(delta);
                writer.flush(); // 必须flush，前端才能实时收到
            });
        } catch (Exception e) {
            e.printStackTrace();
            writer.write("出错：" + e.getMessage());
        } finally {
            writer.close();
        }
    }




    private String extractQuestion(String json) {
        try {
            int idx = json.indexOf("\"question\"");
            if (idx == -1) return null;
            int start = json.indexOf("\"", idx + 10) + 1;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
