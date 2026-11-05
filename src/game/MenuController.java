package game;

import javax.swing.*;
import java.awt.*;

/** 메인 메뉴 동작과 접속 정보 입력을 담당합니다. */
final class MenuController implements MainMenuPanel.Actions {
    private final JFrame window;
    private final MainMenuPanel mainMenu;
    private final RoomController room;

    MenuController(JFrame window) {
        this.window = window;
        mainMenu = new MainMenuPanel(this);
        room = new RoomController(window, this::showMain);
    }

    void showMain() {
        window.setContentPane(mainMenu);
        window.revalidate();
        window.repaint();
    }

    @Override public void createRoom() { showConnectionForm(true); }
    @Override public void joinRoom() { showConnectionForm(false); }
    @Override public void howToPlay() {
        JOptionPane.showMessageDialog(window,
            "2~4명이 캐릭터 하나를 함께 조종합니다.\n\n"
            + "2인: A 플레이어 W·S / B 플레이어 A·D\n"
            + "3인: C 플레이어가 두 키, 나머지는 한 키씩\n"
            + "4인: W·A·S·D를 하나씩 담당\n\n"
            + "친구들과 방향을 맞춰 장애물을 피하고 한성대까지 도착하세요!",
            "게임 방법", JOptionPane.INFORMATION_MESSAGE);
    }
    @Override public void settings() {
        String[] choices = {"1280 × 720", "960 × 540"};
        String selected = (String) JOptionPane.showInputDialog(window,
            "화면 크기를 선택하세요.", "설정", JOptionPane.PLAIN_MESSAGE,
            null, choices, choices[0]);
        if (selected != null) {
            panelSize(selected.startsWith("1280") ? 1280 : 960);
        }
    }
    @Override public void exit() {
        if (JOptionPane.showConfirmDialog(window, "게임을 종료할까요?", "게임 종료",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) window.dispose();
    }

    private void panelSize(int width) {
        Insets insets = window.getInsets();
        window.setSize(width + insets.left + insets.right,
            width * 9 / 16 + insets.top + insets.bottom);
        window.setLocationRelativeTo(null);
    }

    private void showConnectionForm(boolean host) {
        JTextField nickname = new JTextField(16);
        JTextField address = new JTextField("localhost", 16);
        JSpinner port = new JSpinner(new SpinnerNumberModel(5000, 1, 65535, 1));
        JPanel form = new JPanel(new GridLayout(0, 2, 12, 12));
        form.setFont(new Font("Malgun Gothic", Font.PLAIN, 14));
        form.add(new JLabel("닉네임")); form.add(nickname);
        if (!host) { form.add(new JLabel("서버 주소")); form.add(address); }
        form.add(new JLabel("포트")); form.add(port);
        String title = host ? "방 만들기" : "참여하기";
        while (JOptionPane.showConfirmDialog(window, form, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try { port.commitEdit(); }
            catch (java.text.ParseException ex) {
                JOptionPane.showMessageDialog(window, "포트는 1~65535 사이 숫자로 입력하세요.");
                continue;
            }
            if (nickname.getText().trim().isEmpty() || nickname.getText().trim().length() > 20
                    || nickname.getText().chars().anyMatch(Character::isISOControl)
                    || (!host && address.getText().trim().isEmpty())) {
                JOptionPane.showMessageDialog(window, "닉네임은 제어문자 없이 1~20자로, 서버 주소도 입력하세요.");
                continue;
            }
            room.connect(host, address.getText().trim(), ((Number) port.getValue()).intValue(),
                nickname.getText().trim());
            break;
        }
    }

}
