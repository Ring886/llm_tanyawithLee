// src/main/java/rag/ChatServer.java

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.DefaultServlet; // 引入 DefaultServlet
import org.eclipse.jetty.servlets.CrossOriginFilter; // 引入 CrossOriginFilter
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.servlet.FilterHolder; // 引入 FilterHolder
import rag.*;

import java.io.File; // 引入 File 类
import java.util.EnumSet;

public class ChatServer {
    public static void main(String[] args) throws Exception {
        Server server = new Server(8080);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS); // 将 handler 改名为 context，更符合Jetty习惯
        context.setContextPath("/");
        server.setHandler(context);

        // --- 配置 CORS 过滤器 (重要，用于开发阶段) ---
        // 允许前端（如 Vue 开发服务器，通常在8080以外的端口）访问后端API
        FilterHolder corsFilter = new FilterHolder(CrossOriginFilter.class);
        corsFilter.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*"); // 允许所有来源（开发时方便，生产环境应限制为前端域名）
        corsFilter.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE,OPTIONS"); // 允许的HTTP方法
        corsFilter.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "Content-Type,Authorization"); // 允许的请求头
        corsFilter.setInitParameter(CrossOriginFilter.ALLOW_CREDENTIALS_PARAM, "true"); // 允许发送凭据（如Cookie）
        context.addFilter(corsFilter, "/*", EnumSet.of(DispatcherType.REQUEST)); // 将CORS过滤器应用于所有请求

        // --- 配置静态文件服务 ---
        // **重要：请根据你的实际目录结构调整这个路径！**
        // 确保这里的路径指向你的 Vue dist 文件夹（Vue项目编译后的静态文件目录）
        // 假设 Vue 项目目录名为 'vue-frontend'，并且它和 Java 项目在同一个父目录下
        // 例如：
        // your_workspace/
        // ├── llm_tanyawithLee/  (Java项目根目录)
        // └── vue-frontend/      (Vue项目根目录)
        //     └── dist/          (Vue编译后的文件在此)
        String vueDistPath = new File("../vue-frontend/dist").getAbsolutePath();
        // 如果你的 Vue dist 目录在 Java 项目内部，例如 src/main/resources/static
        // String vueDistPath = new File("src/main/resources/static").getAbsolutePath();
        // 你的原始配置是 handler.setResourceBase("src/main/resources/static");
        // 如果你希望继续从 src/main/resources/static 提供服务，请将 vueDistPath 设置为该路径。
        // 但为了部署 Vue 前端，通常需要指向 Vue 的 dist 目录。

        System.out.println("Serving static files from: " + vueDistPath);
        context.setResourceBase(vueDistPath); // 设置静态文件根目录

        // 添加 DefaultServlet 来处理所有静态文件请求（包括 index.html, .js, .css 等）
        // 这个 Servlet 应该在其他 API Servlet 之后添加，否则它会拦截所有请求
        context.addServlet(new ServletHolder("default", new DefaultServlet()), "/");

        // --- 注册你原有的后端 API Servlets ---
        // 原有聊天接口
        context.addServlet(new ServletHolder(new ChatHandler()), "/api/chat/ask");
        System.out.println("现有聊天接口映射到: /api/chat/ask");

        // 注册登录接口
        context.addServlet(new ServletHolder(new LoginHandler()), "/api/login");
        System.out.println("登录接口映射到: /api/login");

        // 注册注册接口
        context.addServlet(new ServletHolder(new RegisterHandler()), "/api/register");
        System.out.println("注册接口映射到: /api/register");

        // ** 新增：注册 ChatSessionServlet **
        // 用于处理新的聊天会话和消息相关的 API 请求
        // /api/chat/sessions/* 用于获取会话列表和特定会话的消息
        // /api/chat/message 用于发送新消息
        context.addServlet(new ServletHolder(new ChatSessionServlet()), "/api/chat/sessions/*");
        context.addServlet(new ServletHolder(new ChatSessionServlet()), "/api/chat/message");
        System.out.println("聊天会话接口映射到: /api/chat/sessions/*");
        System.out.println("消息发送接口映射到: /api/chat/message");

        server.start(); // 启动服务器
        System.out.println("Server started at http://localhost:8080");
        server.join(); // 阻塞主线程直到服务器关闭
    }
}