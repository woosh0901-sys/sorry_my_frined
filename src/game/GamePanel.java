package game;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import main.ImageAssets;

/**
 * 게임 한 판을 그리는 화면. 위치와 판정 결과는 밖(로컬 테스트나 서버)에서 받아 그리기만 한다.
 * public 메서드는 네트워크 수신 스레드에서 바로 불러도 Swing 스레드로 넘겨 처리한다.
 */
@SuppressWarnings("serial")
public final class GamePanel extends JPanel {
    /** 화면에서 생긴 입력을 서버 연결 코드나 로컬 테스트로 넘긴다. */
    public interface Listener {
        void keyChanged(char key, boolean pressed);
        void exitRequested();
        default void nextRequested() { }
    }

    /** 오른쪽 담당 키 목록의 한 줄. player는 1~4이고 키캡 점 색을 정한다. */
    public record Slot(char key, String name, int player, boolean mine) { }

    private enum Facing { UP("up"), DOWN("down"), LEFT("left"), RIGHT("right");
        final String sprite;
        Facing(String sprite) { this.sprite = sprite; }
    }

    private static final int WIDTH = 1280, HEIGHT = 720;
    private static final Color OUTLINE = new Color(0x2F4A45), CREAM = new Color(0xFFF7E8);
    private static final Color TEAL = new Color(0x3E7A6A), PRESSED_CAP = new Color(0xEADCC0);
    private static final Color[] PLAYER = {
        new Color(0xF28B82), new Color(0x6EC1E4), new Color(0xF5C842), new Color(0x8CC57A)};
    private static final char[] KEYS = {'W', 'A', 'S', 'D'};
    private static final String[] DIRECTIONS = {"위로", "왼쪽", "아래로", "오른쪽"};
    private static final long MS = 1_000_000L;
    private static final Font JUA = loadFont();
    private static final Map<String, BufferedImage> IMAGES = new HashMap<>();
    private final Map<Float, Font> fonts = new HashMap<>();

    private final Listener listener;
    private final Timer frames = new Timer(16, event -> repaint());
    private final Set<Character> held = new HashSet<>();
    private final Set<Character> othersHeld = new HashSet<>();
    private final WindowAdapter focusWatcher = new WindowAdapter() {
        @Override public void windowLostFocus(WindowEvent event) { releaseAll(); }
    };
    private Window window;

    private Stage stage;
    private long stageStart;
    private double playerX, playerY;
    private long lastMove = Long.MIN_VALUE / 2;
    private Facing facing = Facing.UP;
    private double[] obstacleX = {}, obstacleY = {}, headingX = {}, headingY = {};
    private boolean[] coins = {};
    private int deaths;
    private long hitAt = Long.MIN_VALUE / 2;
    private long clearMillis = -1;
    private int clearDeaths;
    private boolean hasNext;
    private List<Slot> slots = defaultSlots();
    private int ping = -1;

    public GamePanel(Listener listener) {
        this.listener = listener;
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(OUTLINE);
        setFocusable(true);
        // 한글 입력 상태여도 W/A/S/D가 조합 문자로 바뀌지 않게 입력기를 끈다.
        enableInputMethods(false);
        bindKeys();
    }

    /** 스테이지를 처음부터 시작한다. 서버의 시작 신호(/START)를 받았을 때 부른다. */
    public void startStage(Stage next) {
        edt(() -> {
            stage = next;
            stageStart = System.nanoTime();
            int count = next.obstacles().size();
            obstacleX = new double[count];
            obstacleY = new double[count];
            headingX = new double[count];
            headingY = new double[count];
            for (int i = 0; i < count; i++) {
                Stage.Obstacle o = next.obstacles().get(i);
                obstacleX[i] = o.x1();
                obstacleY[i] = o.y1();
                headingX[i] = o.x2() - o.x1();
                headingY[i] = o.y2() - o.y1();
            }
            coins = new boolean[next.coins().size()];
            playerX = next.start().x();
            playerY = next.start().y();
            facing = Facing.UP;
            lastMove = Long.MIN_VALUE / 2;
            deaths = 0;
            hitAt = Long.MIN_VALUE / 2;
            clearMillis = -1;
            image(next.background());
        });
    }

    /** 담당 키 목록. mine이 true인 키가 화면 아래 "내 키"에 표시된다. */
    public void setPlayers(List<Slot> next) {
        List<Slot> copy = List.copyOf(next);
        edt(() -> slots = copy);
    }

    /** 서버가 계산한 캐릭터 발밑 좌표. 움직인 방향으로 상상부기가 돌아선다. */
    public void setPlayerPosition(double x, double y) {
        edt(() -> {
            double dx = x - playerX, dy = y - playerY;
            double distance = Math.hypot(dx, dy);
            // 출발점으로 되돌아가는 순간이동은 걷는 동작으로 보지 않는다.
            if (distance > 0.01 && distance < 60) {
                lastMove = System.nanoTime();
                if (Math.abs(dx) > Math.abs(dy)) facing = dx > 0 ? Facing.RIGHT : Facing.LEFT;
                else facing = dy > 0 ? Facing.DOWN : Facing.UP;
            }
            playerX = x;
            playerY = y;
        });
    }

    /** index번째 장애물(Stage.obstacles 순서)의 가운데 좌표. */
    public void setObstaclePosition(int index, double x, double y) {
        edt(() -> {
            if (index < 0 || index >= obstacleX.length) return;
            double dx = x - obstacleX[index], dy = y - obstacleY[index];
            if (Math.hypot(dx, dy) > 0.01) {
                headingX[index] = dx;
                headingY[index] = dy;
            }
            obstacleX[index] = x;
            obstacleY[index] = y;
        });
    }

    public void setCoinCollected(int index) {
        edt(() -> { if (index >= 0 && index < coins.length) coins[index] = true; });
    }

    /** 장애물에 맞아 출발점으로 돌아갔을 때. deaths는 지금까지 넘어진 횟수다. */
    public void playerHit(int totalDeaths) {
        edt(() -> {
            deaths = totalDeaths;
            hitAt = System.nanoTime();
        });
    }

    /** 다른 참가자가 키를 누르고 있는지 표시한다. 내 입력은 화면이 직접 표시한다. */
    public void setKeyHeld(char key, boolean pressed) {
        edt(() -> { if (pressed) othersHeld.add(key); else othersHeld.remove(key); });
    }

    public void setPing(int milliseconds) {
        edt(() -> ping = milliseconds);
    }

    /** 도착 결과를 띄운다. hasNextStage가 true면 Enter로 다음 스테이지를 요청할 수 있다. */
    public void showClear(long elapsedMillis, int totalDeaths, boolean hasNextStage) {
        edt(() -> {
            clearMillis = elapsedMillis;
            clearDeaths = totalDeaths;
            hasNext = hasNextStage;
        });
    }

    private static void edt(Runnable task) {
        if (EventQueue.isDispatchThread()) task.run();
        else EventQueue.invokeLater(task);
    }

    private static List<Slot> defaultSlots() {
        List<Slot> all = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) all.add(new Slot(KEYS[i], "나", i + 1, true));
        return all;
    }

    // ---- 입력 ----

    private void bindKeys() {
        int[] codes = {KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D};
        for (int i = 0; i < KEYS.length; i++) {
            char key = KEYS[i];
            bind(KeyStroke.getKeyStroke(codes[i], 0, false), "press " + key, () -> press(key, true));
            bind(KeyStroke.getKeyStroke(codes[i], 0, true), "release " + key, () -> press(key, false));
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exit", listener::exitRequested);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next", () -> {
            if (clearMillis >= 0 && hasNext) listener.nextRequested();
        });
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    // 키를 누르고 있으면 운영체제가 같은 입력을 반복해 보내므로, 상태가 바뀔 때만 전달한다.
    private void press(char key, boolean pressed) {
        if (pressed ? held.add(key) : held.remove(key)) listener.keyChanged(key, pressed);
    }

    // 창을 벗어나면 뗀 신호를 받지 못하므로, 누르고 있던 키를 모두 뗀 것으로 보낸다.
    private void releaseAll() {
        for (char key : held.toArray(new Character[0])) press(key, false);
    }

    @Override public void addNotify() {
        super.addNotify();
        frames.start();
        window = SwingUtilities.getWindowAncestor(this);
        if (window != null) window.addWindowFocusListener(focusWatcher);
        EventQueue.invokeLater(this::requestFocusInWindow);
    }

    @Override public void removeNotify() {
        frames.stop();
        releaseAll();
        if (window != null) window.removeWindowFocusListener(focusWatcher);
        window = null;
        super.removeNotify();
    }

    // ---- 그리기 ----

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (stage == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        double scale = Math.min(getWidth() / (double) WIDTH, getHeight() / (double) HEIGHT);
        g.translate((getWidth() - WIDTH * scale) / 2, (getHeight() - HEIGHT * scale) / 2);
        g.scale(scale, scale);
        g.clipRect(0, 0, WIDTH, HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        long now = System.nanoTime();
        g.drawImage(image(stage.background()), 0, 0, WIDTH, HEIGHT, null);
        paintCoins(g, now);
        paintPlayer(g, now);
        paintObstacles(g);
        paintHitEffect(g, now);
        paintHud(g, now);
        paintBanner(g, now);
        if (clearMillis >= 0) paintClear(g);
        g.dispose();
    }

    private void paintCoins(Graphics2D g, long now) {
        BufferedImage coin = image("coin");
        double bob = Math.sin(now / 250.0 / MS) * 3;
        for (int i = 0; i < coins.length; i++) {
            if (coins[i]) continue;
            Stage.Point p = stage.coins().get(i);
            g.drawImage(coin, (int) Math.round(p.x() - 18), (int) Math.round(p.y() - 18 + bob), 36, 44, null);
        }
    }

    private void paintPlayer(Graphics2D g, long now) {
        // 맞은 직후 무적 시간에는 깜빡여서 다시 맞지 않는다는 걸 보여 준다.
        boolean invincible = now - hitAt < (long) (GameWorld.INVINCIBLE_SECONDS * 1000) * MS;
        if (invincible && (now / (100 * MS)) % 2 == 0) return;
        g.setColor(new Color(47, 74, 69, 52));
        g.fill(new Ellipse2D.Double(playerX - 24, playerY - 6, 48, 11));

        BufferedImage sprite = image("bugi-" + facing.sprite);
        double height = 84, width = sprite.getWidth() * height / sprite.getHeight();
        // 걷는 동안 Step 1 → Idle → Step 2 → Idle 순서로 발 기준 좌우로 뒤뚱거린다.
        boolean walking = now - lastMove < 150 * MS;
        int frame = walking ? (int) ((now / (140 * MS)) % 4) : 1;
        double angle = frame == 0 ? -4 : frame == 2 ? 4 : 0;
        double lift = walking && frame % 2 == 0 ? 2 : 0;
        AffineTransform t = new AffineTransform();
        t.translate(playerX, playerY - lift);
        t.rotate(Math.toRadians(angle));
        t.translate(-width / 2, -height);
        t.scale(width / sprite.getWidth(), height / sprite.getHeight());
        g.drawImage(sprite, t, null);
    }

    private void paintObstacles(Graphics2D g) {
        for (int i = 0; i < obstacleX.length; i++) {
            Stage.Kind kind = stage.obstacles().get(i).kind();
            BufferedImage sprite = image(kind.sprite);
            // PNG는 Figma에서 2배로 내보냈으므로 절반이 화면 크기다.
            double width = sprite.getWidth() / 2.0 * kind.scale, height = sprite.getHeight() / 2.0 * kind.scale;
            AffineTransform t = new AffineTransform();
            t.translate(obstacleX[i], obstacleY[i]);
            // 레미콘은 뒤집히지 않게 좌우 반전만 하고, 철근은 날아가는 방향으로 돌린다.
            if (kind == Stage.Kind.REMICON) {
                if (headingX[i] < 0) t.scale(-1, 1);
            } else {
                t.rotate(Math.atan2(headingY[i], headingX[i]));
            }
            t.translate(-width / 2 - kind.centerOffset, -height / 2);
            t.scale(width / sprite.getWidth(), height / sprite.getHeight());
            g.drawImage(sprite, t, null);
        }
    }

    private void paintHitEffect(Graphics2D g, long now) {
        double t = (now - hitAt) / 1e9;
        if (t < 0.35) {
            g.setColor(new Color(242, 139, 130, (int) (90 * (1 - t / 0.35))));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }
        if (t < 0.9) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, (0.9 - t) / 0.3)));
            outlinedText(g, "넘어짐!", 26, PLAYER[0], playerX, playerY - 96 - t * 30);
            g.setComposite(old);
        }
    }

    // ---- HUD ----

    private void paintHud(Graphics2D g, long now) {
        // 넘어진 횟수
        double deathsWidth = 14 + 30 + 10 + width(g, "넘어짐", 17) + 10 + width(g, String.valueOf(deaths), 28) + 18;
        card(g, 20, 16, deathsWidth, 52, 26, CREAM, 4);
        g.drawImage(image("icon-bandage"), 34, 27, 30, 30, null);
        double x = 74 + text(g, "넘어짐", 17, TEAL, 74, 42) + 10;
        text(g, String.valueOf(deaths), 28, OUTLINE, x, 42);
        String status = ping >= 0 ? ping + " ms" : "로컬 테스트";
        double statusWidth = width(g, status, 14) + 30;
        pill(g, 26, 76, statusWidth, 22, new Color(47, 74, 69, 150));
        g.setColor(ping >= 0 ? new Color(0x5DBB63) : PLAYER[2]);
        g.fill(new Ellipse2D.Double(34, 83, 9, 9));
        text(g, status, 14, CREAM, 49, 87);

        // 타이머와 스테이지 이름
        String time = formatTime(clearMillis >= 0 ? clearMillis : (now - stageStart) / MS);
        double timerWidth = 12 + 32 + 10 + width(g, "00:00:00", 30) + 22;
        double timerX = (WIDTH - timerWidth) / 2;
        card(g, timerX, 14, timerWidth, 54, 27, CREAM, 4);
        g.drawImage(image("icon-stopwatch"), (int) timerX + 12, 24, 32, 34, null);
        text(g, time, 30, OUTLINE, timerX + 54, 41);
        String badge = "STAGE " + stage.number() + " · " + stage.title();
        double badgeWidth = width(g, badge, 15) + 28;
        pill(g, (WIDTH - badgeWidth) / 2, 78, badgeWidth, 26, new Color(255, 255, 255, 210));
        text(g, badge, 15, TEAL, (WIDTH - badgeWidth) / 2 + 14, 91);

        // 코인
        String coinText = collectedCoins() + " / " + coins.length;
        double coinWidth = 10 + 32 + 12 + width(g, coinText, 28) + 22;
        card(g, 1260 - coinWidth, 16, coinWidth, 52, 26, CREAM, 4);
        g.drawImage(image("icon-coin"), (int) (1270 - coinWidth), 26, 32, 32, null);
        text(g, coinText, 28, OUTLINE, 1260 - coinWidth + 54, 42);

        paintSlots(g);
        paintMyKeys(g);
        double hintWidth = width(g, "Esc 나가기", 14) + 20;
        pill(g, 1264 - hintWidth, 692, hintWidth, 22, new Color(47, 74, 69, 150));
        text(g, "Esc 나가기", 14, CREAM, 1274 - hintWidth, 703);
    }

    private void paintSlots(Graphics2D g) {
        double x = 1070, y = 86, w = 190, rowHeight = 48;
        double h = 12 + slots.size() * rowHeight;
        card(g, x, y, w, h, 18, new Color(255, 247, 232, 235), 4);
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            Color color = PLAYER[Math.floorMod(slot.player() - 1, PLAYER.length)];
            double rowY = y + 6 + i * rowHeight;
            boolean pressed = held.contains(slot.key()) && slot.mine() || othersHeld.contains(slot.key());
            if (slot.mine()) {
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 56));
                g.fill(new RoundRectangle2D.Double(x + 6, rowY + 2, w - 12, rowHeight - 4, 24, 24));
            }
            keycap(g, x + 14, rowY + 6, 34, slot.key(), color, pressed);
            double nameX = x + 60;
            double nameWidth = text(g, slot.name(), 18, OUTLINE, nameX, rowY + 19);
            if (slot.mine()) {
                double tagX = nameX + nameWidth + 6;
                pill(g, tagX, rowY + 8, 26, 21, color);
                g.setColor(OUTLINE);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new RoundRectangle2D.Double(tagX, rowY + 8, 26, 21, 21, 21));
                text(g, "나", 15, OUTLINE, tagX + (26 - width(g, "나", 15)) / 2, rowY + 18.5);
            }
            // 키를 누르고 있는 동안 막대가 채워진다.
            pill(g, nameX, rowY + 31, 104, 7, new Color(0xE6DCC8));
            if (pressed) pill(g, nameX, rowY + 31, 104, 7, color);
        }
    }

    private void paintMyKeys(Graphics2D g) {
        List<Slot> mine = slots.stream().filter(Slot::mine).toList();
        if (mine.isEmpty()) return;
        String label;
        if (mine.size() == KEYS.length) label = "모든 방향 담당";
        else if (mine.size() == 1) label = direction(mine.get(0).key()) + " 이동 담당";
        else label = String.join("·", mine.stream().map(slot -> direction(slot.key())).toList()) + " 담당";
        double caps = mine.size() * 34 + (mine.size() - 1) * 6;
        double w = 16 + width(g, "내 키", 18) + 10 + caps + 10 + width(g, label, 18) + 18;
        double x = (WIDTH - w) / 2, y = 664;
        card(g, x, y, w, 46, 23, CREAM, 4);
        double cx = x + 16 + text(g, "내 키", 18, TEAL, x + 16, y + 23) + 10;
        for (Slot slot : mine) {
            Color color = PLAYER[Math.floorMod(slot.player() - 1, PLAYER.length)];
            keycap(g, cx, y + 5, 34, slot.key(), color, held.contains(slot.key()));
            cx += 40;
        }
        text(g, label, 18, OUTLINE, cx + 4, y + 23);
    }

    private void paintBanner(Graphics2D g, long now) {
        double t = (now - stageStart) / 1e9;
        if (t > 1.8) return;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) (t < 1.4 ? 1 : (1.8 - t) / 0.4)));
        double w = Math.max(420, width(g, stage.title(), 44) + 80), x = (WIDTH - w) / 2;
        card(g, x, 250, w, 170, 28, CREAM, 6);
        centeredText(g, "STAGE " + stage.number(), 22, TEAL, 290);
        centeredText(g, stage.title(), 44, OUTLINE, 340);
        centeredText(g, "출발!", 24, PLAYER[0], 390);
        g.setComposite(old);
    }

    private void paintClear(Graphics2D g) {
        g.setColor(new Color(47, 74, 69, 120));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        double w = 520, h = 330, x = (WIDTH - w) / 2, y = (HEIGHT - h) / 2;
        card(g, x, y, w, h, 30, CREAM, 6);
        centeredText(g, "도착!", 54, OUTLINE, y + 66);
        centeredText(g, stage.number() == 2 ? "한성대 등교 성공" : "STAGE " + stage.number() + " 클리어", 22, TEAL, y + 112);
        String[][] rows = {
            {"icon-stopwatch", "기록", formatTime(clearMillis)},
            {"icon-bandage", "넘어짐", clearDeaths + "번"},
            {"icon-coin", "코인", collectedCoins() + " / " + coins.length}};
        for (int i = 0; i < rows.length; i++) {
            double rowY = y + 146 + i * 42;
            g.drawImage(image(rows[i][0]), (int) x + 140, (int) rowY, 30, 30, null);
            text(g, rows[i][1], 20, TEAL, x + 182, rowY + 15);
            text(g, rows[i][2], 24, OUTLINE, x + 280, rowY + 15);
        }
        centeredText(g, hasNext ? "Enter 다음 스테이지 · Esc 나가기" : "Esc 나가기", 17, TEAL, y + h - 26);
    }

    // ---- 그리기 도구 ----

    private void keycap(Graphics2D g, double x, double y, double size, char key, Color dot, boolean pressed) {
        double radius = size * 0.48, drop = size * 0.08, dy = pressed ? drop : 0;
        if (!pressed) {
            g.setColor(OUTLINE);
            g.fill(new RoundRectangle2D.Double(x, y + drop, size, size, radius, radius));
        }
        Shape cap = new RoundRectangle2D.Double(x, y + dy, size, size, radius, radius);
        g.setColor(pressed ? PRESSED_CAP : CREAM);
        g.fill(cap);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke((float) (size * 0.06)));
        g.draw(cap);
        String letter = String.valueOf(key);
        text(g, letter, (float) (size * 0.52), OUTLINE, x + (size - width(g, letter, (float) (size * 0.52))) / 2, y + dy + size / 2 + 1);
        double d = size * 0.2;
        Shape spot = new Ellipse2D.Double(x + size * 0.66, y + dy + size * 0.12, d, d);
        g.setColor(dot);
        g.fill(spot);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(spot);
    }

    private void card(Graphics2D g, double x, double y, double w, double h, double radius, Color fill, double shadow) {
        g.setColor(OUTLINE);
        g.fill(new RoundRectangle2D.Double(x, y + shadow, w, h, radius * 2, radius * 2));
        Shape shape = new RoundRectangle2D.Double(x, y, w, h, radius * 2, radius * 2);
        g.setColor(fill);
        g.fill(shape);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(3f));
        g.draw(shape);
    }

    private void pill(Graphics2D g, double x, double y, double w, double h, Color fill) {
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
    }

    /** centerY에 글자 높이 가운데를 맞춰 그리고, 그린 폭을 돌려준다. */
    private double text(Graphics2D g, String value, float size, Color color, double x, double centerY) {
        FontMetrics metrics = g.getFontMetrics(font(size));
        g.setColor(color);
        g.drawString(styled(value, size).getIterator(), (float) x,
            (float) (centerY + (metrics.getAscent() - metrics.getDescent()) / 2.0));
        return width(g, value, size);
    }

    // Jua에 없는 글자(가운뎃점 등)는 기본 글꼴로 바꿔 네모로 깨지지 않게 한다.
    private AttributedString styled(String value, float size) {
        AttributedString styled = new AttributedString(value);
        styled.addAttribute(TextAttribute.FONT, font(size));
        Font fallback = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size));
        for (int i = 0; i < value.length(); i++) {
            if (!font(size).canDisplay(value.charAt(i))) styled.addAttribute(TextAttribute.FONT, fallback, i, i + 1);
        }
        return styled;
    }

    private void centeredText(Graphics2D g, String value, float size, Color color, double centerY) {
        text(g, value, size, color, (WIDTH - width(g, value, size)) / 2, centerY);
    }

    private void outlinedText(Graphics2D g, String value, float size, Color color, double centerX, double centerY) {
        double x = centerX - width(g, value, size) / 2;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dy = -2; dy <= 2; dy += 2) if (dx != 0 || dy != 0) text(g, value, size, OUTLINE, x + dx, centerY + dy);
        }
        text(g, value, size, color, x, centerY);
    }

    private double width(Graphics2D g, String value, float size) {
        if (value.isEmpty()) return 0;
        return new TextLayout(styled(value, size).getIterator(), g.getFontRenderContext()).getAdvance();
    }

    private Font font(float size) {
        return fonts.computeIfAbsent(size, JUA::deriveFont);
    }

    private int collectedCoins() {
        int count = 0;
        for (boolean coin : coins) if (coin) count++;
        return count;
    }

    private static String direction(char key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == key) return DIRECTIONS[i];
        return String.valueOf(key);
    }

    private static String formatTime(long millis) {
        return String.format("%02d:%02d:%02d", millis / 60000, millis / 1000 % 60, millis / 10 % 100);
    }

    private static BufferedImage image(String name) {
        return IMAGES.computeIfAbsent(name, key -> ImageAssets.load("game/" + key));
    }

    private static Font loadFont() {
        try (InputStream stream = GamePanel.class.getResourceAsStream("/fonts/Jua-Regular.ttf")) {
            if (stream != null) return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (IOException | FontFormatException ignored) {
            // 글꼴을 못 읽어도 기본 글꼴로 게임은 진행할 수 있다.
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 12);
    }
}
