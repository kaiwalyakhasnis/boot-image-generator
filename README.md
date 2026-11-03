# Boot Image Generator — pick a phone, a kernel, a config, an app; get a boot image

A workflow assembled entirely from [KK's own GitHub projects](https://github.com/kaiwalyakhasnis),
turning a drawer-full of Nexus 5X experiments into one pipeline:

```
DEVICE → KERNEL → CONFIG (granular) → APP → QEMU PREVIEW → BOOT IMAGE (fastboot boot)
```

Nothing is ever flashed. Every image boots from RAM with `fastboot boot`;
a reboot returns the phone to its installed system. That is the safety
model of every source project below, and this workflow keeps it.

## Use it with Muse (it's a skill)

This repo is an agent skill: the whole workflow lives in
[SKILL.md](SKILL.md), with `catalog.json`, the apps and the kernel
profiles as its data. To use it:

1. Give this repo to your Muse — upload the zip in chat, or paste the
   repo link once it's public — and say: *"Install this skill for me."*
   Your Muse saves it into its custom skills, so it stays available in
   future chats. (For a one-off build you can skip installing: just say
   *"Follow the SKILL.md in this project"* with the files attached.)
2. Say: *"Open the Boot Image Generator."* Your Muse creates the
   configurator page (`artifact/boot-image-generator.html`) as an
   artifact in your own Muse and shows it to you.
3. Configure on the page — preset, kernel, app (or describe your own
   app in the free-text box) — and press **Build**. The page downloads
   your request package; drop it back into the chat.
4. Your Muse — the skill running in the background — executes the real
   build on its VM (kernel + app), records a QEMU preview **on the
   selected kernel**, and hands you the boot image and the video in
   chat. SKILL.md's "Build environment" section carries the hard-won
   VM fixes, so a first build doesn't have to rediscover them.

Prefer plain chat? Skip the page and just say: *"Build the Clock-minimal
Nexus 5X boot image end to end, like the worked example."*

Humans can also run `profiles/clock-minimal/build.sh` directly on a
Linux machine; the skill is the same procedure, automated.

## Where each piece comes from

| Workflow step | Source project | What it contributes |
|---|---|---|
| Device: Nexus 5X (bullhead, MSM8992) | — | 1080×1920 JD35695B IPS panel, Snapdragon 808 (2×A57 + 4×A53), 2 GB RAM |
| Kernel A (default for apps) | [embedded-compose](https://github.com/kaiwalyakhasnis/embedded-compose) `boards/nexus5x` | LineageOS 15.1 bullhead kernel (3.10, pinned commit), built from source; minui backend, touch + keys |
| Kernel B (latest) | [nexus5x-linux-6.12-ubuntu-26.04LTS](https://github.com/kaiwalyakhasnis/nexus5x-linux-6.12-ubuntu-26.04LTS) | Mainline 6.12 LTS on MSM8992: MDP5/DSI display, all 6 cores, cpufreq, Wi-Fi, Adreno 418 — `fastboot boot` only |
| Kernel C (trimmed) | [nexus5x-local-llm](https://github.com/kaiwalyakhasnis/nexus5x-local-llm) | Trimmed kernel + BusyBox pattern for a single-purpose image |
| Config method | [nexus5x-hike-buddy](https://github.com/kaiwalyakhasnis/nexus5x-hike-buddy) `config/hike-buddy.config` | The "diet fragment" idea: a config fragment merged onto `bullhead_defconfig`, dropping whole subsystems the app never uses |
| App: game | [nexus5x-recovery-roadfighter](https://github.com/kaiwalyakhasnis/nexus5x-recovery-roadfighter) | Road Fighter in recovery/minui + the QEMU setup for the bullhead kernel |
| App: hiking console | [nexus5x-hike-buddy](https://github.com/kaiwalyakhasnis/nexus5x-hike-buddy) | GPS (in the modem, QMI LOC), sensor hub, offline maps — the profile that keeps the *most* hardware |
| App: on-device LLM | [nexus5x-local-llm](https://github.com/kaiwalyakhasnis/nexus5x-local-llm) | llama.cpp + A2UI screens via embedded-compose |
| Apps: demo / charger / clock | [embedded-compose](https://github.com/kaiwalyakhasnis/embedded-compose) `apps/` + `apps/clock` here | Compose-on-framebuffer apps; an app is a directory, built with `APP_DIR=<dir> ./ec build <board>` |
| QEMU preview | embedded-compose `boards/qemu-virt` + `boards/nexus5x-qemu` | Boot the image before the phone: mainline kernel on QEMU `virt`, or the phone's own 3.10 kernel at 1080×1920; `./ec record` makes the video |
| Future device | Pixel 4a (sunfish) | Slot reserved in the workflow; no board exists yet in any source project |

## Granular means granular

The config step is not "performance / battery" radio buttons. Each feature
is a real kernel subsystem mapped to real `CONFIG_` symbols (the catalogue
is `catalog.json`), grouped the way the source projects group them:

- **Radios** — Wi-Fi (`CFG80211`, `QCA_CLD_WLAN`, `CNSS`), Bluetooth, NFC,
  and the modem itself (`MSM_PIL`, SMD/IPC router) — GPS lives in the
  modem, so "no GPS" and "no modem" are the same switch on this phone.
- **Media** — camera (`MSMB_CAMERA`), video (`MSM_VIDC`), GPU (`MSM_KGSL`),
  audio (SoC/codec/SLIMbus).
- **Sensors** — sensor hub (SSC/nanohub clients), proximity, fingerprint.
- **Android leftovers** — binder, ashmem, SELinux, sdcardfs: no Android
  userspace ever runs on these images.
- **CPU & power** — default governor, tick rate in the kernel; core parking,
  frequency caps and backlight at runtime (`power-min.sh` style scripts),
  because Kconfig alone can't park a cluster.
- **Debug overhead** — RTB, schedstats, dynamic debug, SLUB debug: the
  Hike Buddy diet drops these because they cost CPU on every operation.

Presets are just saved positions of those switches, each traceable to a
real project: **Full** (Kernel B as-is), **Hike Buddy** (its published
fragment), **Local LLM** (trimmed, keeps Wi-Fi + all cores), **Clock —
minimal** (the worked example in `profiles/clock-minimal/`).

## The worked example: Clock

`apps/clock/` is a new embedded-compose app (set time + alarm, animated
seconds ring, 1 Hz repaint — see its README), and
`profiles/clock-minimal/` is its full recipe: the config fragment, the
runtime power script (A57s offline, A53s at 600 MHz powersave, backlight
~10%), and `build.sh`, which builds kernel + image and prints the
`fastboot boot` command. `PREVIEW=1 build.sh` also records the QEMU run.

## Sharing this workflow

- **The page**: the "Nexus 5X Kernel + App Workflow" page in Muse runs
  steps 1–4 in the browser and downloads the same files this repo holds
  (config fragment, power script, build script, workflow skill).
- **As a skill**: `SKILL.md` here is the whole workflow as an agent
  playbook — anyone can hand it to their own AI agent.
- **As code**: this folder. Fork it, add a board, add an app.

## Honest limits

- The browser page *composes* recipes; compiling a kernel and an app is a
  real build on a real machine (the `build.sh` scripts are that build).
- A config symbol that a kernel tree doesn't know is reported by
  `merge_config.sh`, not silently applied — symbols differ between the
  3.10 Android kernel and the 6.12 port.
- QEMU's `virt` board is not the phone: it proves the app and the boot,
  not the phone's drivers. `boards/nexus5x-qemu` (the phone's own kernel
  in QEMU) gets closer; only `fastboot boot` on the phone proves the rest.
