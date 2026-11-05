package game;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("serial") // 화면 객체 자체를 직렬화해서 저장하지 않습니다.
public final class MainMenuPanel extends JPanel {
    public interface Actions {
        void createRoom();
        void joinRoom();
        void howToPlay();
        void settings();
        void exit();
    }

    private final BufferedImage background = ImageAssets.load("background");
    private final BufferedImage title = ImageAssets.load("title");
    private final BufferedImage footer = ImageAssets.load("footer");
    private final Map<JButton, Rectangle> positions = new LinkedHashMap<>();

    public MainMenuPanel(Actions actions) {
        setLayout(null);
        setBackground(new Color(0x304B46));
        setPreferredSize(new Dimension(1280, 720));
        addButton("create-room", "방 만들기", 486, 305, 308, 63, actions::createRoom);
        addButton("join-room", "참여하기", 486, 379, 308, 63, actions::joinRoom);
        addButton("how-to-play", "게임 방법", 486, 453, 308, 63, actions::howToPlay);
        addButton("exit", "게임 종료", 486, 527, 308, 63, actions::exit);
        addButton("settings", "설정", 1160, 28, 92, 40, actions::settings);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "exit");
        getActionMap().put("exit", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { actions.exit(); }
        });
    }

    private void addButton(String asset, String label, int x, int y, int w, int h, Runnable action) {
        ImageButton button = new ImageButton(ImageAssets.load(asset), label);
        button.setName(asset);
        button.addActionListener(e -> action.run());
        positions.put(button, new Rectangle(x, y, w, h));
        add(button);
    }

    private double scale() { return Math.min(getWidth() / 1280.0, getHeight() / 720.0); }
    private int offsetX(double s) { return (int) Math.round((getWidth() - 1280 * s) / 2); }
    private int offsetY(double s) { return (int) Math.round((getHeight() - 720 * s) / 2); }

    @Override public void doLayout() {
        double s = scale();
        int ox = offsetX(s), oy = offsetY(s);
        positions.forEach((button, r) -> button.setBounds(
            ox + (int) Math.round(r.x * s), oy + (int) Math.round(r.y * s),
            (int) Math.round(r.width * s), (int) Math.round(r.height * s)));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        double s = scale();
        g.translate(offsetX(s), offsetY(s));
        g.scale(s, s);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(background, 0, 0, 1280, 720, null);
        g.drawImage(title, 330, 87, 620, 168, null);
        // Figma trims transparent padding when exporting an individual text node.
        g.drawImage(footer, (1280 - footer.getWidth()) / 2, 671, null);
        g.dispose();
    }
}
