import rag.ChatHandler;
import rag.LoginHandler;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import rag.RegisterHandler;

public class ChatServer {
    public static void main(String[] args) throws Exception {
        Server server = new Server(8080);
        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        handler.setContextPath("/");
        server.setHandler(handler);

        // 原有聊天接口
        handler.addServlet(new ServletHolder(new ChatHandler()), "/chat/ask");

        // 注册登录接口
        handler.addServlet(new ServletHolder(new LoginHandler()), "/api/login");

        // 注册注册接口
        handler.addServlet(new ServletHolder(new RegisterHandler()), "/api/register");


        // 静态资源
        handler.addServlet(new ServletHolder("default", new org.eclipse.jetty.servlet.DefaultServlet()), "/");
        handler.setResourceBase("src/main/resources/static");

        server.start();
        System.out.println("Server started at http://localhost:8080");
        server.join();
    }
}
