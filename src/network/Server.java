package network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Server implements AutoCloseable {
    private static final int MAX_PLAYERS = 4;

    private final ServerSocket listener;
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int nextId = 1;
    private boolean started;

    public Server(int port) throws IOException {
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
        } catch (IOException | RuntimeException ex) {
            listener.close();
            throw ex;
        }
    }

    public void start() throws IOException {
        System.out.println("서버 시작: 포트 " + listener.getLocalPort() + " / 최대 " + MAX_PLAYERS + "명");
        while (!closed.get()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException ex) {
                if (closed.get()) return;
                throw ex;
            }

            try {
                acceptClient(socket);
            } catch (IOException ex) {
                closeSocket(socket);
                System.err.println("접속 초기화 실패: " + ex.getMessage());
            }
        }
    }

    private synchronized void acceptClient(Socket socket) throws IOException {
        if (closed.get()) {
            closeSocket(socket);
            return;
        }
        if (started || clients.size() >= MAX_PLAYERS) {
            String error = started ? "GAME_IN_PROGRESS" : "ROOM_FULL";
            socket.getOutputStream().write(("ERROR\t" + error + "\n").getBytes(StandardCharsets.UTF_8));
            closeSocket(socket);
            return;
        }

        ClientHandler client = new ClientHandler(socket);
        clients.add(client);
        if (closed.get()) {
            clients.remove(client);
            client.close();
            return;
        }
        new Thread(client, "client-handler").start();
    }

    private synchronized void receive(ClientHandler client, String message) {
        if (closed.get() || !clients.contains(client)) return;
        String[] parts = message.split("\t", -1);
        String command = parts[0];
        if (command.equals("JOIN") && parts.length == 2) {
            join(client, parts[1]);
            return;
        }
        if (client.name == null) {
            client.send("ERROR\tJOIN_REQUIRED");
            return;
        }

        switch (command) {
            case "CHAT" -> {
                if (parts.length != 2 || parts[1].isBlank() || parts[1].length() > 500
                        || parts[1].chars().anyMatch(Character::isISOControl)) {
                    client.send("ERROR\tINVALID_CHAT");
                } else {
                    broadcast("CHAT\t" + client.id + "\t" + client.name + "\t" + parts[1]);
                }
            }
            case "START" -> {
                if (parts.length == 1) startGame(client);
                else client.send("ERROR\tINVALID_MESSAGE");
            }
            case "INPUT" -> {
                if (parts.length != 2 || parts[1].length() != 1 || !started
                        || !client.keys.contains(parts[1])) {
                    client.send("ERROR\tINVALID_INPUT");
                } else {
                    broadcast("INPUT\t" + client.id + "\t" + parts[1]);
                }
            }
            case "QUIT" -> client.close();
            default -> client.send("ERROR\tUNKNOWN_COMMAND");
        }
    }

    private void join(ClientHandler client, String nickname) {
        if (client.name != null) {
            client.send("ERROR\tALREADY_JOINED");
            return;
        }
        String name = nickname.trim();
        if (name.isEmpty() || name.length() > 20 || name.chars().anyMatch(Character::isISOControl)
                || clients.stream().anyMatch(other -> name.equals(other.name))) {
            client.send("ERROR\tINVALID_NAME");
            return;
        }
        client.id = nextId++;
        client.name = name;
        client.send("WELCOME\t" + client.id);
        System.out.println("입장: " + name);
        sendRoster();
    }

    private void startGame(ClientHandler requester) {
        if (started) {
            requester.send("ERROR\tALREADY_STARTED");
            return;
        }
        List<ClientHandler> joined = joinedClients();
        if (joined.get(0) != requester) {
            requester.send("ERROR\tHOST_ONLY");
            return;
        }
        if (joined.size() < 2 || joined.size() != clients.size()) {
            requester.send("ERROR\tNEED_2_TO_4_PLAYERS");
            return;
        }

        String[] assignments = switch (joined.size()) {
            case 2 -> new String[]{"WS", "AD"};
            case 3 -> new String[]{"W", "A", "SD"};
            default -> new String[]{"W", "A", "S", "D"};
        };
        started = true;
        for (int i = 0; i < joined.size(); i++) {
            ClientHandler client = joined.get(i);
            client.keys = assignments[i];
            client.send("KEYS\t" + client.keys);
        }
        broadcast("START");
    }

    private List<ClientHandler> joinedClients() {
        List<ClientHandler> joined = new ArrayList<>();
        for (ClientHandler client : clients) {
            if (client.name != null) joined.add(client);
        }
        joined.sort(Comparator.comparingInt(client -> client.id));
        return joined;
    }

    private void sendRoster() {
        broadcast("ROSTER_BEGIN");
        boolean host = true;
        for (ClientHandler client : joinedClients()) {
            broadcast("PLAYER\t" + client.id + "\t" + client.name + "\t" + (host ? "HOST" : "MEMBER"));
            host = false;
        }
        broadcast("ROSTER_END");
    }

    private void broadcast(String message) {
        for (ClientHandler client : clients) {
            if (client.name != null) client.send(message);
        }
    }

    private synchronized void disconnected(ClientHandler client) {
        if (!clients.remove(client) || closed.get() || client.name == null) return;
        if (started) {
            started = false;
            for (ClientHandler other : clients) other.keys = "";
            broadcast("STOP\tPLAYER_LEFT");
        }
        System.out.println("퇴장: " + client.name);
        broadcast("LEFT\t" + client.id);
        sendRoster();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { listener.close(); }
        catch (IOException ignored) { }
        for (ClientHandler client : clients) client.close();
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader input;
        private final BufferedWriter output;
        private int id;
        private String name;
        private String keys = "";

        ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        @Override public void run() {
            try {
                String message;
                while (!socket.isClosed() && (message = readLine()) != null) receive(this, message);
            } catch (IOException ex) {
                if (!socket.isClosed()) System.out.println("클라이언트 연결 종료: " + ex.getMessage());
            } finally {
                close();
                disconnected(this);
            }
        }

        void send(String message) {
            if (socket.isClosed()) return;
            try {
                output.write(message);
                output.newLine();
                output.flush();
            } catch (IOException ex) {
                close();
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

        void close() { closeSocket(socket); }
    }
}
