//package rag;
//
//import java.io.*;
//import java.net.*;
//import java.util.*;
//import com.google.gson.*;
//
//public class EmbeddingUtil {
//
//    private static final String API_KEY = "sk-QBzcPkljgrbrHLDj0O70TOyYmpdMVF8983FE34CEE11F08BCA8E07EACA3CDE";
//    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/embedding/text-embedding-v1";
//
//    public static List<Float> embed(String text) throws IOException {
//        URL url = new URL(API_URL);
//        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
//
//        conn.setRequestMethod("POST");
//        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
//        conn.setRequestProperty("Content-Type", "application/json");
//        conn.setDoOutput(true);
//
//        // 构造 JSON 请求体
//        JsonObject payload = new JsonObject();
//        payload.addProperty("model", "text-embedding-v1");
//        JsonArray inputArray = new JsonArray();
//        inputArray.add(text);
//        payload.add("input", inputArray);
//
//        try (OutputStream os = conn.getOutputStream()) {
//            byte[] input = payload.toString().getBytes("utf-8");
//            os.write(input, 0, input.length);
//        }
//
//        int status = conn.getResponseCode();
//        if (status != 200) {
//            throw new IOException("向量请求失败，状态码：" + status);
//        }
//
//        try (BufferedReader br = new BufferedReader(
//                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
//            StringBuilder response = new StringBuilder();
//            String responseLine;
//            while ((responseLine = br.readLine()) != null) {
//                response.append(responseLine.trim());
//            }
//
//            // 解析 JSON 响应
//            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
//            JsonArray embeddingArray = json.getAsJsonObject("output")
//                    .getAsJsonArray("embeddings")
//                    .get(0).getAsJsonObject()
//                    .getAsJsonArray("embedding");
//
//            List<Float> embedding = new ArrayList<>();
//            for (JsonElement elem : embeddingArray) {
//                embedding.add(elem.getAsFloat());
//            }
//            return embedding;
//        }
//    }
//
//    public static double cosineSimilarity(List<Float> a, List<Float> b) {
//        double dot = 0.0, normA = 0.0, normB = 0.0;
//        for (int i = 0; i < a.size(); i++) {
//            dot += a.get(i) * b.get(i);
//            normA += a.get(i) * a.get(i);
//            normB += b.get(i) * b.get(i);
//        }
//        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
//    }
//}


package rag;

import java.io.*;
import java.net.*;
import java.util.*;
import com.google.gson.*;


// 用法摘自https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api#8493578287w6l
// 这是最关键的，项目成功最大难点就在于这里，还得是官方文档
public class EmbeddingUtil {

    private static final String API_KEY = "sk-937378dfe8f04fc0925976e87638038f";
    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/services/embedding/text-embedding-v2";

    public static List<Float> embed(String text) throws IOException {
        String API_KEY = "sk-937378dfe8f04fc0925976e87638038f";
        String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";

        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "text-embedding-v4"); // 或 text-embedding-v3
        payload.addProperty("input", text); // ✅ 注意是 input，而不是 texts/textsArray

        try (OutputStream os = conn.getOutputStream()) {
            byte[] inputBytes = payload.toString().getBytes("utf-8");
            os.write(inputBytes, 0, inputBytes.length);
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line.trim());
                }
                System.err.println("向量请求失败，状态码：" + status + "，错误内容：" + errorResponse);
            }
            throw new IOException("向量请求失败，状态码：" + status);
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }

            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
            JsonArray embeddingArray = json.getAsJsonArray("data")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");

            List<Float> embedding = new ArrayList<>();
            for (JsonElement elem : embeddingArray) {
                embedding.add(elem.getAsFloat());
            }
            return embedding;
        }
    }


    public static double cosineSimilarity(List<Float> a, List<Float> b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
