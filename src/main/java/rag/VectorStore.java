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
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?allowPublicKeyRetrieval=true&useSSL=false";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "root";
    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver"; // MySQL 8.0+

    public static class Entry {
        public final String text;
        public final List<Float> vector;
        public Entry(String text, List<Float> vector) {
            this.text = text;
            this.vector = vector;
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    public void addEntry(String text) {
        try {
            List<Float> vec = EmbeddingUtil.embed(text);
            entries.add(new Entry(text, vec));
        } catch (IOException e) {
            System.err.println("向量生成失败，文本跳过：" + text);
            e.printStackTrace();
        }
    }

    // 辅助方法：执行SQL查询并处理结果
    private int loadDataFromTable(String tableName, String sql, String... columnsToConcatenate) throws SQLException, IOException, ClassNotFoundException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        int count = 0;

        try {
            Class.forName(JDBC_DRIVER);
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();

            while (rs.next()) {
                StringBuilder textBuilder = new StringBuilder();
                for (String col : columnsToConcatenate) {
                    // 检查列是否存在，防止ResultSet.getString()抛出异常
                    try {
                        String value = rs.getString(col);
                        if (value != null && !value.trim().isEmpty()) {
                            textBuilder.append(value).append(" "); // 使用空格分隔不同列的内容
                        }
                    } catch (SQLException e) {
                        // 列不存在，跳过或记录警告
                        System.err.println("警告: 表 " + tableName + " 中不存在列 " + col);
                    }
                }
                String fullText = textBuilder.toString().trim();

                if (!fullText.isEmpty()) {
                    addEntry(fullText);
                    count++;
                }
            }
            System.out.println("✅ 从表 " + tableName + " 加载知识条目完成，共加载 " + count + " 条。");
        } catch (SQLException e) {
            System.err.println("❌ 从表 " + tableName + " 加载知识条目失败！");
            throw e;
        } finally {
            try {
                if (rs != null) rs.close();
                if (pstmt != null) pstmt.close();
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
        return count;
    }

    // 1. 加载 medical_articles 表
    public int loadMedicalArticles() throws SQLException, IOException, ClassNotFoundException {
        String sql = "SELECT title, content FROM medical_articles";
        return loadDataFromTable("medical_articles", sql, "title", "content");
    }

    // 2. 加载 doctors 表
    public int loadDoctors() throws SQLException, IOException, ClassNotFoundException {
        // 提取医生姓名、科室、职称、专长作为知识
        String sql = "SELECT name, department, title, specialty FROM doctors";
        return loadDataFromTable("doctors", sql, "name", "department", "title", "specialty");
    }

    // 3. 加载 drugs 表
    public int loadDrugs() throws SQLException, IOException, ClassNotFoundException {
        // 提取药品名称、编码、规格、厂家、适用症、用法用量作为知识
        String sql = "SELECT name, drug_code, specification, manufacturer, indications, usage_and_dosage, drug_type FROM drugs";
        return loadDataFromTable("drugs", sql, "name", "drug_code", "specification", "manufacturer", "indications", "usage_and_dosage", "drug_type");
    }

    // 4. 加载 hospital_departments 表
    public int loadHospitalDepartments() throws SQLException, IOException, ClassNotFoundException {
        // 提取科室名称、简介、位置、联系方式作为知识
        String sql = "SELECT name, description, location, contact_phone FROM hospital_departments";
        return loadDataFromTable("hospital_departments", sql, "name", "description", "location", "contact_info");
    }

    // 5. 加载 doctor_schedules 表
    // 注意：排班信息通常是动态的，并且可能包含大量重复的时间点信息。
    // 对于RAG来说，通常更关注“某个医生是否有排班”或“某个科室的排班规律”，
    // 而不是具体的某个时间点。这里我们尝试提取医生姓名和排班日期/时间段。
    // 实际应用中，你可能需要更复杂的逻辑来汇总排班信息，避免生成过多细碎的向量。
    public int loadDoctorSchedules() throws SQLException, IOException, ClassNotFoundException {
        // 假设 doctor_schedules 表有 doctor_id, schedule_date, start_time, end_time
        // 并且医生姓名可以从 doctors 表通过 doctor_id 关联获取
        // 这里为了简化，假设 doctor_schedules 表直接有 doctor_name 或者我们只提取日期。
        // 如果需要关联 doctors 表，SQL会更复杂，例如 JOIN 操作。
        // 假设 doctor_schedules 表有 doctor_name 字段或者我们只关注日期
        String sql = "SELECT ds.id, d.name AS doctor_name, ds.schedule_date, ds.location, ds.start_time, ds.end_time, ds.available_slots, ds.booked_slots " + // 包含 booked_slots
                "FROM doctor_schedules ds " +
                "JOIN doctors d ON ds.doctor_id = d.id " +
                "WHERE ds.schedule_date >= CURDATE()"; // 建议只加载今天或未来的排班

        return loadDataFromTable("doctor_schedules", sql,
                "doctor_name", "schedule_date", "location", "start_time", "end_time", "available_slots", "booked_slots"); // 包含 booked_slots
    }


    public String retrieveRelevant(String question) {
        List<Float> questionVec;
        try {
            questionVec = EmbeddingUtil.embed(question);
        } catch (IOException e) {
            System.err.println("问题向量生成失败: " + question);
            e.printStackTrace();
            return "抱歉，向量生成失败，无法回答问题。";
        }

        double maxSim = -1;
        String bestMatch = "未能找到相关内容";

        for (Entry entry : entries) {
            double sim = EmbeddingUtil.cosineSimilarity(entry.vector, questionVec);
            if (sim > maxSim) {
                maxSim = sim;
                bestMatch = entry.text; // 返回最匹配的原始文本
            }
        }
        return bestMatch;
    }
}