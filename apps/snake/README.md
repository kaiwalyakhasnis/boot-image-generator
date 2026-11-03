# Snake — a 2D snake game as the phone's only app

The classic game, built as an out-of-tree embedded-compose app in the
shape of `apps/clock` and `apps/chessclock`: one screen, drawn with
Canvas, no image files.

Rules (as specified): the snake grows by one segment for every food it
eats; the game ends when the snake runs into itself or into any
boundary wall. Speed rises slightly with each food. Score = food eaten.

## Controls — the phone's buttons, mapped

The Nexus 5X has a touchscreen, two volume keys, and Power. All of
them are mapped:

- **Touch** — tap the side of the board you want to head toward
  (tap above the snake = up, below = down, left / right likewise).
- **VOL+** — turn right (clockwise). **VOL−** — turn left.
  Two buttons are enough to steer anywhere.
- **ENTER / POWER** — pause / resume while playing; ENTER starts a
  game from the title screen.
- **QEMU** — arrow keys steer directly; same ENTER/POWER rules.
- **Title screen** — big START GAME button (tap it, or ENTER).
- **Game over** — tap or ENTER plays again; POWER returns to title.

Idle on the title screen for ~6 s and a self-playing demo game starts
(the QEMU preview runs on it); any real key or tap takes over.

## Build

```sh
# from the embedded-compose repo root
APP_DIR=<forge>/apps/snake ./ec build nexus5x        # phone boot image
APP_DIR=<forge>/apps/snake ./ec build nexus5x-qemu   # QEMU variant
APP_DIR=<forge>/apps/snake ./ec record nexus5x-qemu  # preview video
```

Pairs with the clock-minimal kernel profile (trimmed LineageOS 3.10,
powersave governor, A53 capped at 600 MHz) like the other forge apps.
