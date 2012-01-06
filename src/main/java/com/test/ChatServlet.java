package com.test;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = "/chat", asyncSupported = true)
public class ChatServlet extends HttpServlet {

    private static final long serialVersionUID = -277914015930424042L;

    private final Map<String, AsyncContext> asyncContexts = new ConcurrentHashMap<String, AsyncContext>();
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();
    private final Thread notifier = new Thread(new Runnable() {
        @Override
        public void run() {
            while (true) {
                try {
                    // Waits until a message arrives
                    String message = messages.take();

                    // Sends the message to all the AsyncContext's response
                    for (AsyncContext asyncContext : asyncContexts.values()) {
                        try {
                            sendMessage(asyncContext.getResponse().getWriter(), message);
                        } catch (Exception e) {
                            asyncContexts.values().remove(asyncContext);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    });

    private void sendMessage(PrintWriter writer, String message) throws IOException {
        // default message format is message-size ; message-data ;
        writer.print(message.length());
        writer.print(";");
        writer.print(message);
        writer.print(";");
        writer.flush();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        notifier.start();
    }

    // GET method is used to establish a stream connection
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // Content-Type header
        response.setContentType("text/plain");
        response.setCharacterEncoding("utf-8");

        // Access-Control-Allow-Origin header
        response.setHeader("Access-Control-Allow-Origin", "*");

        PrintWriter writer = response.getWriter();

        // Id
        final String id = UUID.randomUUID().toString();
        writer.print(id);
        writer.print(';');

        // Padding
        for (int i = 0; i < 1024; i++) {
            writer.print(' ');
        }
        writer.print(';');
        writer.flush();

        final AsyncContext ac = request.startAsync();
        ac.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                asyncContexts.remove(id);
            }

            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                asyncContexts.remove(id);
            }

            @Override
            public void onError(AsyncEvent event) throws IOException {
                asyncContexts.remove(id);
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {

            }
        });
        asyncContexts.put(id, ac);
    }

    // POST method is used to communicate with the server
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("utf-8");

        AsyncContext ac = asyncContexts.get(request.getParameter("metadata.id"));
        if (ac == null) {
            return;
        }

        // close-request
        if ("close".equals(request.getParameter("metadata.type"))) {
            ac.complete();
            return;
        }

        // send-request
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("username", request.getParameter("username"));
        data.put("message", request.getParameter("message"));

        try {
            messages.put(new Gson().toJson(data));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void destroy() {
        messages.clear();
        asyncContexts.clear();
        notifier.interrupt();
    }

}