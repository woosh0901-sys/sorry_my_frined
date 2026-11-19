package client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

@SuppressWarnings("serial")
public final class JavaChatClientView extends JPanel implements AutoCloseable {
    private final String userName;
    private final Runnable onLeave;
    private final Socket socket = new Socket();
    private DataInputStream input;
    private DataOutputStream output;
    private int playerId;

    private final JLabel roomInfo = new JLabel();
    private final JTextArea messages = new JTextArea();
    private final JTextField txtInput = new JTextField();
    private final JButton send = new JButton("보내기");

    public JavaChatClientView(String userName, Runnable onLeave) {
        super(new BorderLayout(0, 14));
        this.userName = userName;
        this.onLeave = onLeave;
        setBorder(new EmptyBorder(28, 32, 28, 32));
        setBackground(new Color(0xE7F3EC));

        JPanel heading = new JPanel(new BorderLayout(12, 8));
        heading.setOpaque(false);
        JLabel title = new JLabel("친구야 미안해 · 대기실");
        title.setFont(new Font("Malgun Gothic", Font.BOLD, 28));
        JButton leave = new JButton("방 나가기");
        leave.addActionListener(event -> onLeave.run());
        heading.add(title, BorderLayout.CENTER);
        heading.add(leave, BorderLayout.EAST);
        heading.add(roomInfo, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        add(new JScrollPane(messages), BorderLayout.CENTER);

        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setOpaque(false);
        controls.add(new JLabel(userName), BorderLayout.WEST);
        controls.add(txtInput, BorderLayout.CENTER);
        controls.add(send, BorderLayout.EAST);
        txtInput.addActionListener(event -> sendChat());
        send.addActionListener(event -> sendChat());
        add(controls, BorderLayout.SOUTH);
    }

    public void connect(String address, int port) throws IOException {
        socket.connect(new InetSocketAddress(address, port), 4000);
        socket.setSoTimeout(4000);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        // 첫 응답으로 참가자 ID를 받거나, 거절 사유를 메인 화면에 전달한다.
        sendMessage("/JOIN " + userName);
        String[] reply = input.readUTF().split(" ", 2);
        if (reply.length == 2 && reply[0].equals("/JOIN_ERROR")) {
            throw new IOException(switch (reply[1]) {
                case "INVALID_NAME" -> "닉네임은 제어 문자 없이 1~20자로 입력하세요.";
                case "DUPLICATE_NAME" -> "이미 사용 중인 닉네임이에요.";
                default -> "서버가 참가 요청을 거절했어요.";
            });
        }
        if (reply.length != 2 || !reply[0].equals("/JOIN_OK")) {
            throw new IOException("서버에서 올바른 참가 승인 메시지를 받지 못했어요.");
        }
        try {
            playerId = Integer.parseInt(reply[1]);
            if (playerId < 1) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new IOException("서버에서 올바른 참가자 ID를 받지 못했어요.", ex);
        }
        socket.setSoTimeout(0);
    }

    public void listen(String info) {
        // 승인 응답은 connect()에서 읽었으므로 이후에는 환영 문구와 채팅을 받는다.
        roomInfo.setText(info + "   /   참가자 ID: " + playerId);
        new ListenNetwork().start();
        txtInput.requestFocusInWindow();
    }

    private final class ListenNetwork extends Thread {
        @Override
        public void run() {
            try {
                while (!socket.isClosed()) {
                    String message = input.readUTF();
                    // 수신은 별도 스레드에서 기다리고, 채팅창 갱신은 Swing에 맡긴다.
                    EventQueue.invokeLater(() -> appendText(message));
                }
            } catch (IOException ex) {
                if (!socket.isClosed()) {
                    EventQueue.invokeLater(() -> {
                        appendText("서버와의 연결이 종료되었습니다.\n");
                        txtInput.setEnabled(false);
                        send.setEnabled(false);
                    });
                }
            } finally {
                close();
            }
        }
    }

    private void sendChat() {
        String text = txtInput.getText().strip();
        if (text.equals("/exit")) {
            onLeave.run();
            return;
        }
        if (text.isEmpty() || socket.isClosed() || !send.isEnabled()) return;
        if (text.length() > 500) {
            appendText("채팅은 500자까지 입력할 수 있어요.\n");
            return;
        }
        txtInput.setText("");
        send.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    sendMessage("[" + userName + "] " + text + "\n");
                } catch (IOException ex) {
                    close();
                    EventQueue.invokeLater(() -> {
                        if (isShowing()) appendText("메시지를 보낼 수 없습니다.\n");
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                send.setEnabled(!socket.isClosed());
                txtInput.setEnabled(!socket.isClosed());
            }
        }.execute();
        txtInput.requestFocusInWindow();
    }

    private void sendMessage(String message) throws IOException {
        output.writeUTF(message);
        output.flush();
    }

    private void appendText(String message) {
        messages.append(message);
        messages.setCaretPosition(messages.getDocument().getLength());
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) { }
    }
}
