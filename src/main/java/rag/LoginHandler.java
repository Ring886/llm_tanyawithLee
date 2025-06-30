package rag;

import jakarta.servlet.http.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class LoginHandler extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 解析请求体中的JSON
        String body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", (acc, line) -> acc + line);

        // 简单提取手机号和密码
        String phone = extractJsonField(body, "phone");
        String code = extractJsonField(body, "code");

        // 模拟校验：正确手机号和密码是"123"
        boolean isValid = "123".equals(phone) && "123".equals(code);

        if(isValid) System.out.println("成功了！");

        // 设置响应头
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        // 返回结果
        String result = "{\"isValid\": " + isValid + "}";
        resp.getWriter().write(result);
    }

    // 简单的JSON字段提取方法（不推荐用于复杂JSON）
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
