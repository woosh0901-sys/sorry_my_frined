package server;

import game.Stage;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

public final class JavaChatServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 30000;

    private final ServerSocket socket;
    private final Vector<UserService> users = new Vector<>();
    private final Consumer<String> log;
    private final ScheduledExecutorService gameClock = Executors.newSingleThreadScheduledExecutor();
    private int nextPlayerId = 1;
    private GameSession game;
    private long lastTick;

    public JavaChatServer(int port, Consumer<String> log) throws IOException {
        this.log = log;
        socket = new ServerSocket(port);
        gameClock.scheduleWithFixedDelay(this::tick, 0, 33, TimeUnit.MILLISECONDS);
        new AcceptServer().start();
        log.accept("서버 시작: " + socket.getLocalPort());
    }

    public String localAddresses() {
        StringJoiner addresses = new StringJoiner(", ");
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (var ips = network.getInetAddresses(); ips.hasMoreElements();) {
                    if (ips.nextElement() instanceof Inet4Address ip) addresses.add(ip.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // 주소 확인이 실패해도 같은 컴퓨터에서는 접속할 수 있다.
        }
        return addresses.length() == 0 ? "127.0.0.1" : addresses.toString();
    }

    // 접속 수락과 메시지 처리를 나눠 여러 참가자의 접속을 계속 받는다.
    private final class AcceptServer extends Thread {
        @Override
        public void run() {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    synchronized (users) {
                        if (socket.isClosed()) {
                            client.close();
                            return;
                        }
                        UserService user = new UserService(client);
                        users.add(user);
                        user.start();
                    }
                } catch (IOException ex) {
                    if (!socket.isClosed()) log.accept("접속 수락 실패: " + ex.getMessage());
                }
            }
        }
    }

    // 참가 순서, 게임 상태, 전송 순서는 users 잠금 안에서 함께 관리한다.
    private List<UserService> players() {
        return users.stream().filter(user -> user.joined).sorted(Comparator.comparingInt(user -> user.playerId)).toList();
    }

    private void broadcast(List<String> messages) {
        for (UserService user : users) {
            if (user.joined) user.send(messages);
        }
    }

    private void broadcastRoom() {
        List<UserService> players = players();
        if (!players.isEmpty()) broadcast(List.of("/ROOM " + players.get(0).playerId + " " + players.size()));
    }

    private void receive(UserService user, String message) {
        String[] fields = message.split(" ", 4);
        synchronized (users) {
            if (socket.isClosed() || user.clientSocket.isClosed()) return;
            switch (fields[0]) {
                case "/START" -> {
                    if (fields.length == 1) startGame(user);
                }
                case "/KEY" -> {
                    if (game == null || fields.length != 3 || fields[1].length() != 1) return;
                    if (!fields[2].equals("DOWN") && !fields[2].equals("UP")) return;
                    String change = game.changeKey(user.playerId, fields[1].charAt(0), fields[2].equals("DOWN"));
                    if (change != null) broadcast(List.of(change));
                }
                case "/NEXT" -> {
                    Stage next = game == null ? null : game.nextStage();
                    if (fields.length == 1 && next != null) startStage(next, players());
                }
                default -> {
                    if (message.startsWith("/") || message.isBlank() || message.length() > 1024) return;
                    log.accept(message);
                    broadcast(List.of(message + "\n"));
                }
            }
        }
    }

    private void startGame(UserService requester) {
        List<UserService> players = players();
        if (players.get(0) != requester) requester.send(List.of("/ERROR HOST_ONLY"));
        else if (game != null) requester.send(List.of("/ERROR GAME_IN_PROGRESS"));
        else if (players.size() < 2) requester.send(List.of("/ERROR NOT_ENOUGH_PLAYERS"));
        else startStage(Stage.STAGE_1, players);
    }

    private void startStage(Stage stage, List<UserService> players) {
        game = new GameSession(stage, players.stream()
            .map(user -> new GameSession.Player(user.playerId, user.userName)).toList());
        lastTick = System.nanoTime();
        broadcast(game.startMessages());
    }

    private void tick() {
        synchronized (users) {
            if (game == null || socket.isClosed()) return;
            long now = System.nanoTime();
            List<String> update = game.step(Math.min(0.05, (now - lastTick) / 1e9));
            lastTick = now;
            if (!update.isEmpty()) broadcast(update);
        }
    }

    private void removeUser(UserService user) {
        synchronized (users) {
            users.remove(user);
            if (!user.joined || socket.isClosed()) return;
            if (game != null) {
                game = null;
                broadcast(List.of("/LOBBY", "참가자가 나가 게임을 종료했습니다.\n"));
            }
            broadcastRoom();
        }
    }

    private final class UserService extends Thread {
        private final Socket clientSocket;
        private final BlockingQueue<List<String>> outgoing = new ArrayBlockingQueue<>(64);
        private final Thread writer;
        private DataOutputStream output;
        private String userName;
        private int playerId;
        private boolean joined;

        UserService(Socket clientSocket) {
            this.clientSocket = clientSocket;
            writer = new Thread(this::writeMessages, "smf-room-writer");
        }

        @Override
        public void run() {
            try (clientSocket) {
                clientSocket.setSoTimeout(4000);
                clientSocket.setTcpNoDelay(true);
                DataInputStream input = new DataInputStream(clientSocket.getInputStream());
                output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

                String rejection = join(input.readUTF());
                if (rejection != null) {
                    output.writeUTF("/JOIN_ERROR " + rejection);
                    output.flush();
                    return;
                }
                clientSocket.setSoTimeout(0);
                writer.start();
                log.accept(userName + " 입장");

                while (!clientSocket.isClosed()) {
                    String message = input.readUTF().trim();
                    receive(this, message);
                }
            } catch (IOException ex) {
                // 연결이 닫히면 아래에서 참가자 목록을 정리한다.
            } finally {
                closeSocket();
                removeUser(this);
                if (joined) log.accept(userName + " 퇴장");
            }
        }

        // 접속 후 첫 메시지로 참가를 처리하고, 승인 ID나 거절 사유를 보낸다.
        private String join(String request) {
            String[] fields = request.split(" ", 2);
            if (fields.length != 2 || !fields[0].equals("/JOIN")) return "INVALID_REQUEST";

            String name = fields[1].strip();
            if (name.isEmpty() || name.length() > 20 || name.chars().anyMatch(Character::isISOControl)) {
                return "INVALID_NAME";
            }

            synchronized (users) {
                if (game != null) return "GAME_IN_PROGRESS";
                if (players().size() >= 4) return "ROOM_FULL";
                if (users.stream().anyMatch(user -> name.equals(user.userName))) return "DUPLICATE_NAME";
                userName = name;
                playerId = nextPlayerId++;
                // 승인 메시지가 대기실 정보나 게임 메시지보다 먼저 도착해야 한다.
                send(List.of("/JOIN_OK " + playerId, "Welcome to Java chat server\n", name + "님 환영합니다.\n"));
                joined = true;
                broadcastRoom();
            }
            return null;
        }

        // 느린 연결 때문에 서버 틱이나 다른 참가자의 전송이 멈추지 않게 한다.
        private void send(List<String> messages) {
            if (!clientSocket.isClosed() && !outgoing.offer(messages)) closeSocket();
        }

        private void writeMessages() {
            try {
                while (!clientSocket.isClosed()) {
                    for (String message : outgoing.take()) output.writeUTF(message);
                    output.flush();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                closeSocket();
            }
        }

        private void closeSocket() {
            try {
                clientSocket.close();
            } catch (IOException ignored) { }
            writer.interrupt();
        }
    }

    @Override
    public void close() {
        gameClock.shutdownNow();
        // 서버 소켓을 닫아 accept()를 종료하고, 참가자 소켓을 닫아 readUTF()를 깨운다.
        try {
            socket.close();
        } catch (IOException ignored) { }
        for (UserService user : users.toArray(new UserService[0])) user.closeSocket();
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(JavaChatServer::showServerWindow);
    }

    private static void showServerWindow() {
        JFrame frame = new JFrame("채팅 서버");
        JTextArea messages = new JTextArea(14, 32);
        messages.setEditable(false);
        JTextField port = new JTextField(String.valueOf(DEFAULT_PORT), 6);
        JButton start = new JButton("서버 시작");
        start.addActionListener(event -> {
            try {
                int number = Integer.parseInt(port.getText().trim());
                if (number < 1 || number > 65535) throw new IllegalArgumentException("포트는 1~65535 사이여야 합니다.");
                JavaChatServer server = new JavaChatServer(number, message ->
                    EventQueue.invokeLater(() -> messages.append(message + "\n")));
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent event) {
                        server.close();
                    }
                });
                start.setEnabled(false);
                port.setEditable(false);
            } catch (IllegalArgumentException | IOException ex) {
                messages.append("서버를 시작할 수 없습니다. " + ex.getMessage() + "\n");
            }
        });
        JPanel controls = new JPanel();
        controls.add(new JLabel("포트"));
        controls.add(port);
        controls.add(start);
        frame.add(new JScrollPane(messages), BorderLayout.CENTER);
        frame.add(controls, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }
}
