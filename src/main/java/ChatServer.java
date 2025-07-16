import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.DefaultServlet; // 引入 DefaultServlet，用于处理静态文件
import org.eclipse.jetty.servlets.CrossOriginFilter; // 引入 CrossOriginFilter，用于处理跨域请求
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.servlet.FilterHolder; // 引入 FilterHolder，用于配置过滤器
import rag.*; // 引入您的所有 Servlet 类

import java.io.File;
import java.util.EnumSet; // 引入 EnumSet，用于配置过滤器调度类型

public class ChatServer {
    public static void main(String[] args) throws Exception {
        Server server = new Server(8080);

        // 创建一个 ServletContextHandler，用于管理 Servlet 和过滤器
        // ServletContextHandler.SESSIONS 表示启用会话管理
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/"); // 设置上下文路径为根路径
        server.setHandler(context); // 将上下文处理器设置给服务器

        // --- 配置 CORS 过滤器 (跨域资源共享) ---
        // 这在开发阶段非常重要，允许前端（例如 Vue 开发服务器，通常在 8080 以外的端口）访问后端 API
        FilterHolder corsFilter = new FilterHolder(CrossOriginFilter.class);
        // 允许所有来源访问（开发时方便，生产环境应限制为您的前端域名，例如 "http://localhost:3000"）
        corsFilter.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        // 允许的 HTTP 方法
        corsFilter.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE,OPTIONS");
        // 允许的请求头
        corsFilter.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "Content-Type,Authorization");
        // 允许发送凭据（如 Cookie），如果前端需要携带 Cookie，则设置为 true
        corsFilter.setInitParameter(CrossOriginFilter.ALLOW_CREDENTIALS_PARAM, "true");
        // 将 CORS 过滤器应用于所有请求
        context.addFilter(corsFilter, "/*", EnumSet.of(DispatcherType.REQUEST));

        // --- 配置静态文件服务 ---
        // **重要：请根据你的实际目录结构调整这个路径！**
        // 确保这里的路径指向你的 Vue dist 文件夹（Vue项目编译后的静态文件目录）
        // 示例：
        // your_workspace/
        // ├── llm_tanyawithLee/  (Java 项目根目录)
        // └── vue-frontend/      (Vue 项目根目录)
        //     └── dist/          (Vue 编译后的文件在此)
        String vueDistPath = new File("../vue-frontend/dist").getAbsolutePath();
        // 如果你的 Vue dist 目录在 Java 项目内部，例如 src/main/resources/static
        // String vueDistPath = new File("src/main/resources/static").getAbsolutePath();
        // 你的原始配置是 handler.setResourceBase("src/main/resources/static");
        // 如果你希望继续从 src/main/resources/static 提供服务，请将 vueDistPath 设置为该路径。
        // 但为了部署 Vue 前端，通常需要指向 Vue 的 dist 目录。

        System.out.println("静态文件服务目录: " + vueDistPath);
        context.setResourceBase(vueDistPath); // 设置静态文件根目录

        // 添加 DefaultServlet 来处理所有静态文件请求（包括 index.html, .js, .css 等）
        // 这个 Servlet 应该在其他 API Servlet 之后添加，否则它会拦截所有请求
        context.addServlet(new ServletHolder("default", new DefaultServlet()), "/");

        // --- 注册你的后端 API Servlets ---

        // Agent 聊天接口 (这是 LLM/Agent 逻辑的入口，负责处理用户问题并返回流式回复)
        context.addServlet(new ServletHolder(new ChatHandler()), "/api/chat/ask");
        System.out.println("Agent 聊天接口映射到: /api/chat/ask");

        // 注册登录接口
        context.addServlet(new ServletHolder(new LoginHandler()), "/api/login");
        System.out.println("登录接口映射到: /api/login");

        // 注册注册接口
        context.addServlet(new ServletHolder(new RegisterHandler()), "/api/register");
        System.out.println("注册接口映射到: /api/register");

        //药品管理接口
        context.addServlet(new ServletHolder(new DrugManagementServlet()), "/api/drugs");
        System.out.println("注册接口映射到: /api/drugs");

        // 反馈接口
        context.addServlet(new ServletHolder(new FeedbackServlet()), "/api/feedback");
        System.out.println("注册接口映射到: /api/feedback");


        // ** 修改：注册 ChatSessionServlet **
        // ChatSessionServlet 现在仅用于处理聊天会话的创建、列表查询和消息历史记录的查询。
        // 它不再负责处理用户消息并调用 LLM 获取回复。
        // /api/chat/sessions/* 用于获取会话列表和特定会话的消息
        context.addServlet(new ServletHolder(new ChatSessionServlet()), "/api/chat/sessions/*");
        System.out.println("聊天会话管理接口映射到: /api/chat/sessions/*");
        // 移除了 ChatSessionServlet 对 /api/chat/message 的映射，
        // 因为该接口现在不应再触发 LLM 回复，避免与 ChatHandler 冲突。
        // System.out.println("消息发送接口映射到: /api/chat/message"); // 此行已移除

        server.start(); // 启动服务器
        System.out.println("服务器已启动，访问地址: http://localhost:8080");
        server.join(); // 阻塞主线程直到服务器关闭
    }
}
