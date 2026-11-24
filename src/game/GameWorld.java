package game;

import java.util.Arrays;
import java.util.Set;

/**
 * 한 판의 규칙(이동, 벽, 장애물, 코인, 도착)을 계산한다.
 * Swing을 쓰지 않으므로 서버에서도 그대로 만들어 같은 판정에 쓸 수 있다.
 */
public final class GameWorld {
    public static final double SPEED = 170;
    public static final double INVINCIBLE_SECONDS = 1.2;
    // 벽은 발밑 상자로, 장애물은 몸통 상자로 판정한다.
    private static final double FOOT_HALF_WIDTH = 13, FOOT_HEIGHT = 14;
    private static final double BODY_HALF_WIDTH = 16, BODY_HEIGHT = 50;
    private static final double COIN_RADIUS = 30;

    private final Stage stage;
    private double x, y;
    private final double[] travelled;
    private final boolean[] forward;
    private final boolean[] coins;
    private int deaths;
    private double elapsed, invincible;
    private boolean cleared;

    public GameWorld(Stage stage) {
        this.stage = stage;
        x = stage.start().x();
        y = stage.start().y();
        travelled = new double[stage.obstacles().size()];
        forward = new boolean[travelled.length];
        Arrays.fill(forward, true);
        coins = new boolean[stage.coins().size()];
    }

    /** dt초만큼 진행한다. held에는 지금 눌려 있는 키(W/A/S/D)가 모두 들어 있다. */
    public void step(double dt, Set<Character> held) {
        if (cleared) return;
        elapsed += dt;
        invincible = Math.max(0, invincible - dt);
        for (int i = 0; i < travelled.length; i++) moveObstacle(i, dt);

        double dx = (held.contains('D') ? 1 : 0) - (held.contains('A') ? 1 : 0);
        double dy = (held.contains('S') ? 1 : 0) - (held.contains('W') ? 1 : 0);
        double length = Math.hypot(dx, dy);
        if (length > 0) {
            double distance = SPEED * dt / length;
            // 축을 나눠 움직이면 벽에 비스듬히 닿아도 벽을 따라 미끄러진다.
            if (walkable(x + dx * distance, y)) x += dx * distance;
            if (walkable(x, y + dy * distance)) y += dy * distance;
        }

        for (int i = 0; i < coins.length; i++) {
            Stage.Point coin = stage.coins().get(i);
            if (!coins[i] && Math.hypot(coin.x() - x, coin.y() - (y - 20)) < COIN_RADIUS) coins[i] = true;
        }
        if (invincible == 0 && hitsObstacle()) {
            deaths++;
            invincible = INVINCIBLE_SECONDS;
            x = stage.start().x();
            y = stage.start().y();
        }
        if (stage.finish().contains(x, y)) cleared = true;
    }

    private void moveObstacle(int i, double dt) {
        Stage.Obstacle obstacle = stage.obstacles().get(i);
        double length = obstacle.length();
        travelled[i] += (forward[i] ? 1 : -1) * obstacle.speed() * dt;
        if (travelled[i] >= length) {
            travelled[i] = Math.max(0, 2 * length - travelled[i]);
            forward[i] = false;
        } else if (travelled[i] <= 0) {
            travelled[i] = Math.min(length, -travelled[i]);
            forward[i] = true;
        }
    }

    private boolean walkable(double px, double py) {
        for (double cx : new double[] {px - FOOT_HALF_WIDTH, px, px + FOOT_HALF_WIDTH}) {
            for (double cy : new double[] {py - FOOT_HEIGHT, py}) {
                if (stage.paths().stream().noneMatch(area -> area.contains(cx, cy))) return false;
            }
        }
        return true;
    }

    private boolean hitsObstacle() {
        for (int i = 0; i < travelled.length; i++) {
            Stage.Obstacle obstacle = stage.obstacles().get(i);
            boolean horizontal = obstacle.horizontal();
            double halfWidth = (horizontal ? obstacle.kind().length : obstacle.kind().thickness) / 2;
            double halfHeight = (horizontal ? obstacle.kind().thickness : obstacle.kind().length) / 2;
            double ox = obstacleX(i), oy = obstacleY(i);
            if (x + BODY_HALF_WIDTH > ox - halfWidth && x - BODY_HALF_WIDTH < ox + halfWidth
                    && y > oy - halfHeight && y - BODY_HEIGHT < oy + halfHeight) return true;
        }
        return false;
    }

    public Stage stage() { return stage; }
    public double x() { return x; }
    public double y() { return y; }
    public int deaths() { return deaths; }
    public boolean cleared() { return cleared; }
    public long elapsedMillis() { return Math.round(elapsed * 1000); }
    public boolean coinCollected(int i) { return coins[i]; }

    public int coinsCollected() {
        int count = 0;
        for (boolean coin : coins) if (coin) count++;
        return count;
    }

    public double obstacleX(int i) {
        Stage.Obstacle o = stage.obstacles().get(i);
        return o.x1() + (o.x2() - o.x1()) * travelled[i] / o.length();
    }

    public double obstacleY(int i) {
        Stage.Obstacle o = stage.obstacles().get(i);
        return o.y1() + (o.y2() - o.y1()) * travelled[i] / o.length();
    }
}
