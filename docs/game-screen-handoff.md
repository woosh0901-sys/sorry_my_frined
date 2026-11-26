# 게임 화면 인수인계 (신진용 → 우서현)

1·2스테이지의 화면과 판정 코드를 멀티플레이 서버에 연결했습니다.
멀티플레이는 `main.Main`을 실행하고 한 명이 방을 만든 뒤, 총 2~4명이 모이면 방장이 **게임 시작**을 누릅니다.
`game.LocalGame`은 서버 없이 스테이지를 확인할 때 실행합니다.

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

`LocalGame.push()`는 계산 결과를 바로 화면에 전달합니다. 멀티플레이에서는 서버의 `GameSession`이 같은 `GameWorld`를 실행하고 결과를 메시지로 전달합니다.

## 좌표 기준

- 화면 1280×720 px, 40px 타일. 창 크기가 달라도 이 좌표계로 그립니다.
- 캐릭터 좌표 = **발밑 가운데**. 장애물 좌표 = 몸체 가운데.
- 벽 판정은 발밑 상자(26×14), 장애물 판정은 몸통 상자(32×50)로 합니다.
- 장애물은 `Stage.obstacles` 순서대로 번호(0부터)를 씁니다.

## 클라이언트 → 서버

- `/START`는 방장의 시작 요청입니다. 서버는 2~4명이 대기 중일 때 승인합니다.
- `/KEY W DOWN`, `/KEY W UP`은 키 상태 변경입니다. 서버는 해당 키를 배정받은 참가자의 입력을 받아들입니다.
- `/NEXT`는 다음 스테이지 요청입니다. 1스테이지를 클리어한 뒤 참가자가 Enter를 누르면 전원이 2스테이지로 넘어갑니다.

## 서버 → 클라이언트

| 메시지 | 클라이언트에서 부를 메서드 |
|---|---|
| `/ROOM 1 2` (방장 ID, 인원) | 대기실 인원 표시와 방장 시작 버튼 갱신 |
| `/START 1` | 게임 화면 전환 및 스테이지 초기화 |
| `/SLOT W 1 1 신진용` (키, 화면 슬롯 번호, 참가자 ID, 닉네임) | `setPlayers(...)`에 전달하고, 참가자 ID로 내 키 확인 |
| `/POS x y` | `setPlayerPosition(x, y)` |
| `/OBS i x y` | `setObstaclePosition(i, x, y)` |
| `/HELD W 1` / `/HELD W 0` | `setKeyHeld('W', true/false)` (다른 사람이 누르는 중 표시) |
| `/COIN i` | `setCoinCollected(i)` |
| `/HIT 3` (지금까지 넘어진 횟수) | `playerHit(3)` |
| `/CLEAR 83450 3` (ms, 넘어짐) | `showClear(...)`로 결과 표시 |
| `/LOBBY` | 진행 중 참가자가 나가면 남은 참가자를 대기실로 복귀시킴 |
| `/ERROR HOST_ONLY` 등 | 시작 요청이 거절된 이유 표시 |

서버는 참가 순서대로 키를 배정합니다. 2명은 `W/S`와 `A/D`, 3명은 `W`, `A`, `S/D`, 4명은 `W`, `A`, `S`, `D`를 각각 맡습니다. 서버가 약 30Hz로 `GameWorld`를 진행하고, 화면은 서버에서 받은 좌표와 판정 결과를 표시합니다.
