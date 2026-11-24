package main;

import client.JavaChatClientView;
import game.GamePanel;
import game.Stage;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import server.JavaChatServer;

@SuppressWarnings("serial")
public final class Main extends JFrame implements MainMenuPanel.Actions {
    private final MainMenuPanel menu;
    private JavaChatClientView chat;
    private JavaChatServer hostedServer;
    private GamePanel game;
    private boolean connecting;

    public static void main(String[] args) {
        EventQueue.invokeLater(() -> new Main().setVisible(true));
    }

    private Main() {
        super("친구야 미안해");
        menu = new MainMenuPanel(this);
        setContentPane(menu);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeRoom();
            }
        });
        pack();
        setMinimumSize(new Dimension(800, 480));
        setLocationRelativeTo(null);
    }

    @Override
    public void createRoom() {
        showConnectionForm(true);
    }

    @Override
    public void joinRoom() {
        showConnectionForm(false);
    }

    private void showConnectionForm(boolean host) {
        if (connecting) return;
        JTextField nickname = new JTextField(16);
        JTextField address = new JTextField("127.0.0.1", 16);
        JTextField port = new JTextField(String.valueOf(JavaChatServer.DEFAULT_PORT), 16);
        JPanel form = new JPanel(new GridLayout(0, 2, 10, 8));
        form.add(new JLabel("닉네임"));
        form.add(nickname);
        if (!host) {
            form.add(new JLabel("서버 주소"));
            form.add(address);
        }
        form.add(new JLabel("포트"));
        form.add(port);

        String title = host ? "방 만들기" : "참여하기";
        while (JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String name = nickname.getText().strip();
            String ip = address.getText().strip();
            try {
                if (name.isEmpty() || name.length() > 20 || name.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("닉네임은 제어 문자 없이 1~20자로 입력하세요.");
                }
                if (ip.isEmpty()) throw new IllegalArgumentException("서버 주소를 입력하세요.");
                int number = Integer.parseInt(port.getText().strip());
                if (number < 1 || number > 65535) throw new NumberFormatException();
                connect(host, ip, number, name);
                return;
            } catch (NumberFormatException ex) {
                showError("포트는 1~65535 사이 숫자로 입력하세요.");
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
            }
        }
    }

    private void connect(boolean host, String address, int port, String name) {
        connecting = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        JavaChatClientView next = new JavaChatClientView(name, this::leaveRoom);
        new SwingWorker<JavaChatServer, Void>() {
            private String roomInfo;

            // 방을 만들 때만 서버를 열고, 접속 작업은 Swing 이벤트 스레드 밖에서 진행한다.
            @Override
            protected JavaChatServer doInBackground() throws IOException {
                JavaChatServer server = host ? new JavaChatServer(port, System.out::println) : null;
                try {
                    // 클라이언트가 /JOIN_OK와 참가자 ID를 받으면 connect()가 끝난다.
                    next.connect(address, port);
                    roomInfo = server != null ? "방 주소: " + server.localAddresses() + "   /   포트: " + port
                        : "접속 주소: " + address + "   /   포트: " + port;
                    return server;
                } catch (IOException | RuntimeException ex) {
                    next.close();
                    if (server != null) server.close();
                    throw ex;
                }
            }

            @Override
            protected void done() {
                connecting = false;
                setCursor(Cursor.getDefaultCursor());
                try {
                    JavaChatServer server = get();
                    if (!isDisplayable()) {
                        next.close();
                        if (server != null) server.close();
                        return;
                    }
                    hostedServer = server;
                    chat = next;
                    chat.setGameHandler(Main.this::onGameMessage);
                    // 참가가 승인되면 같은 창을 대기실로 바꾸고 채팅 수신을 시작한다.
                    setContentPane(chat);
                    revalidate();
                    repaint();
                    chat.listen(roomInfo);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    if (isDisplayable()) showError("방에 연결할 수 없습니다.\n" + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    // 서버가 보낸 게임 진행 메시지. 지금은 시작 신호만 처리하고,
    // 위치·충돌·클리어 메시지는 서버 담당 코드에서 GamePanel 메서드로 연결한다(docs/game-screen-handoff.md).
    private void onGameMessage(String message) {
        String[] fields = message.split(" ");
        if (fields[0].equals("/START")) {
            try {
                showGame(fields.length > 1 ? Integer.parseInt(fields[1]) : 1);
            } catch (NumberFormatException ignored) { }
        }
    }

    // 대기실에서 게임 시작 신호를 받으면 같은 창을 게임 화면으로 바꾼다.
    private void showGame(int number) {
        Stage stage = Stage.of(number);
        if (stage == null || chat == null) return;
        if (game == null) {
            game = new GamePanel(new GamePanel.Listener() {
                @Override public void keyChanged(char key, boolean pressed) {
                    if (chat != null) chat.sendKey(key, pressed);
                }

                @Override public void exitRequested() { leaveRoom(); }
            });
        }
        game.startStage(stage);
        setContentPane(game);
        revalidate();
        repaint();
        game.requestFocusInWindow();
    }

    private void leaveRoom() {
        if (hostedServer != null && JOptionPane.showConfirmDialog(this,
                "방을 닫으면 참가자의 연결도 종료됩니다. 나갈까요?",
                "방 나가기", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        closeRoom();
        setContentPane(menu);
        revalidate();
        repaint();
    }

    private void closeRoom() {
        game = null;
        if (chat != null) {
            chat.close();
            chat = null;
        }
        if (hostedServer != null) {
            hostedServer.close();
            hostedServer = null;
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "접속 오류", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void howToPlay() {
        JOptionPane.showMessageDialog(this,
            "한 명이 방을 만들고 친구에게 표시된 IP 주소와 포트를 알려 주세요.\n"
            + "친구는 참여하기에서 주소와 포트를 입력하면 함께 채팅할 수 있어요.",
            "게임 방법", JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void settings() {
        String[] choices = {"1280 × 720", "960 × 540"};
        String selected = (String) JOptionPane.showInputDialog(this, "화면 크기를 선택하세요.",
            "설정", JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
        if (selected == null) return;
        int width = selected.equals(choices[0]) ? 1280 : 960;
        Insets insets = getInsets();
        setSize(width + insets.left + insets.right, width * 9 / 16 + insets.top + insets.bottom);
        setLocationRelativeTo(null);
    }

    @Override
    public void exit() {
        if (JOptionPane.showConfirmDialog(this, "게임을 종료할까요?", "게임 종료",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) dispose();
    }
}
