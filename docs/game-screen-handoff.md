# 게임 화면 인수인계 (신진용 → 우서현)

화면과 조작(Swing)은 끝났고, 서버 없이 임시 좌표로 돌려 볼 수 있습니다.
서버 쪽에서는 아래 "서현이 할 일"만 연결하면 두 클라이언트에서 같은 캐릭터가 움직입니다.

## 바로 실행해 보기 (서버 없이)

- Eclipse: `src/game/LocalGame.java` 실행 (Run As → Java Application). 인자에 `2`를 주면 스테이지 2부터 시작.
- 터미널:
  ```
  javac -encoding UTF-8 -d bin $(find src -name '*.java')
  java -cp bin:resources game.LocalGame      # Windows는 bin;resources
  ```
- 조작: W/A/S/D 이동, Esc 나가기, 도착 후 Enter 다음 스테이지.

## 파일 구성

| 파일 | 역할 | 서버에서 쓰나 |
|---|---|---|
| `game/Stage.java` | 길·도착 구역·코인·장애물 좌표 (Figma 좌표 그대로) | O |
| `game/GameWorld.java` | 이동·벽·장애물 충돌·코인·도착 판정. Swing 미사용 | O (그대로 가져다 쓰면 됨) |
| `game/GamePanel.java` | 화면 그리기 + W/A/S/D 입력 | X (클라이언트) |
| `game/LocalGame.java` | 서버 대신 GameWorld를 돌려 GamePanel에 넣는 임시 모드 | X (서버 붙인 뒤엔 테스트용) |
| `resources/images/game/` | Figma에서 내보낸 배경(2배)·상상부기·장애물·아이콘 | |
| `resources/fonts/Jua-Regular.ttf` | HUD 글꼴 (OFL 라이선스, `OFL.txt`) | |

`LocalGame.push()`가 서버가 할 일의 견본입니다. 같은 값을 메시지로 보내고 클라이언트에서 같은 메서드를 부르면 됩니다.

## 좌표 기준

- 화면 1280×720 px, 40px 타일. 창 크기가 달라도 이 좌표계로 그립니다.
- 캐릭터 좌표 = **발밑 가운데**. 장애물 좌표 = 몸체 가운데.
- 벽 판정은 발밑 상자(26×14), 장애물 판정은 몸통 상자(32×50)로 합니다.
- 장애물은 `Stage.obstacles` 순서대로 번호(0부터)를 씁니다.

## 이미 연결된 것 (클라이언트)

- 서버가 `/START <스테이지번호>`를 보내면 대기실이 게임 화면으로 바뀝니다 (`Main.onGameMessage`).
  - 슬래시(`/`)로 시작하는 서버 메시지는 채팅창에 안 찍히고 `Main.onGameMessage`로 갑니다.
- 키를 누르고 뗄 때 서버로 `/KEY W DOWN`, `/KEY W UP`을 보냅니다 (`JavaChatClientView.sendKey`).
  - 키를 꾹 누르고 있어도 DOWN은 한 번만 갑니다. 창 포커스를 잃으면 누르던 키의 UP을 보냅니다.

## 서현이 할 일 (제안 — 같이 확정해요)

서버 → 클라이언트 메시지 제안:

| 메시지 | 클라이언트에서 부를 메서드 |
|---|---|
| `/START 1` | (연결됨) 게임 화면 전환 |
| `/SLOT W 1 신진용` (키, 플레이어번호, 닉네임) | 모아서 `setPlayers(...)`, 내 닉네임이면 `mine=true` |
| `/POS x y` | `setPlayerPosition(x, y)` |
| `/OBS i x y` | `setObstaclePosition(i, x, y)` |
| `/HELD W 1` / `/HELD W 0` | `setKeyHeld('W', true/false)` (다른 사람이 누르는 중 표시) |
| `/COIN i` | `setCoinCollected(i)` |
| `/HIT 3` (지금까지 넘어진 횟수) | `playerHit(3)` |
| `/CLEAR 83450 3` (ms, 넘어짐) | `showClear(83450, 3, false)` |

서버 쪽 흐름:
1. 방장이 게임 시작을 요청하면 조건 확인 후 참가자별 담당 키를 정하고 `/SLOT`들과 `/START`를 보냅니다.
2. `/KEY` 를 받으면 그 사람 담당 키인지 확인하고, 맞으면 눌린 키 집합에 넣고/뺍니다.
3. 서버 타이머(예: 30Hz)로 `world.step(dt, 눌린키집합)` → `/POS`, `/OBS`… 를 전원에게 보냅니다.
   충돌·도착은 `GameWorld`가 판정하므로 모든 클라이언트가 같은 결과를 봅니다.
4. 클라이언트 `Main.onGameMessage`에 위 표대로 분기를 추가합니다. `GamePanel` 메서드는
   네트워크 스레드에서 불러도 안전합니다(내부에서 Swing 스레드로 넘김).

> 장애물 움직임도 서버(`GameWorld`)가 계산해서 보내야 화면과 충돌 판정이 어긋나지 않습니다.

## 아직 안 한 것

- 대기실의 "게임 시작" 버튼 (방장만 보이게) — 요청 메시지 이름 정해지면 붙이겠습니다.
- 2인일 때 키 배정 방식(한 사람이 두 키씩 등) 확정.
