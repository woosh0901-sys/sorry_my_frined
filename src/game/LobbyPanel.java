package game;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

@SuppressWarnings("serial")
final class LobbyPanel extends JPanel {
    private final DefaultListModel<String> players = new DefaultListModel<>();
    private final JTextArea messages = new JTextArea();
    private final JLabel state = new JLabel("친구를 기다리고 있어요. 2~4명이 모이면 시작할 수 있습니다.");
    private final JButton start = new JButton("게임 시작");
    private String assignedKeys = "";
    private boolean playing;
    private boolean host;

    LobbyPanel(String roomInfo, Consumer<String> chat, Runnable begin, Runnable leave, Consumer<String> input) {
        super(new BorderLayout(20, 20));
        setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
        setBackground(new Color(0xE7F3EC));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 8));
        heading.setOpaque(false);
        JLabel title = new JLabel("친구야 미안해 · 대기실");
        title.setFont(new Font("Malgun Gothic", Font.BOLD, 28));
        heading.add(title); heading.add(new JLabel(roomInfo)); heading.add(state);
        add(heading, BorderLayout.NORTH);
        JList<String> list = new JList<>(players);
        list.setFont(new Font("Malgun Gothic", Font.PLAIN, 16));
        JScrollPane roster = new JScrollPane(list);
        roster.setPreferredSize(new Dimension(240, 0));
        roster.setBorder(BorderFactory.createTitledBorder("참가자"));
        add(roster, BorderLayout.WEST);
        messages.setEditable(false);
        messages.setLineWrap(true); messages.setWrapStyleWord(true);
        messages.setFont(new Font("Malgun Gothic", Font.PLAIN, 15));
        add(new JScrollPane(messages), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(12, 12));
        bottom.setOpaque(false);
        JTextField text = new JTextField();
        text.setFont(new Font("Malgun Gothic", Font.PLAIN, 16));
        JButton send = new JButton("보내기");
        Runnable submit = () -> {
            String value = text.getText().trim();
            if (value.isEmpty()) return;
            if (value.length() > 500 || value.chars().anyMatch(Character::isISOControl)) {
                append("채팅은 탭·줄바꿈 없이 500자 이내로 입력하세요."); return;
            }
            chat.accept(value); text.setText("");
        };
        text.addActionListener(e -> submit.run()); send.addActionListener(e -> submit.run());
        JPanel chatRow = new JPanel(new BorderLayout(8, 0));
        chatRow.add(text, BorderLayout.CENTER); chatRow.add(send, BorderLayout.EAST);
        bottom.add(chatRow, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setOpaque(false);
        JButton exit = new JButton("방 나가기");
        start.setEnabled(false);
        start.addActionListener(e -> begin.run()); exit.addActionListener(e -> leave.run());
        buttons.add(start); buttons.add(exit); bottom.add(buttons, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        for (String key : new String[]{"W", "A", "S", "D"}) {
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key.charAt(0), 0), key);
            getActionMap().put(key, new AbstractAction() {
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (playing && assignedKeys.contains(key)) input.accept(key);
                }
            });
        }
    }

    void showPlayers(java.util.List<String> names, boolean host) {
        this.host = host;
        players.clear(); names.forEach(players::addElement);
        updateStart();
    }

    void setKeys(String keys) { assignedKeys = keys; }

    void setPlaying(boolean value) {
        playing = value;
        updateStart();
        state.setText(value ? "시작! 내 담당 키: " + assignedKeys
            : "참가자가 나가 게임이 중단되었습니다. 다시 시작할 수 있습니다.");
        if (!value) assignedKeys = "";
    }

    private void updateStart() { start.setEnabled(host && players.size() >= 2 && !playing); }

    void append(String message) {
        if (messages.getDocument().getLength() > 30_000) messages.setText("");
        messages.append(message + "\n");
        messages.setCaretPosition(messages.getDocument().getLength());
    }
}
