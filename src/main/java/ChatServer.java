import rag.ChatHandler;


import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class ChatServer {
    public static void main(String[] args) throws Exception {
        Server server = new Server(8080);
        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        handler.setContextPath("/");
        server.setHandler(handler);

        handler.addServlet(new ServletHolder(new ChatHandler()), "/chat/ask");

        // 提供静态HTML
        handler.addServlet(new ServletHolder("default", new org.eclipse.jetty.servlet.DefaultServlet()), "/");
        handler.setResourceBase("src/main/resources/static");

        server.start();
        System.out.println("Server started at http://localhost:8080");
        server.join();
    }
}
