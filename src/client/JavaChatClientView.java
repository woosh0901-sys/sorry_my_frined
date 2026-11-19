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
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        sendMessage("/login " + userName);
    }

    public void listen(String info) {
        roomInfo.setText(info);
        new ListenNetwork().start();
        txtInput.requestFocusInWindow();
    }

    private final class ListenNetwork extends Thread {
        @Override
        public void run() {
            try {
                while (!socket.isClosed()) {
                    String message = input.readUTF();
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
