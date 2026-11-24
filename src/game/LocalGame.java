package game;

import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JFrame;
import javax.swing.Timer;

/**
 * 서버 없이 화면과 조작을 확인하는 임시 모드. GameWorld를 이 컴퓨터에서 돌려 결과를 GamePanel에 넣는다.
 * 서버가 붙으면 같은 GameWorld를 서버가 돌리고, 서버 메시지로 GamePanel의 같은 메서드를 부르면 된다.
 */
public final class LocalGame implements GamePanel.Listener {
    private final GamePanel panel = new GamePanel(this);
    private final Set<Character> held = new HashSet<>();
    private final Timer ticker = new Timer(16, event -> tick());
    private final Runnable onExit;
    private GameWorld world;
    private long last;
    private int shownDeaths;
    private boolean shownClear;

    public LocalGame(Stage stage, Runnable onExit) {
        this.onExit = onExit;
        start(stage);
        ticker.start();
    }

    public GamePanel panel() { return panel; }

    public void stop() { ticker.stop(); }

    private void start(Stage stage) {
        world = new GameWorld(stage);
        shownDeaths = 0;
        shownClear = false;
        last = System.nanoTime();
        panel.startStage(stage);
        panel.setPlayers(List.of(
            new GamePanel.Slot('W', "나", 1, true), new GamePanel.Slot('A', "나", 2, true),
            new GamePanel.Slot('S', "나", 3, true), new GamePanel.Slot('D', "나", 4, true)));
        push();
    }

    private void tick() {
        long now = System.nanoTime();
        world.step(Math.min(0.05, (now - last) / 1e9), held);
        last = now;
        push();
    }

    // 서버가 보낼 메시지와 같은 내용을 GamePanel에 넣는다.
    private void push() {
        panel.setPlayerPosition(world.x(), world.y());
        for (int i = 0; i < world.stage().obstacles().size(); i++) {
            panel.setObstaclePosition(i, world.obstacleX(i), world.obstacleY(i));
        }
        for (int i = 0; i < world.stage().coins().size(); i++) {
            if (world.coinCollected(i)) panel.setCoinCollected(i);
        }
        if (world.deaths() != shownDeaths) {
            shownDeaths = world.deaths();
            panel.playerHit(shownDeaths);
        }
        if (world.cleared() && !shownClear) {
            shownClear = true;
            panel.showClear(world.elapsedMillis(), world.deaths(), Stage.of(world.stage().number() + 1) != null);
        }
    }

    @Override public void keyChanged(char key, boolean pressed) {
        if (pressed) held.add(key);
        else held.remove(key);
    }

    @Override public void exitRequested() {
        stop();
        onExit.run();
    }

    @Override public void nextRequested() {
        Stage next = Stage.of(world.stage().number() + 1);
        if (next != null) start(next);
    }

    /** 인자로 스테이지 번호(1 또는 2)를 줄 수 있다. */
    public static void main(String[] args) {
        Stage stage = Stage.of(args.length > 0 ? Integer.parseInt(args[0]) : 1);
        EventQueue.invokeLater(() -> {
            JFrame frame = new JFrame("친구야 미안해 · 로컬 테스트");
            LocalGame game = new LocalGame(stage == null ? Stage.STAGE_1 : stage, frame::dispose);
            frame.setContentPane(game.panel());
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { game.stop(); }
            });
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
