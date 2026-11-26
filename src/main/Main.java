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
import java.util.ArrayList;
import java.util.List;
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
    private final List<GamePanel.Slot> slots = new ArrayList<>();
    private int stageNumber;
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
                    chat.setGameHandler(message -> {
                        if (chat == next) onGameMessage(message);
                    });
                    chat.setDisconnectHandler(() -> {
                        if (chat != next) return;
                        returnToMenu();
                        if (isDisplayable()) showError("방과의 연결이 종료되었어요.");
                    });
                    // 참가가 승인되면 같은 창을 대기실로 바꾸고 채팅 수신을 시작한다.
                    showPanel(chat);
                    chat.listen(roomInfo);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    if (isDisplayable()) showError("방에 연결할 수 없습니다.\n" + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    // 서버가 판정한 상태를 Swing 화면에 반영한다.
    private void onGameMessage(String message) {
        String[] fields = message.split(" ", 5);
        try {
            switch (fields[0]) {
                case "/ROOM" -> {
                    if (fields.length == 3) chat.updateRoom(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]));
                }
                case "/START" -> {
                    if (fields.length == 2) showGame(Integer.parseInt(fields[1]));
                }
                case "/LOBBY" -> showLobby();
                case "/ERROR" -> {
                    if (fields.length == 2) chat.showRoomError(fields[1]);
                }
                default -> {
                    if (game != null) updateGame(fields);
                }
            }
        } catch (NumberFormatException ignored) { }
    }

    private void updateGame(String[] fields) {
        switch (fields[0]) {
            case "/SLOT" -> {
                if (fields.length != 5 || fields[1].length() != 1) return;
                char key = fields[1].charAt(0);
                int seat = Integer.parseInt(fields[2]);
                boolean mine = Integer.parseInt(fields[3]) == chat.playerId();
                slots.removeIf(slot -> slot.key() == key);
                slots.add(new GamePanel.Slot(key, fields[4], seat, mine));
                game.setPlayers(slots);
            }
            case "/POS" -> {
                if (fields.length == 3) game.setPlayerPosition(Double.parseDouble(fields[1]), Double.parseDouble(fields[2]));
            }
            case "/OBS" -> {
                if (fields.length == 4) game.setObstaclePosition(Integer.parseInt(fields[1]),
                    Double.parseDouble(fields[2]), Double.parseDouble(fields[3]));
            }
            case "/HELD" -> {
                if (fields.length == 3 && fields[1].length() == 1) game.setKeyHeld(fields[1].charAt(0), fields[2].equals("1"));
            }
            case "/COIN" -> {
                if (fields.length == 2) game.setCoinCollected(Integer.parseInt(fields[1]));
            }
            case "/HIT" -> {
                if (fields.length == 2) game.playerHit(Integer.parseInt(fields[1]));
            }
            case "/CLEAR" -> {
                if (fields.length == 3) game.showClear(Long.parseLong(fields[1]), Integer.parseInt(fields[2]),
                    Stage.of(stageNumber + 1) != null);
            }
            default -> { }
        }
    }

    // 대기실에서 게임 시작 신호를 받으면 같은 창을 게임 화면으로 바꾼다.
    private void showGame(int number) {
        Stage stage = Stage.of(number);
        if (stage == null || chat == null) return;
        if (game == null) {
            game = new GamePanel(new GamePanel.Listener() {
                @Override public void keyChanged(char key, boolean pressed) {
                    if (chat != null && slots.stream().anyMatch(slot -> slot.mine() && slot.key() == key)) {
                        chat.sendKey(key, pressed);
                    }
                }

                @Override public void exitRequested() { leaveRoom(); }
                @Override public void nextRequested() { if (chat != null) chat.requestNextStage(); }
            });
            game.setOnline(true);
        }
        stageNumber = number;
        slots.clear();
        game.setPlayers(slots);
        game.startStage(stage);
        if (getContentPane() != game) showPanel(game);
        game.requestFocusInWindow();
    }

    private void showLobby() {
        game = null;
        slots.clear();
        showPanel(chat);
    }

    private void showPanel(JPanel panel) {
        setContentPane(panel);
        revalidate();
        repaint();
    }

    private void leaveRoom() {
        if (hostedServer != null && JOptionPane.showConfirmDialog(this,
                "방을 닫으면 참가자의 연결도 종료됩니다. 나갈까요?",
                "방 나가기", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        returnToMenu();
    }

    private void returnToMenu() {
        closeRoom();
        showPanel(menu);
    }

    private void closeRoom() {
        game = null;
        slots.clear();
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
        JOptionPane.showMessageDialog(this, JOptionPane.INFORMATION_MESSAGE);
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
