package game;

import client.GameConnection;
import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/** 접속·대기실 이벤트와 연결 수명만 관리합니다. */
final class RoomController {
    private final JFrame window;
    private final Runnable onLeave;
    private GameConnection connection;
    private LobbyPanel lobby;
    private boolean ownsServer;
    private boolean connecting;
    private final List<String> roster = new ArrayList<>();
    private int hostId;

    RoomController(JFrame window, Runnable onLeave) {
        this.window = window;
        this.onLeave = onLeave;
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { disconnect(); }
        });
    }

    void connect(boolean host, String address, int port, String nickname) {
        if (connecting || connection != null) return;
        connecting = true;
        JDialog progress = new JDialog(window, "접속 중", true);
        progress.add(new JLabel("  서버에 연결하고 있습니다…  ", SwingConstants.CENTER));
        progress.setSize(340, 110);
        progress.setLocationRelativeTo(window);
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        new SwingWorker<GameConnection, Void>() {
            private String roomInfo;

            @Override protected GameConnection doInBackground() throws Exception {
                GameConnection connected = GameConnection.open(host, address, port, nickname);
                String local = "localhost";
                if (host) {
                    try { local = GameConnection.localAddresses(); }
                    catch (java.net.SocketException ignored) { }
                }
                roomInfo = host ? "내 방 · 접속 주소: " + local + " / 포트: " + port
                    : "참여한 방 · " + address + ":" + port;
                return connected;
            }

            @Override protected void done() {
                connecting = false;
                progress.dispose();
                try {
                    GameConnection connected = get();
                    if (!window.isDisplayable()) { connected.close(); return; }
                    enterLobby(connected, host, roomInfo);
                } catch (Exception ex) {
                    if (!window.isDisplayable()) return;
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    JOptionPane.showMessageDialog(window, errorMessage(cause.getMessage()),
                        "접속 실패", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progress.setVisible(true);
    }

    private void enterLobby(GameConnection connected, boolean host, String info) {
        connection = connected;
        ownsServer = host;
        roster.clear();
        hostId = 0;
        lobby = new LobbyPanel(info, text -> connected.send("CHAT\t" + text),
            () -> connected.send("START"), this::leaveRoom,
            key -> connected.send("INPUT\t" + key));
        window.setContentPane(lobby);
        window.revalidate(); window.repaint();
        connected.listen(message -> SwingUtilities.invokeLater(() -> {
            if (connection == connected) {
                try { receive(message); }
                catch (NumberFormatException ex) { lobby.append("올바르지 않은 참가자 정보를 받았습니다."); }
            }
        }), reason -> SwingUtilities.invokeLater(() -> {
            if (connection != connected) return;
            disconnect();
            onLeave.run();
            JOptionPane.showMessageDialog(window, reason, "연결 종료", JOptionPane.INFORMATION_MESSAGE);
        }));
    }

    private void receive(String message) {
        String[] fields = message.split("\t", -1);
        switch (fields[0]) {
            case "ROSTER_BEGIN" -> { roster.clear(); hostId = 0; }
            case "PLAYER" -> {
                if (fields.length == 4) {
                    if (fields[3].equals("HOST")) hostId = Integer.parseInt(fields[1]);
                    roster.add(fields[2] + (fields[3].equals("HOST") ? " (방장)" : ""));
                }
            }
            case "ROSTER_END" -> lobby.showPlayers(roster, connection.playerId() == hostId);
            case "CHAT" -> { if (fields.length == 4) lobby.append(fields[2] + ": " + fields[3]); }
            case "KEYS" -> { if (fields.length == 2) lobby.setKeys(fields[1]); }
            case "START" -> { lobby.setPlaying(true); lobby.append("게임을 시작했습니다."); }
            case "STOP" -> { lobby.setPlaying(false); lobby.append("참가자가 나가 대기 상태로 돌아갑니다."); }
            case "INPUT" -> {
                if (fields.length == 3) lobby.append("플레이어 " + fields[1] + " 입력: " + fields[2]);
            }
            case "ERROR" -> { if (fields.length == 2) lobby.append(errorMessage(fields[1])); }
            default -> { /* LEFT는 이어지는 참가자 목록으로 반영 */ }
        }
    }

    private void leaveRoom() {
        if (ownsServer && JOptionPane.showConfirmDialog(window,
                "방을 닫으면 다른 참가자의 연결도 종료됩니다. 나갈까요?", "방 닫기",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        disconnect();
        onLeave.run();
    }

    private void disconnect() {
        GameConnection old = connection;
        connection = null;
        lobby = null;
        ownsServer = false;
        if (old != null) old.close();
    }

    private static String errorMessage(String code) {
        if (code == null) return "접속 정보를 확인하세요.";
        return switch (code) {
            case "ROOM_FULL" -> "방 인원이 가득 찼습니다.";
            case "GAME_IN_PROGRESS" -> "이미 게임이 진행 중입니다.";
            case "INVALID_NAME" -> "닉네임이 중복되었거나 형식이 올바르지 않습니다.";
            case "HOST_ONLY" -> "방장만 시작할 수 있습니다.";
            case "NEED_2_TO_4_PLAYERS" -> "2~4명이 모두 입장해야 시작할 수 있습니다.";
            case "ALREADY_STARTED" -> "이미 시작했습니다.";
            case "INVALID_INPUT" -> "현재 내 담당 키만 입력할 수 있습니다.";
            default -> "연결 상태와 주소·포트를 확인하세요.\n" + code;
        };
    }
}
