package game;

import java.util.List;

/**
 * 스테이지 한 판의 좌표 정보. 단위는 1280×720 화면 기준 px이고 Figma 시안 좌표와 같다.
 * 캐릭터 좌표는 발밑 가운데를 기준으로 한다. 화면과 판정(서버)이 같은 값을 쓴다.
 */
public record Stage(int number, String title, String background, List<Area> paths, Area finish,
                    Point start, List<Point> coins, List<Obstacle> obstacles) {
    public static final int TILE = 40;

    public record Point(double x, double y) { }

    public record Area(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    /** 장애물 종류. length·thickness는 이동 방향 기준 충돌 상자 크기다. */
    public enum Kind {
        REMICON("remicon", 0.72, 84, 40, 10),
        REBAR("rebar", 0.68, 96, 22, 7);

        public final String sprite;
        public final double scale, length, thickness;
        /** 그림 속 속도선 때문에 몸체가 오른쪽으로 치우친 만큼의 보정값. */
        public final double centerOffset;

        Kind(String sprite, double scale, double length, double thickness, double centerOffset) {
            this.sprite = sprite;
            this.scale = scale;
            this.length = length;
            this.thickness = thickness;
            this.centerOffset = centerOffset;
        }
    }

    /** (x1, y1)에서 출발해 (x2, y2)까지 왕복하는 장애물. speed는 초당 px. */
    public record Obstacle(Kind kind, double x1, double y1, double x2, double y2, double speed) {
        public double length() { return Math.hypot(x2 - x1, y2 - y1); }
        public boolean horizontal() { return Math.abs(x2 - x1) >= Math.abs(y2 - y1); }
    }

    private static Area tiles(int x, int y, int w, int h) {
        return new Area(x * TILE, y * TILE, w * TILE, h * TILE);
    }

    public static final Stage STAGE_1 = new Stage(1, "나무 가이드라인 길", "stage1",
        List.of(tiles(2, 4, 3, 14), tiles(2, 4, 25, 3), tiles(8, 1, 3, 3), tiles(24, 4, 3, 8),
            tiles(9, 9, 18, 3), tiles(9, 9, 3, 8), tiles(9, 14, 15, 3)),
        new Area(896, 560, 64, 120), new Point(140, 620),
        List.of(new Point(380, 95), new Point(410, 622)),
        List.of(
            new Obstacle(Kind.REBAR, 140, 200, 140, 470, 130),
            new Obstacle(Kind.REMICON, 300, 220, 720, 220, 150),
            new Obstacle(Kind.REBAR, 1000, 236, 790, 236, 190),
            new Obstacle(Kind.REBAR, 1020, 200, 1020, 450, 170),
            new Obstacle(Kind.REMICON, 1010, 420, 770, 420, 150),
            new Obstacle(Kind.REBAR, 460, 410, 700, 410, 170),
            new Obstacle(Kind.REBAR, 420, 400, 420, 560, 140),
            new Obstacle(Kind.REMICON, 520, 622, 860, 622, 160)));

    public static final Stage STAGE_2 = new Stage(2, "한성대 가는 길", "stage2",
        List.of(tiles(6, 14, 4, 3), tiles(7, 3, 3, 14), tiles(7, 3, 9, 3), tiles(13, 3, 3, 14),
            tiles(13, 12, 8, 3), tiles(18, 3, 3, 12), tiles(18, 3, 8, 3), tiles(23, 3, 3, 14),
            tiles(23, 14, 5, 3), tiles(10, 1, 3, 2)),
        new Area(1036, 560, 84, 120), new Point(330, 655),
        List.of(new Point(460, 74), new Point(580, 630)),
        List.of(
            new Obstacle(Kind.REBAR, 340, 170, 340, 500, 150),
            new Obstacle(Kind.REMICON, 330, 182, 600, 182, 150),
            new Obstacle(Kind.REBAR, 580, 520, 580, 170, 170),
            new Obstacle(Kind.REMICON, 560, 545, 820, 545, 160),
            new Obstacle(Kind.REBAR, 780, 170, 780, 540, 180),
            new Obstacle(Kind.REMICON, 1000, 182, 760, 182, 160),
            new Obstacle(Kind.REBAR, 980, 540, 980, 170, 190)));

    private static final List<Stage> ALL = List.of(STAGE_1, STAGE_2);

    /** 번호에 맞는 스테이지. 없으면 null. */
    public static Stage of(int number) {
        return number >= 1 && number <= ALL.size() ? ALL.get(number - 1) : null;
    }
}
