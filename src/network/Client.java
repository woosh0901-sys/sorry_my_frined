package network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Client implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final Server hostedServer;
    private final int playerId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean listening = new AtomicBoolean();
    private final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(128), task -> {
        Thread thread = new Thread(task, "room-send");
        thread.setDaemon(true);
        return thread;
    });

    public static Client open(boolean host, String address, int port, String nickname) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("포트 범위: 1~65535");
        if (nickname == null || nickname.isBlank() || nickname.length() > 20
                || nickname.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("닉네임은 제어문자 없이 1~20자로 입력하세요.");
        }
        Server server = host ? new Server(port) : null;
        if (server != null) {
            Thread thread = new Thread(() -> {
                try { server.start(); }
                catch (IOException ex) { server.close(); }
            }, "room-server");
            thread.setDaemon(true);
            thread.start();
        }
        try { return new Client(host ? "127.0.0.1" : address, port, nickname, server); }
        catch (IOException | RuntimeException ex) {
            if (server != null) server.close();
            throw ex;
        }
    }

    private Client(String address, int port, String nickname, Server server) throws IOException {
        hostedServer = server;
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), 4000);
            socket.setSoTimeout(4000);
            socket.setTcpNoDelay(true);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            output.write("JOIN\t" + nickname + "\n");
            output.flush();
            String reply = readLine();
            if (reply == null || !reply.startsWith("WELCOME\t")) {
                throw new IOException(reply == null ? "서버 연결이 종료되었습니다." : reply.replace("ERROR\t", ""));
            }
            playerId = Integer.parseInt(reply.substring(8));
            socket.setSoTimeout(0);
        } catch (IOException | RuntimeException ex) {
            socket.close();
            sender.shutdownNow();
            throw ex;
        }
    }

    public int playerId() { return playerId; }

    public void listen(Consumer<String> onMessage, Consumer<String> onDisconnect) {
        if (!listening.compareAndSet(false, true)) throw new IllegalStateException("이미 수신 중입니다.");
        Thread thread = new Thread(() -> {
            String reason = "서버와 연결이 끊어졌습니다.";
            try {
                String line;
                while (!closed.get() && (line = readLine()) != null) onMessage.accept(line);
            } catch (IOException ex) { reason = "연결 종료: " + ex.getMessage(); }
            finally {
                boolean unexpected = !closed.get();
                close();
                if (unexpected) onDisconnect.accept(reason);
            }
        }, "room-receive");
        thread.setDaemon(true);
        thread.start();
    }

    /** 소켓 전송으로 화면이 멈추지 않도록 전용 스레드에서 순서대로 전송합니다. */
    public void send(String message) {
        if (closed.get()) return;
        if (message.length() > 2048 || message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("올바르지 않은 메시지입니다.");
        }
        try {
            sender.execute(() -> {
                if (closed.get()) return;
                try { output.write(message + "\n"); output.flush(); }
                catch (IOException ex) {
                    // 수신 루프가 종료 이벤트를 전달하도록 소켓만 닫습니다.
                    try { socket.close(); } catch (IOException ignored) { }
                }
            });
        } catch (RejectedExecutionException ex) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = input.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (ch == '\r') continue;
            if (line.length() >= 2048) throw new IOException("서버 메시지 최대 길이 초과");
            line.append((char) ch);
        }
        return line.length() == 0 ? null : line.toString();
    }

    public static String localAddresses() throws SocketException {
        StringBuilder result = new StringBuilder();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            var addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address) {
                    if (result.length() > 0) result.append(", ");
                    result.append(address.getHostAddress());
                }
            }
        }
        return result.length() == 0 ? "localhost (이 컴퓨터)" : result.toString();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { socket.close(); } catch (IOException ignored) { }
        sender.shutdownNow();
        if (hostedServer != null) hostedServer.close();
    }
}
