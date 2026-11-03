# clock: a phone that is only a clock

An out-of-tree app for [embedded-compose](https://github.com/kaiwalyakhasnis/embedded-compose),
in the same shape as its `apps/demo` and `apps/matrix` (whose clock page this
one grows out of): one static binary, Compose runtime, drawn on `Canvas`
straight to the framebuffer via minui. No Android, no JVM.

**On screen:** the time, huge, with a seconds ring that sweeps once a
minute; the date; the alarm; the battery %. A soft glow breathes behind the
ring, the digits fade in at boot while the ring sweeps up to the current
second, and a ringing alarm pulses amber rings and flashes the digits.

**Setting it** (the phone's only keys are volume and power; in QEMU use the
arrow keys + Enter):

| Key | SHOW mode | SET mode |
|---|---|---|
| VOL + / VOL − / ENTER | enter SET | next / previous field |
| ↑ / ↓ (QEMU) | — | adjust the field |
| POWER (short) | — | back to SHOW |
| any key | stop a ringing alarm | — |

Fields: hour, minute, alarm hour, alarm minute, alarm on/off. The edited
field is underlined and blinks with the colon.

**Minimal processing is the point.** In SHOW mode the app repaints once per
second, aligned to the wall-clock tick (the matrix clock's pattern); the
60 fps frame clock runs only during the 2.5 s boot intro and while the alarm
rings. Kernel-side, pair it with
[`profiles/clock-minimal`](../../profiles/clock-minimal/): radios and the
modem compiled out, A57 cluster parked, A53s capped at 600 MHz, backlight
low. On the Nexus 5X's IPS panel the backlight is the power story — black
pixels save nothing (see nexus5x-hike-buddy's hardware notes).

Two honest limits:

- "Set time" sets an *offset* on the RTC, held in RAM. Like Hike Buddy's
  clock, it is lost on reboot — userdata is encrypted and `/tmp` is the
  only writable storage in these RAM-booted images. Setting the RTC itself
  belongs in init, before the app starts.
- The alarm is a state in the app: it rings for the alarm minute (any key
  dismisses), and only while the clock image is booted, obviously.

## Build

Inside a checkout of embedded-compose (the app lives outside that repo,
which `APP_DIR` supports):

```sh
APP_DIR=<path-to>/nexus5x-forge/apps/clock ./ec build nexus5x      # real phone image
APP_DIR=<path-to>/nexus5x-forge/apps/clock ./ec build qemu-virt   # QEMU preview image
./ec run qemu-virt        # mouse = touch, arrows/Enter = keys
./ec record qemu-virt clock.gif
adb reboot bootloader && fastboot boot out/nexus5x/boot.img   # RAM only, nothing flashed
```

Or run `profiles/clock-minimal/build.sh`, which also builds the kernel with
the clock's config diet first.
