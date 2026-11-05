package game;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Real Swing button: focus, Tab, Space, Enter, mouse and accessible name. */
@SuppressWarnings("serial") // 버튼 객체 자체를 직렬화해서 저장하지 않습니다.
final class ImageButton extends JButton {
    private final BufferedImage artwork;

    ImageButton(BufferedImage artwork, String label) {
        this.artwork = artwork;
        setText(label);
        getAccessibleContext().setAccessibleName(label);
        setToolTipText(label);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "activate");
        getActionMap().put("activate", new AbstractAction() {
            public void actionPerformed(ActionEvent event) { doClick(); }
        });
        getModel().addChangeListener(e -> repaint());
        addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
            public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
        });
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        boolean pressed = getModel().isArmed() && getModel().isPressed();
        int dy = pressed ? Math.max(1, getHeight() * 3 / 63) : 0;
        g.drawImage(artwork, 0, dy, getWidth(), getHeight() - dy, null);
        if (getModel().isRollover() || isFocusOwner()) {
            g.setColor(new Color(255, 255, 255, 40));
            g.fill(new RoundRectangle2D.Double(3, 3 + dy, getWidth() - 6,
                getHeight() - 9 - dy, 20, 20));
        }
        if (isFocusOwner()) {
            g.setColor(new Color(0xDC9853));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(5, 5, getWidth() - 11, getHeight() - 12, 16, 16);
        }
        g.dispose();
    }
}
