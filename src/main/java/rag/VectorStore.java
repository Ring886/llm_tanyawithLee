// src/main/java/rag/VectorStore.java
package rag;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class VectorStore {

    // 定义数据库连接常量，这些应该从配置文件读取，这里为了简化直接硬编码
    // 确保你的数据库名是 'hospital' 或者你实际使用的数据库名
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "root";
    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver"; // MySQL 8.0+

    // 嵌入模型输入长度限制的阈值 (假设值，请根据您实际使用的模型调整)
    // 例如：如果您的模型以 token 计数，需要将字符数转换为 approximate token 数。
    // 千问 text-embedding-v2 通常最大 token 数是 2048，这里以字符粗略估计
    private static final int MAX_EMBEDDING_CHUNK_LENGTH = 500; // 500个字符作为一个块

    public static class Entry {
        public final String text;
        public final List<Float> vector;

        public Entry(String text, List<Float> vector) {
            this.text = text;
            this.vector = vector;
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    /**
     * 将文本向量化并添加到知识库。如果文本过长，将进行分块处理。
     * @param text 需要向量化的文本
     */
    public void addEntry(String text) {
        if (text == null || text.trim().isEmpty()) {
            return; // 忽略空文本
        }
        text = text.trim();

        if (text.length() > MAX_EMBEDDING_CHUNK_LENGTH) {
            // 对长文本进行分块处理。这里使用简单的按字符长度分块，并带有小量重叠，以保留上下文。
            // 实际应用中，更高级的分块策略（如基于句子、段落，并保持语义完整性）效果更好。
            int overlap = 50; // 分块重叠的字符数
            for (int i = 0; i < text.length(); i += (MAX_EMBEDDING_CHUNK_LENGTH - overlap)) {
                int endIndex = Math.min(i + MAX_EMBEDDING_CHUNK_LENGTH, text.length());
                String chunk = text.substring(i, endIndex);

                // 如果是最后一个块，确保包含了文本的剩余部分
                if (endIndex == text.length() && i + MAX_EMBEDDING_CHUNK_LENGTH - overlap > text.length()) {
                    chunk = text.substring(i);
                }

                chunk = chunk.trim();
                if (!chunk.isEmpty()) {
                    try {
                        List<Float> vec = EmbeddingUtil.embed(chunk);
                        entries.add(new Entry(chunk, vec));
                    } catch (IOException e) {
                        System.err.println("❌ 向量生成失败，文本片段跳过（" + (chunk.length() > 50 ? chunk.substring(0, 50) + "..." : chunk) + "）: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } else {
            // 短文本直接向量化
            try {
                List<Float> vec = EmbeddingUtil.embed(text);
                entries.add(new Entry(text, vec));
            } catch (IOException e) {
                System.err.println("❌ 向量生成失败，文本跳过（" + (text.length() > 50 ? text.substring(0, 50) + "..." : text) + "）: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // 辅助方法：执行SQL查询并处理结果，将指定列的内容拼接成字符串进行向量化
    private int loadDataFromTable(String tableName, String sql, String... columnsToConcatenate) throws SQLException, IOException, ClassNotFoundException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        int count = 0;

        try {
            Class.forName(JDBC_DRIVER); // 加载JDBC驱动
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();

            while (rs.next()) {
                StringBuilder textBuilder = new StringBuilder();
                for (String col : columnsToConcatenate) {
                    try {
                        // 尝试获取字符串，如果字段不存在或为null，getString()会返回null或抛异常
                        String value = rs.getString(col);
                        if (value != null && !value.trim().isEmpty()) {
                            textBuilder.append(value.trim()).append(" "); // 使用空格分隔，并去除单个值的首尾空格
                        }
                    } catch (SQLException e) {
                        // 列不存在或数据类型不匹配，记录警告但继续处理其他列
                        System.err.println("警告: 表 " + tableName + " 中获取列 '" + col + "' 失败或不存在: " + e.getMessage());
                    }
                }
                String fullText = textBuilder.toString().trim(); // 最终去除拼接后字符串的首尾空格

                if (!fullText.isEmpty()) {
                    addEntry(fullText); // 调用分块处理的 addEntry 方法
                    count++;
                }
            }
            System.out.println("✅ 从表 " + tableName + " 加载知识条目完成，共加载 " + count + " 条。");
        } catch (SQLException e) {
            System.err.println("❌ 从表 " + tableName + " 加载知识条目失败！" + e.getMessage());
            throw e; // 向上抛出异常，让调用者知道加载失败
        } finally {
            // 确保资源关闭
            try {
                if (rs != null) rs.close();
                if (pstmt != null) pstmt.close();
                if (conn != null) conn.close();
            } catch (SQLException se) {
                System.err.println("❌ 关闭数据库资源失败: " + se.getMessage());
                se.printStackTrace();
            }
        }
        return count;
    }


    // 2. 加载 doctors 表
    public int loadDoctors() throws SQLException, IOException, ClassNotFoundException {
        // 提取医生姓名、科室、职称、专长作为知识
        String sql = "SELECT name, department, title, specialty FROM doctors";
        return loadDataFromTable("doctors", sql, "name", "department", "title", "specialty");
    }

    // 3. 加载 drugs 表
    public int loadDrugs() throws SQLException, IOException, ClassNotFoundException {
        // 提取药品名称、编码、规格、厂家、适用症、用法用量、药品类型作为知识
        String sql = "SELECT name, drug_code, specification, manufacturer, indications, usage_and_dosage, drug_type FROM drugs";
        return loadDataFromTable("drugs", sql, "name", "drug_code", "specification", "manufacturer", "indications", "usage_and_dosage", "drug_type");
    }

    // 4. 加载 hospital_departments 表
    public int loadHospitalDepartments() throws SQLException, IOException, ClassNotFoundException {
        // 提取科室名称、简介、位置、联系方式作为知识
        String sql = "SELECT name, description, location, contact_phone FROM hospital_departments";
        // 确保数据库字段名是 contact_phone
        return loadDataFromTable("hospital_departments", sql, "name", "description", "location", "contact_phone");
    }

    // 5. 加载 doctor_schedules 表 (根据最新的表结构进行修改)
    public int loadDoctorSchedules() throws SQLException, IOException, ClassNotFoundException {
        // SQL 查询语句中现在使用 start_time 和 end_time 字段
        // 拼接时可以更具可读性，例如 "医生[doctor_name]在[schedule_date]从[start_time]到[end_time]时段在[location]出诊，有[available_slots]个号可挂，已挂号[booked_slots]个。"
        String sql = "SELECT d.name AS doctor_name, ds.schedule_date, ds.start_time, ds.end_time, ds.location, ds.available_slots, ds.booked_slots " +
                "FROM doctor_schedules ds " +
                "JOIN doctors d ON ds.doctor_id = d.id " +
                "WHERE ds.schedule_date >= CURDATE()"; // 建议只加载今天或未来的排班

        // loadDataFromTable 会将这些列的值拼接起来
        return loadDataFromTable("doctor_schedules", sql,
                "doctor_name", "schedule_date", "start_time", "end_time", "location", "available_slots", "booked_slots");
    }


    /**
     * 根据用户问题检索最相关的知识。
     * @param question 用户问题
     * @return 最相关的知识文本，如果没有找到则返回默认提示
     */
    public String retrieveRelevant(String question) {
        if (question == null || question.trim().isEmpty()) {
            return "请提供一个问题以便检索。";
        }

        List<Float> questionVec;
        try {
            questionVec = EmbeddingUtil.embed(question);
        } catch (IOException e) {
            System.err.println("❌ 问题向量生成失败: " + question + " - " + e.getMessage());
            e.printStackTrace();
            return "抱歉，由于内部错误，无法理解您的问题以进行知识检索。";
        }

        double maxSim = -1.0;
        String bestMatch = "未能找到相关内容。"; // 默认回复
        final double SIMILARITY_THRESHOLD = 0.75; // 相似度阈值，低于此值认为不相关

        // 如果entries为空，说明知识库没有加载成功或没有数据
        if (entries.isEmpty()) {
            return "知识库为空，无法检索到任何信息。";
        }

        for (Entry entry : entries) {
            // 确保向量不为空且维度匹配
            if (entry.vector != null && !entry.vector.isEmpty() && questionVec.size() == entry.vector.size()) {
                double sim = EmbeddingUtil.cosineSimilarity(entry.vector, questionVec);
                if (sim > maxSim) {
                    maxSim = sim;
                    bestMatch = entry.text; // 返回最匹配的原始文本
                }
            } else {
                System.err.println("警告: 知识库中发现无效或维度不匹配的向量条目，已跳过。文本片段: " + (entry.text != null && entry.text.length() > 50 ? entry.text.substring(0, 50) + "..." : entry.text));
            }
        }

        // 根据相似度阈值判断是否返回检索结果
        if (maxSim >= SIMILARITY_THRESHOLD) {
            System.out.println("✅ 找到高度相关知识（相似度: " + String.format("%.2f", maxSim * 100) + "%）：" + bestMatch);
            return bestMatch;
        } else {
            System.out.println("⚠️ 未找到高度相关知识（最高相似度: " + String.format("%.2f", maxSim * 100) + "%）。");
            return "未能找到高度相关内容，请尝试更具体的问题。"; // 可以返回更友好的提示
        }
    }
}