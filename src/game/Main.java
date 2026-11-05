package game;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.Font;

/** 프로그램 시작과 객체 연결만 담당합니다. */
public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UIManager.put("OptionPane.messageFont", new Font("Malgun Gothic", Font.PLAIN, 15));
            UIManager.put("Button.font", new Font("Malgun Gothic", Font.PLAIN, 14));
            JFrame window = new JFrame("친구야 미안해");
            new MenuController(window).showMain();
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            window.pack();
            window.setMinimumSize(new Dimension(800, 480));
            window.setLocationRelativeTo(null);
            window.setVisible(true);
        });
    }
}
