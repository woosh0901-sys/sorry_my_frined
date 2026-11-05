package server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** 소켓 입출력만 담당하고, 받은 메시지의 의미는 Listener에 위임합니다. */
final class ClientSession implements Runnable, AutoCloseable {
    interface Listener {
        void received(ClientSession client, String message);
        void disconnected(ClientSession client);
    }

    private final Socket socket;
    private final Listener listener;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(128);
    private final Thread sender;

    ClientSession(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000); // 접속 후 10초 안에 JOIN 필요
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        sender = new Thread(this::writeMessages, "client-send");
        sender.setDaemon(true);
        sender.start();
    }

    void joined() throws IOException { socket.setSoTimeout(0); }

    void send(String message) {
        if (!closed.get() && !outgoing.offer(message)) close();
    }

    private void writeMessages() {
        try {
            while (!closed.get()) {
                output.write(outgoing.take());
                output.newLine();
                output.flush();
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (IOException ex) { close(); }
    }

    @Override public void run() {
        try {
            String line;
            while (!closed.get() && (line = readLine()) != null) listener.received(this, line);
        } catch (IOException ex) {
            if (!closed.get()) System.out.println("클라이언트 연결 종료: " + ex.getMessage());
        } finally {
            close();
            listener.disconnected(this);
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = input.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (ch == '\r') continue;
            if (line.length() >= 2048) throw new IOException("메시지 최대 길이 초과");
            line.append((char) ch);
        }
        return line.length() == 0 ? null : line.toString();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sender.interrupt();
        outgoing.clear();
        try { socket.close(); }
        catch (IOException ignored) { /* 이미 끊어진 연결 */ }
    }
}
