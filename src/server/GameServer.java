package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 단일 방의 접속 인원·채팅·시작·키 소유권을 관리합니다. Swing에 의존하지 않습니다. */
public final class GameServer implements ClientSession.Listener, AutoCloseable {
    private record Player(int id, String name) { }

    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Map<ClientSession, Player> clients = new LinkedHashMap<>();
    private final Map<ClientSession, String> keys = new LinkedHashMap<>();
    private int nextId = 1;
    private boolean started;
    private volatile boolean closed;

    public GameServer(int port) throws IOException {
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
        } catch (IOException | RuntimeException ex) { listener.close(); throw ex; }
    }

    public void start() throws IOException {
        System.out.println("서버 시작: 포트 " + listener.getLocalPort() + " / 최대 4명");
        while (!closed) {
            Socket socket;
            try { socket = listener.accept(); }
            catch (IOException ex) { if (closed) return; throw ex; }
            try { accept(socket); }
            catch (IOException ex) {
                socket.close();
                System.err.println("접속 초기화 실패: " + ex.getMessage());
            }
        }
    }

    private synchronized void accept(Socket socket) throws IOException {
        if (closed || started || clients.size() == 4) {
            socket.getOutputStream().write(("ERROR\t" + (started ? "GAME_IN_PROGRESS" : "ROOM_FULL") + "\n")
                .getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }
        ClientSession session = new ClientSession(socket, this);
        clients.put(session, null); // JOIN 대기 중인 소켓도 정원에 포함합니다.
        workers.execute(session);
    }

    @Override public synchronized void received(ClientSession client, String message) {
        if (!clients.containsKey(client)) return;
        String[] parts = message.split("\t", -1);
        String command = parts[0];
        if (command.equals("JOIN") && parts.length == 2) { join(client, parts[1]); return; }
        Player player = clients.get(client);
        if (player == null) { client.send("ERROR\tJOIN_REQUIRED"); return; }
        switch (command) {
            case "CHAT" -> {
                if (parts.length != 2 || parts[1].isBlank() || parts[1].length() > 500
                        || parts[1].chars().anyMatch(Character::isISOControl)) {
                    client.send("ERROR\tINVALID_CHAT");
                } else broadcast("CHAT\t" + player.id() + "\t" + player.name() + "\t" + parts[1]);
            }
            case "START" -> {
                if (parts.length == 1) startGame(client);
                else client.send("ERROR\tINVALID_MESSAGE");
            }
            case "INPUT" -> {
                if (parts.length != 2 || parts[1].length() != 1 || !started
                        || !keys.getOrDefault(client, "").contains(parts[1])) {
                    client.send("ERROR\tINVALID_INPUT");
                } else broadcast("INPUT\t" + player.id() + "\t" + parts[1]);
            }
            case "QUIT" -> { client.close(); disconnected(client); }
            default -> client.send("ERROR\tUNKNOWN_COMMAND");
        }
    }

    private void join(ClientSession client, String nickname) {
        if (clients.get(client) != null) { client.send("ERROR\tALREADY_JOINED"); return; }
        String name = nickname.trim();
        if (name.isEmpty() || name.length() > 20 || name.chars().anyMatch(Character::isISOControl)
                || clients.values().stream().anyMatch(p -> p != null && p.name().equals(name))) {
            client.send("ERROR\tINVALID_NAME");
            return;
        }
        try { client.joined(); }
        catch (IOException ex) { client.close(); return; }
        Player player = new Player(nextId++, name);
        clients.put(client, player);
        client.send("WELCOME\t" + player.id());
        System.out.println("입장: " + name);
        sendRoster();
    }

    private void startGame(ClientSession requester) {
        if (started) { requester.send("ERROR\tALREADY_STARTED"); return; }
        if (requester != host()) { requester.send("ERROR\tHOST_ONLY"); return; }
        if (clients.size() < 2 || clients.containsValue(null)) {
            requester.send("ERROR\tNEED_2_TO_4_PLAYERS");
            return;
        }
        // 입장 순서 A, B, C, D. 방향의 의미와 담당 키는 랜덤으로 바꾸지 않습니다.
        String[] assignments = switch (clients.size()) {
            case 2 -> new String[]{"WS", "AD"};
            case 3 -> new String[]{"W", "A", "SD"};
            default -> new String[]{"W", "A", "S", "D"};
        };
        int index = 0;
        var joined = clients.entrySet().stream()
            .sorted(Comparator.comparingInt(e -> e.getValue().id())).toList();
        for (var entry : joined) keys.put(entry.getKey(), assignments[index++]);
        started = true;
        keys.forEach((client, assigned) -> client.send("KEYS\t" + assigned));
        broadcast("START");
    }

    private ClientSession host() {
        return clients.entrySet().stream().filter(e -> e.getValue() != null)
            .sorted(Comparator.comparingInt(e -> e.getValue().id()))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private void sendRoster() {
        broadcast("ROSTER_BEGIN");
        ClientSession host = host();
        clients.forEach((client, player) -> {
            if (player != null) broadcast("PLAYER\t" + player.id() + "\t" + player.name()
                + "\t" + (client == host ? "HOST" : "MEMBER"));
        });
        broadcast("ROSTER_END");
    }

    private void broadcast(String message) {
        clients.forEach((client, player) -> { if (player != null) client.send(message); });
    }

    @Override public synchronized void disconnected(ClientSession client) {
        Player player = clients.remove(client);
        if (player == null) return;
        keys.remove(client);
        if (closed) return;
        if (started) {
            started = false;
            keys.clear();
            broadcast("STOP\tPLAYER_LEFT");
        }
        System.out.println("퇴장: " + player.name());
        broadcast("LEFT\t" + player.id());
        sendRoster();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { listener.close(); }
        catch (IOException ignored) { /* 이미 닫힌 서버 */ }
        clients.keySet().forEach(ClientSession::close);
        clients.clear();
        keys.clear();
        workers.shutdownNow();
    }
}
