package main;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("serial")
final class MainMenuPanel extends JPanel {
    // 패널은 버튼을 그려 주고, 누른 뒤의 동작은 Main에서 전달받는다.
    interface Actions {
        void createRoom();
        void joinRoom();
        void howToPlay();
        void settings();
        void exit();
    }

    private final BufferedImage background = ImageAssets.load("background");
    // Figma 6:2의 배경 장식. PNG 내보내기에서 잘린 여백을 반영한 좌표다.
    private final List<Artwork> backgroundArt = List.of(
        new Artwork("station", 0, 220),
        new Artwork("village-bus", 926, 328),
        new Artwork("bugi-shadow", 1104, 680),
        new Artwork("bugi", 1098, 490),
        new Artwork("station-line", 45, 249),
        new Artwork("station-name", 84, 236),
        new Artwork("station-subtitle", 86, 265),
        new Artwork("bus-route", 1068, 406),
        new Artwork("bus-label", 1152, 407),
        new Artwork("wasd", 874, 556)
    );
    private final BufferedImage title = ImageAssets.load("title");
    private final BufferedImage footer = ImageAssets.load("footer");
    private final Map<JButton, Rectangle> positions = new LinkedHashMap<>();

    MainMenuPanel(Actions actions) {
        setLayout(null);
        setBackground(new Color(0xF2F7F0));
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

    private record Artwork(BufferedImage image, int x, int y) {
        Artwork(String asset, int x, int y) {
            this(ImageAssets.load(asset), x, y);
        }

        void paint(Graphics2D g) {
            g.drawImage(image, x, y, null);
        }
    }

    // 배경과 버튼에 같은 배율을 적용해 창 크기를 바꿔도 위치가 맞도록 한다.
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
        backgroundArt.forEach(artwork -> artwork.paint(g));
        g.drawImage(title, 330, 87, 620, 168, null);
        // Figma trims transparent padding when exporting an individual text node.
        g.drawImage(footer, (1280 - footer.getWidth()) / 2, 671, null);
        g.dispose();
    }
}
