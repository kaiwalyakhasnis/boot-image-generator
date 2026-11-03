#!/bin/sh
# power-min.sh — the runtime half of the clock profile for bullhead (MSM8992).
# Config can delete subsystems; only userspace can park cores and cap clocks.
# Run once from init (board-init.sh) after /sys is mounted. Every write is
# guarded: paths differ a little between the 3.10 LineageOS kernel and the
# 6.12 port, and a missing node must never fail the boot.
#
# Target state:
#   - big cluster (A57, cpu4-5, up to 1.82 GHz): OFFLINE — a clock never
#     needs it, and an offline cluster draws (nearly) nothing.
#   - little cluster (A53, cpu0-3): governor powersave, capped at 600 MHz
#     (stock idle-adjacent step; the panel composition in the clock app is
#     one 1080p blit per second, which 600 MHz does in a few ms).
#   - backlight near the floor: the Nexus 5X panel is IPS — black pixels do
#     NOT save power on it, the backlight dominates (nexus5x-hike-buddy's
#     hardware notes). Dimming is worth more than any dark theme.

w() { [ -e "$1" ] && echo "$2" > "$1" 2>/dev/null; }

# Park the A57s.
for c in 4 5; do
    w /sys/devices/system/cpu/cpu$c/online 0
done

# Cap and calm the A53s (per-policy nodes on the cluster leader cover the
# whole cluster on 3.10; write both cpu0 and per-cpu paths where present).
for c in 0 1 2 3; do
    base=/sys/devices/system/cpu/cpu$c/cpufreq
    w $base/scaling_governor powersave
    w $base/scaling_max_freq 600000
    w $base/scaling_min_freq 302400
done

# Backlight: ~10% — readable indoors, the single biggest saving on this panel.
w /sys/class/leds/lcd-backlight/brightness 24

# USB: keep the gadget (charging + optional EC_DEV_USB dev link); nothing
# else to switch off — the radios are not in the kernel at all.
exit 0
