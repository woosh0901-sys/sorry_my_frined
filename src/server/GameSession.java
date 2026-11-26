package server;

import game.GameWorld;
import game.Stage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 서버가 한 스테이지의 입력과 판정을 관리한다. */
final class GameSession {
    record Player(int id, String name) { }

    private static final String KEYS = "WASD";
    private final GameWorld world;
    private final List<Player> players;
    private final int[] owners;
    private final Set<Character> held = new HashSet<>();
    private final boolean[] reportedCoins;

    GameSession(Stage stage, List<Player> players) {
        this.players = List.copyOf(players);
        owners = switch (players.size()) {
            case 2 -> new int[] {0, 1, 0, 1};
            case 3 -> new int[] {0, 1, 2, 2};
            case 4 -> new int[] {0, 1, 2, 3};
            default -> throw new IllegalArgumentException("게임에는 2~4명이 필요합니다.");
        };
        world = new GameWorld(stage);
        reportedCoins = new boolean[stage.coins().size()];
    }

    List<String> startMessages() {
        List<String> messages = new ArrayList<>();
        messages.add("/START " + world.stage().number());
        for (int i = 0; i < KEYS.length(); i++) {
            int seat = owners[i];
            Player player = players.get(seat);
            messages.add("/SLOT " + KEYS.charAt(i) + " " + (seat + 1) + " " + player.id() + " " + player.name());
        }
        return messages;
    }

    String changeKey(int playerId, char key, boolean pressed) {
        int index = KEYS.indexOf(key);
        if (world.cleared() || index < 0 || players.get(owners[index]).id() != playerId) return null;
        boolean changed = pressed ? held.add(key) : held.remove(key);
        return changed ? heldMessage(key, pressed) : null;
    }

    Stage nextStage() {
        return world.cleared() ? Stage.of(world.stage().number() + 1) : null;
    }

    List<String> step(double seconds) {
        if (world.cleared()) return List.of();
        int previousDeaths = world.deaths();
        world.step(seconds, held);

        List<String> messages = new ArrayList<>();
        messages.add("/POS " + world.x() + " " + world.y());
        for (int i = 0; i < world.stage().obstacles().size(); i++) {
            messages.add("/OBS " + i + " " + world.obstacleX(i) + " " + world.obstacleY(i));
        }
        for (int i = 0; i < reportedCoins.length; i++) {
            if (!reportedCoins[i] && world.coinCollected(i)) {
                reportedCoins[i] = true;
                messages.add("/COIN " + i);
            }
        }
        if (world.deaths() != previousDeaths) messages.add("/HIT " + world.deaths());
        if (world.cleared()) {
            for (char key : held) messages.add(heldMessage(key, false));
            held.clear();
            messages.add("/CLEAR " + world.elapsedMillis() + " " + world.deaths());
        }
        return messages;
    }

    private static String heldMessage(char key, boolean pressed) {
        return "/HELD " + key + (pressed ? " 1" : " 0");
    }
}
