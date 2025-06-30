package rag;

import jakarta.servlet.http.*;
import jakarta.servlet.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class RegisterHandler extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 读取请求体中的 JSON 数据
        String body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", (acc, line) -> acc + line);

        // 简单提取手机号和验证码字段（其实这里不会做校验，只是模拟一下）
        String phone = extractJsonField(body, "phone");
        String code = extractJsonField(body, "code");

        System.out.println("收到注册请求: phone = " + phone + ", code = " + code);

        // 设置响应头
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        // 模拟永远注册成功
        String result = "{\"success\": true}";
        resp.getWriter().write(result);
    }

    // 简单的 JSON 字段提取方法（与 LoginHandler 相同）
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
