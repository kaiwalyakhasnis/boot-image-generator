---
name: boot-image-generator
description: Turn an LG Nexus 5X into a single-purpose device — build a trimmed LineageOS 3.10 kernel plus one embedded-compose app into a RAM-booted boot image, preview it in QEMU on the selected kernel, and deliver image + video. Use when the user wants a custom Nexus 5X boot image, a minimal-kernel single-app device, or says "boot image generator" / "build the boot image".
---

# Skill: Nexus 5X Forge — turn a Nexus 5X into a single-purpose device

Build a RAM-booted boot image for an LG Nexus 5X (bullhead) that runs one
app on bare Linux — no Android — with a kernel trimmed to exactly what
that app needs. Assembled from kaiwalyakhasnis's public projects:
embedded-compose, nexus5x-hike-buddy, nexus5x-local-llm,
nexus5x-recovery-roadfighter, nexus5x-linux-6.12-ubuntu-26.04LTS.

## Safety rules (do not bend)

- `fastboot boot` ONLY. Never `fastboot flash` a generated image. A reboot
  must always return the phone to its installed system.
- Preview in QEMU before touching the phone (step 5 before step 6).
- A config symbol a kernel tree doesn't know is reported by
  merge_config.sh; never claim a symbol was applied without that check.

## Inputs

1. **Device** — Nexus 5X for now (Pixel 4a slot reserved, unsupported).
2. **Kernel** — `los310` (LineageOS 15.1, 3.10, for embedded-compose apps),
   `mainline612` (6.12 LTS, newest), or `trimmed-llm`.
3. **Features** — granular toggles from `catalog.json` (radios/modem-GPS,
   media/GPU, sensors, Android leftovers, USB/net extras, debug). A preset
   (full / hike-buddy / local-llm / clock-minimal) sets them in bulk;
   individual switches override.
4. **App** — an embedded-compose app directory (demo, charger, clock,
   hike-buddy, roadfighter, local-llm), or a new one written in the shape
   of `apps/demo` (Main.kt exporting `ec_app_main`, one `@Composable`,
   `build.gradle.kts` static-lib, `app.env`).
5. **CPU profile** — stock / balanced / minimal. Minimal = A57 cluster
   parked offline + A53 capped 600 MHz powersave + backlight ~10%,
   applied by a runtime script (`power-min.sh`), not by Kconfig alone.

## What "Build" means (the Build button)

Build is not "download a recipe". When the user presses Build (in the
Boot Image Generator page, or says "build" with a package from it),
the agent MUST execute the build itself, on its own VM, end to end:

1. Generate/download the package (.config or fragment, build.sh, this
   SKILL.md, boot recipe) for the chosen device/kernel/preset/app.
2. Run the build on the VM: kernel (with the fragment merged) + app image,
   exactly as in the Procedure below. Keep a live log the user can watch.
3. Boot the result in QEMU and record a video of it running (for the Nexus
   5X image: the qemu-virt / nexus5x-qemu run of the same app; QEMU does
   not emulate the Snapdragon 808, so say so).
4. Deliver to the user, in chat: the finished boot image (downloadable),
   the QEMU video, and the build outcome — including any compile errors,
   fixed and retried in the open, never silently dropped.
5. Only then is the build "done". If a stage cannot run (missing tools,
   a kernel that will not compile), report that stage plainly instead of
   handing back a recipe and calling it a build.

## Front-end artifact (the configurator page)

`artifact/boot-image-generator.html` in this repo is the visual front-end:
one self-contained static page (preset / kernel / app choices and a
free-text custom-app prompt, one Build button at the end). The page
cannot build anything itself — it composes the user's choices into a
downloadable request package. This skill is the engine behind it.

When the user asks to "open the Boot Image Generator", or wants to
configure visually:

1. Create a web artifact in the user's own Muse from
   `artifact/boot-image-generator.html` and present it to them.
2. The user configures on the page and presses Build; the page downloads
   their request package (and shows what to say next).
3. The user drops that package back into the chat. Read it as the Inputs
   above and execute the build exactly as "What Build means" requires.
4. Skipping the page is equally valid: if the user just describes what
   they want in chat, take the Inputs from the conversation and build.

## Build environment (Muse VM) — read before building

Verified the hard way on 3 Nov 2026; a fresh Muse VM hits every one of
these. Fix them up front and the build is mostly waiting.

- **Tools**: `apt-get install -y g++-aarch64-linux-gnu qemu-system-arm bc
  bison flex libssl-dev`. If `apt-get update` stalls forever, an extra
  package source is hanging through the proxy: restrict apt to the Ubuntu
  archive mirror only (own `-o Dir::Etc::sourcelist=` file + empty
  sourceparts dir) and it completes in seconds.
- **JDK**: embedded-compose needs JDK 17+. Point `JAVA_HOME` at a JDK 21
  and export `GRADLE_USER_HOME` (any home dir; it caches everything).
- **Gradle wrapper cannot download itself** (the VM breaks the JVM's IPv6
  in a way that kills the wrapper's fetch): download
  `gradle-8.13-bin.zip` with curl and pre-seed it into
  `$GRADLE_USER_HOME/wrapper/dists/gradle-8.13-bin/<hash>/` (unzipped, zip
  copied alongside, plus an empty `gradle-8.13-bin.zip.ok`), where <hash>
  is the MD5 of the distribution URL as a base-36 big integer. Gradle's
  own networking needs, in `GRADLE_OPTS` and the project's
  `gradle.properties`: `-Djava.net.preferIPv4Stack=true` and the proxy via
  its IPv6 literal `fd8b:4f84:7d32:99::1` port 3128 (http+https).
- **The egress proxy CA rotates several times a day.** Symptom: Java
  downloads die with `PKIX path validation failed: signature check
  failed` while curl works. Fix: compare the SHA-256 fingerprint of
  `/usr/local/share/ca-certificates/hatch-egress-ca.crt` with the JDK's
  stored `hatch-egress` alias; if different, delete the alias and
  `keytool -importcert` the current cert into the JDK's
  `lib/security/cacerts`, then restart the Gradle daemon. Check this
  FIRST on any Java download failure.
- **`python` must exist**: AOSP GCC 4.9's `bin/*` are script wrappers with
  an absolute `#!/usr/bin/python` shebang and the 3.10 build calls
  `python` too. `ln -s /usr/bin/python3 /usr/bin/python`.
- **Ignore kernel warnings**: the 3.10 tree's `scripts/gcc-wrapper.py`
  (Python 2) turns any non-whitelisted compiler warning into a build
  error, and trimmed configs surface benign ones endlessly. Pass
  `CC=<cross-prefix>gcc` on kernel `make` lines to bypass the wrapper
  entirely; warnings then print but cannot fail the build.
- **dtc vs modern GCC**: fresh 3.10 tree copies need embedded-compose's
  `boards/nexus5x/patches/0000-dtc-fix-yylloc-link-error-with-gcc10.patch`
  or the host `dtc` fails to link (`multiple definition of yylloc`).
  The QEMU kit's tree already carries it; tarball copies don't.
- **QEMU preview must use the SELECTED kernel** (`boards/nexus5x-qemu`,
  never generic `qemu-virt` — that silently substitutes mainline 6.12).
  The board needs the kit from nexus5x-recovery-roadfighter:
  `qemu/setup.sh` with `KERNEL_SRC` = a git checkout of
  LineageOS/android_kernel_lge_bullhead at commit
  65a266ae3d341b04bcf70250c7fa1f28cf687425, `CROSS_COMPILE` = the AOSP
  4.9 prebuilt, and `DTC=$WORK/kobj/scripts/dtc/dtc` (gen-dtb.sh
  otherwise looks for a dtc inside a desktop LineageOS tree). Set
  `NEXUS5X_QEMU_KIT=$WORK` in `boards/nexus5x-qemu/local.env`, and merge
  the SAME config fragment into the kit kernel after setup, so the
  preview kernel config matches the phone's.
- **The clock-minimal fragment's `# CONFIG_MSM_AVTIMER is not set` is
  load-bearing**: avtimer.c calls `q6core_is_adsp_ready()` from the
  disabled QDSP6 audio core; without that line vmlinux fails to link.
- **VM restarts wipe /usr and /root** (apt packages, the python symlink,
  the Kotlin/Native cache in /root/.konan) but home survives (Gradle
  caches, kernel objects). After a restart, redo the apt install and the
  python symlink before resuming.
- **Budget**: a cold full build (both kernels + app + recording) took
  ~2 hours of wall time on the VM including environment fights; with the
  fixes above pre-applied it is mostly compile time (~30–40 min), and
  cached rebuilds are minutes. It is also heavy on usage allowance —
  warn the user before starting.

## Procedure

1. **Resolve the app's needs** against `catalog.json: apps[].needs`
   (e.g. Hike Buddy needs `modem_gps` + `sensorhub`; the clock needs
   nothing). Force those features ON regardless of preset.
2. **Emit the config fragment**: for every OFF feature, one
   `# CONFIG_X is not set` line per symbol, grouped and commented like
   `nexus5x-hike-buddy/config/hike-buddy.config`; then the app's
   always-keep lines (display, RTC, keys, touch, charger, USB gadget).
3. **Build the kernel**: defconfig for the chosen kernel →
   `scripts/kconfig/merge_config.sh -m .config fragment` → `olddefconfig`
   → build `Image.gz-dtb` with the kernel's toolchain (AOSP GCC 4.9 for
   the 3.10 kernel). Log any symbol merge_config rejects and stop if a
   rejected symbol is one the app needs.
4. **Build the image**: in embedded-compose,
   `APP_DIR=<app dir> ./ec build nexus5x` with `KERNEL=<step 3 output>`
   in `boards/nexus5x/local.env`. Add the CPU profile's runtime script to
   the board init. Output: `boot.img`.
5. **Preview in QEMU**: build and record on board `nexus5x-qemu` — the
   phone's own selected kernel via the recovery-roadfighter kit (see
   Build environment). Never substitute `qemu-virt`: it runs a different
   (mainline) kernel and the preview would not show what was selected.
   `./ec record nexus5x-qemu out.gif` or `./ec run nexus5x-qemu`. Show
   the user the recording and the boot log. Only proceed when the user
   confirms the preview looks right.
6. **Deliver the boot image** for the user's own PC:
   `adb reboot bootloader` → `fastboot boot boot.img`. The user runs this
   themselves; never drive a flash, and remind them a reboot undoes it.

## Worked example

`profiles/clock-minimal/` + `apps/clock/`: "phone as a clock" — every
radio and the modem compiled out (GPS goes with the modem), GPU/camera/
audio/sensors out, A57s parked, A53s at 600 MHz, 1 Hz app repaint.
`build.sh` there runs steps 3–5 end to end (`PREVIEW=1` for the video).

## Known limits

- QEMU `virt` proves app + boot, not phone drivers; `nexus5x-qemu` gets
  closer; only the phone proves the panel/touch/PMIC paths.
- App state (clock offset, alarms) lives in RAM: userdata is FDE-encrypted
  and `/tmp` is the only writable store in these images.
- The Nexus 5X panel is IPS: minimise backlight, not pixel brightness.
