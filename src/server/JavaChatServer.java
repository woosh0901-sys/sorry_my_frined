package server;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.StringJoiner;
import java.util.Vector;
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

    public JavaChatServer(int port, Consumer<String> log) throws IOException {
        this.log = log;
        socket = new ServerSocket(port);
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

    private final class UserService extends Thread {
        private final Socket clientSocket;
        private DataOutputStream output;
        private volatile String userName;

        UserService(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (clientSocket) {
                clientSocket.setSoTimeout(4000);
                DataInputStream input = new DataInputStream(clientSocket.getInputStream());
                output = new DataOutputStream(clientSocket.getOutputStream());

                String login = input.readUTF();
                if (!login.startsWith("/login ") || login.substring(7).isBlank()) return;
                String name = login.substring(7).trim();
                clientSocket.setSoTimeout(0);
                synchronized (this) {
                    writeOne("Welcome to Java chat server\n");
                    userName = name;
                    writeOne(name + "님 환영합니다.\n");
                }
                log.accept(name + " 입장");

                while (!clientSocket.isClosed()) {
                    String message = input.readUTF().trim();
                    log.accept(message);
                    writeAll(message + "\n");
                }
            } catch (IOException ex) {
                // 연결이 닫히면 아래에서 참가자 목록을 정리한다.
            } finally {
                users.remove(this);
                if (userName != null) log.accept(userName + " 퇴장");
            }
        }

        private synchronized void writeOne(String message) throws IOException {
            output.writeUTF(message);
            output.flush();
        }

        private void writeAll(String message) {
            for (UserService user : users.toArray(new UserService[0])) {
                if (user.userName == null) continue;
                try {
                    user.writeOne(message);
                } catch (IOException ex) {
                    user.closeSocket();
                }
            }
        }

        private void closeSocket() {
            try {
                clientSocket.close();
            } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() {
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
