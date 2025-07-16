package rag;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.google.gson.Gson; // 导入 Gson 库
import com.google.gson.GsonBuilder; // 导入 GsonBuilder，用于美化输出

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp; // 导入 Timestamp 类
import java.util.ArrayList;
import java.util.List;

@WebServlet("/api/drugs")
public class DrugManagementServlet extends HttpServlet {

    // 数据库连接信息（请替换为您的实际信息）
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hospital?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String DB_USER = "root"; // 您的数据库用户名
    private static final String DB_PASSWORD = "200402135734"; // 您的数据库密码
    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver";

    // 使用 GsonBuilder 创建 Gson 实例，用于美化 JSON 输出
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // 静态代码块，确保 JDBC 驱动在类加载时只加载一次
    static {
        try {
            Class.forName(JDBC_DRIVER);
            System.out.println("✅ JDBC Driver Loaded: " + JDBC_DRIVER);
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Failed to load JDBC Driver: " + JDBC_DRIVER);
            e.printStackTrace();
            throw new RuntimeException("无法加载数据库驱动，请检查 classpath。", e);
        }
    }

    // 获取数据库连接
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // --- 药品数据模型类 (POJO) ---
    // 这个类用于在 Java 代码中表示 drugs 表中的一行数据
    public static class Drug {
        public int id;
        public String name;
        public String drugCode;
        public String specification;
        public String manufacturer;
        public String indications;
        public String usageAndDosage;
        public String drugType;

        // 构造函数
        public Drug(int id, String name, String drugCode, String specification, String manufacturer,
                    String indications, String usageAndDosage, String drugType) {
            this.id = id;
            this.name = name;
            this.drugCode = drugCode;
            this.specification = specification;
            this.manufacturer = manufacturer;
            this.indications = indications;
            this.usageAndDosage = usageAndDosage;
            this.drugType = drugType;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 设置响应的内容类型为 JSON，并使用 UTF-8 编码
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        // 设置 CORS 头部，允许所有来源访问（开发阶段方便，生产环境请限制为您的前端域名）
        resp.setHeader("Access-Control-Allow-Origin", "*");
        PrintWriter out = resp.getWriter(); // 获取用于发送响应给客户端的写入器

        List<Drug> drugs = new ArrayList<>(); // 用于存储查询到的药品列表
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = getConnection(); // 获取数据库连接
            // SQL 查询语句，选择 drugs 表中的所有字段
            String sql = "SELECT id, name, drug_code, specification, manufacturer, indications, usage_and_dosage, drug_type FROM drugs ORDER BY name ASC";
            pstmt = conn.prepareStatement(sql); // 准备 SQL 语句
            rs = pstmt.executeQuery(); // 执行查询并获取结果集

            while (rs.next()) { // 遍历结果集中的每一行
                // 从结果集中获取数据，并创建 Drug 对象
                Drug drug = new Drug(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("drug_code"),
                        rs.getString("specification"),
                        rs.getString("manufacturer"),
                        rs.getString("indications"),
                        rs.getString("usage_and_dosage"),
                        rs.getString("drug_type")
                );
                drugs.add(drug); // 将 Drug 对象添加到列表中
            }

            // 将药品列表转换为 JSON 格式并发送回客户端
            out.print(gson.toJson(drugs));
            resp.setStatus(HttpServletResponse.SC_OK); // 设置 HTTP 状态码为 200 OK

        } catch (SQLException e) {
            // 捕获 SQL 异常，打印错误日志，并向客户端返回 500 内部服务器错误
            System.err.println("获取药品信息出错: " + e.getMessage());
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"message\": \"内部服务器错误: " + e.getMessage() + "\"}");
        } finally {
            // 确保所有数据库资源都被关闭，避免资源泄露
            try {
                if (rs != null) rs.close();
                if (pstmt != null) pstmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                System.err.println("关闭数据库资源出错: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
