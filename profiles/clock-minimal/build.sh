#!/bin/sh
# build.sh — build the Clock boot image for the Nexus 5X on a PC, and the
# QEMU preview of it. Run from anywhere; paths resolve from this script.
#
# What it does, in order:
#   1. KERNEL: builds the LineageOS 15.1 bullhead kernel (the same pinned
#      commit embedded-compose's boards/nexus5x uses) with
#      clock-minimal.config merged onto lineageos_bullhead_defconfig.
#      Skipped when KERNEL=<Image.gz-dtb> is given, or out/ already has one.
#   2. IMAGE: APP_DIR=apps/clock ./ec build nexus5x inside a checkout of
#      kaiwalyakhasnis/embedded-compose, with KERNEL pointed at step 1.
#      Output: dist/nexus5x-clock-boot.img (RAM-boot only, never flashed).
#   3. QEMU (with PREVIEW=1): builds the same app for the qemu-virt board
#      (mainline 6.12, no phone needed) and records a preview video with
#      ./ec record: dist/clock-qemu-preview.gif
#
# Needs (Ubuntu/Debian): git, python3, and embedded-compose's own deps:
#   sudo apt install g++-aarch64-linux-gnu qemu-system-arm python3-pil \
#                    openjdk-17-jdk-headless curl make bc bison flex libssl-dev
set -e

FORGE=$(cd "$(dirname "$0")/../.." && pwd)
PROFILE=$FORGE/profiles/clock-minimal
OUT=$FORGE/out/clock-minimal
DIST=$FORGE/dist
EC=${EC:-$FORGE/../embedded-compose}          # checkout of embedded-compose
LINEAGE_KERNEL_COMMIT=65a266ae3d341b04bcf70250c7fa1f28cf687425   # lineage-15.1
AOSP_GCC_COMMIT=7a28c220c2e9001825328dca6188ef0077a80a88         # aarch64-linux-android-4.9
mkdir -p "$OUT" "$DIST"

[ -d "$EC" ] || { echo "embedded-compose checkout not found at $EC — clone it:"; \
  echo "  git clone https://github.com/kaiwalyakhasnis/embedded-compose \"$EC\""; exit 1; }

# ---- 1. kernel with the clock diet ---------------------------------------
if [ -z "$KERNEL" ]; then
    KIMG=$OUT/kernel/arch/arm64/boot/Image.gz-dtb
    if [ ! -f "$KIMG" ]; then
        SRC=$OUT/android_kernel_lge_bullhead-$LINEAGE_KERNEL_COMMIT
        GCC=$OUT/aarch64-linux-android-4.9-$AOSP_GCC_COMMIT
        if [ ! -d "$SRC" ]; then
            curl -L "https://github.com/LineageOS/android_kernel_lge_bullhead/archive/$LINEAGE_KERNEL_COMMIT.tar.gz" \
                | tar xz -C "$OUT"
            mv "$OUT"/android_kernel_lge_bullhead-* "$SRC" 2>/dev/null || true
            # ec's host build fix: dtc yylloc vs GCC 10+ -fno-common (the
            # tarball copy has no patches applied, unlike ec's own flow).
            patch -p1 -s -d "$SRC" < "$EC/boards/nexus5x/patches/0000-dtc-fix-yylloc-link-error-with-gcc10.patch" || true
        fi
        if [ ! -d "$GCC" ]; then
            mkdir -p "$GCC"
            curl -L "https://android.googlesource.com/platform/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9/+archive/$AOSP_GCC_COMMIT.tar.gz" \
                | tar xz -C "$GCC"
        fi
        # CC = the real compiler, bypassing scripts/gcc-wrapper.py so that
        # warnings cannot fail the build (KK: ignore warnings).
        MK="make -C $SRC O=$OUT/kernel ARCH=arm64 CROSS_COMPILE=$GCC/bin/aarch64-linux-android- CC=$GCC/bin/aarch64-linux-android-gcc CONFIG_NO_ERROR_ON_MISMATCH=1"
        mkdir -p "$OUT/kernel"   # kernel Makefile refuses a nonexistent O= dir
        $MK lineageos_bullhead_defconfig
        ( cd "$OUT/kernel" && CONFIG_=CONFIG_ "$SRC/scripts/kconfig/merge_config.sh" -m .config "$PROFILE/clock-minimal.config" \
            && make -C "$SRC" O="$OUT/kernel" ARCH=arm64 CROSS_COMPILE="$GCC/bin/aarch64-linux-android-" CONFIG_NO_ERROR_ON_MISMATCH=1 olddefconfig )
        $MK -j"$(nproc)"
    fi
    KERNEL=$KIMG
fi
echo "kernel: $KERNEL"

# ---- 2. boot image ---------------------------------------------------------
# boards/nexus5x reads KERNEL from boards/nexus5x/local.env; APP_DIR points
# the build at the out-of-tree clock app. power-min.sh runs at boot via the
# board's init hook (EC_BOARD_INIT_EXTRA is documentation-only here: copy
# power-min.sh into the app dir is NOT how it works — see README "Power").
( cd "$EC" && printf 'KERNEL=%s\n' "$KERNEL" > boards/nexus5x/local.env )
( cd "$EC" && APP_DIR="$FORGE/apps/clock" ./ec build nexus5x )
cp "$EC/out/nexus5x-clock/boot.img" "$DIST/nexus5x-clock-boot.img"   # ec output dir is <board>-<app>
echo
echo "Boot it (RAM only — a reboot returns the phone to its own system):"
echo "  adb reboot bootloader        # or hold Vol-Down + Power"
echo "  fastboot boot $DIST/nexus5x-clock-boot.img"

# ---- 3. QEMU preview --------------------------------------------------------
if [ "${PREVIEW:-0}" = "1" ]; then
    # Preview runs the SELECTED kernel (3.10) via the nexus5x-qemu board,
    # never the generic qemu-virt board (that would be mainline 6.12).
    ( cd "$EC" && APP_DIR="$FORGE/apps/clock" ./ec build nexus5x-qemu )
    ( cd "$EC" && ./ec record nexus5x-qemu "$DIST/clock-qemu-preview.gif" )
    echo "QEMU preview: $DIST/clock-qemu-preview.gif"
    echo "For the phone's own 3.10 kernel in QEMU instead, use board nexus5x-qemu"
    echo "(needs the QEMU kit from kaiwalyakhasnis/nexus5x-recovery-roadfighter)."
fi
