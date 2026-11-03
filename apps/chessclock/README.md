# Chess Clock — an embedded-compose app for the Nexus 5X

Turns the phone into a dedicated chess clock: a boot image whose only job is
two clocks, a format carousel, and nothing else.

## Formats

Browse in SETUP with VOL/arrows or by tapping the top/bottom half:

BULLET 1+0 · BLITZ 3+0 · BLITZ 3+2 · BLITZ 5+0 · BLITZ 5+3 (default) ·
RAPID 10+0 · RAPID 15+10 · CLASSICAL 30+0 · CLASSICAL 90+30 ·
HANDICAP 10 vs 5 (White gets double time — odds games) · CUSTOM.

CUSTOM is an editor, not a single number: **per-side minutes** (White and
Black can differ), **Fischer increment** (banked on every press) and a
**Bronstein delay** (the first N seconds of every turn are free). Fields with
ENTER, values with VOL, POWER goes back.

## Playing

- Press your clock by **tapping your half** of the screen, or with the
  hardware keys: **VOL+ = White pressed, VOL- = Black pressed**
  (QEMU keyboard: LEFT = White, RIGHT = Black).
- The running side is lit (cream for White, blue for Black) with a green
  TO MOVE dot; a bar along the centre edge drains with your remaining time.
- Under 60 s the display gains tenths; under 10 s the active clock pulses red.
- **ENTER or POWER pauses.** While paused the arbiter can gift time:
  VOL+ gives White +10 s, VOL- gives Black +10 s; ENTER resumes,
  POWER abandons the game back to SETUP.
- Flag fall freezes the fallen clock at 0:00.0, flashes its panel red and
  declares the winner. Any key returns to SETUP.
- Nobody around? Leave it on SETUP for 9 s and it starts a **self-playing
  demo game** (5+3, presses every ~2.3 s) — handy on a shelf, and it is what
  the QEMU preview records. Any real key or tap takes over instantly.

## Reset

There is no single "reset button" to hit by accident mid-game: reset is
POWER while paused (two deliberate presses: pause, then abandon), or any key
after a flag fall. A running game can never be reset by one stray press.

## Build

Built like the Clock app, with the clock-minimal kernel profile:

    APP_DIR=<forge>/apps/chessclock ./ec build nexus5x-qemu   # QEMU preview
    APP_DIR=<forge>/apps/chessclock ./ec build nexus5x        # phone boot.img
    fastboot boot dist/nexus5x-chessclock-boot.img            # RAM-boot only
