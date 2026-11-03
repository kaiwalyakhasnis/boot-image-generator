package clock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.localtime_r
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.timespec
import platform.posix.tm
import ui.Canvas
import ui.Color
import ui.Device
import ui.DrawScope
import ui.Modifier
import ui.Offset
import ui.Size
import ui.Stroke
import ui.fillMaxSize
import ui.rememberFrameMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// evdev key codes (linux/input.h), the same ones the charger and matrix
// apps use. On the phone: volume keys and Power (gpio-keys / qpnp-power-on);
// in QEMU: the keyboard.
private const val KEY_ENTER = 28
private const val KEY_UP = 103
private const val KEY_DOWN = 108
private const val KEY_LEFT = 105
private const val KEY_RIGHT = 106
private const val KEY_VOLUMEDOWN = 114
private const val KEY_VOLUMEUP = 115
private const val KEY_POWER = 116

private val INK = Color(0xfff2f5f7)        // the digits
private val DIM = Color(0xff8a97a3)        // date, labels
private val ACCENT = Color(0xff40c4ff)     // seconds ring, highlights
private val ALARM = Color(0xffffb300)      // alarm: amber, impossible to miss

// Alpha helper, same idiom as the charger app's Scene.kt (toolkit Color has
// copy(alpha=) but no .a()).
private fun Color.a(alpha: Float) = copy(alpha = alpha.coerceIn(0f, 1f))

private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

// ---------------------------------------------------------------- time

private class WallTime(val hour: Int, val minute: Int, val second: Int, val weekday: Int, val day: Int, val month: Int)

private fun wallTime(): WallTime = memScoped {
    val t = alloc<time_tVar>()
    time(t.ptr)
    val tm = alloc<tm>()
    localtime_r(t.ptr, tm.ptr)
    WallTime(tm.tm_hour, tm.tm_min, tm.tm_sec, tm.tm_wday, tm.tm_mday, tm.tm_mon)
}

/** Milliseconds until the next whole second, so the 1 Hz loop lands on the tick. */
private fun msToNextSecond(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    1000 - ts.tv_nsec / 1_000_000
}

private fun readSysfsLine(path: String): String? = memScoped {
    val f = fopen(path, "r") ?: return null
    val buf = allocArray<ByteVar>(128)
    val line = fgets(buf, 128, f)?.toKString()
    fclose(f)
    line?.trim()
}

private fun batteryPercent(): Int? =
    readSysfsLine("/sys/class/power_supply/battery/capacity")?.toIntOrNull()?.coerceIn(0, 100)

// ---------------------------------------------------------------- state

private enum class Field { HOUR, MINUTE, ALARM_HOUR, ALARM_MINUTE, ALARM_ON }

private class ClockState {
    var offsetMin by mutableStateOf(0)          // "set time" = an offset on the RTC (see README)
    var alarmHour by mutableStateOf(7)
    var alarmMinute by mutableStateOf(0)
    var alarmOn by mutableStateOf(true)
    var setting by mutableStateOf(false)        // SET mode: fields are editable
    var field by mutableStateOf(Field.HOUR)
    var ringing by mutableStateOf(false)
    var lastRungKey by mutableStateOf(-1)       // hour*60+minute of the last ring, so it fires once

    fun adjust(dir: Int) {
        when (field) {
            Field.HOUR -> offsetMin += dir * 60
            Field.MINUTE -> offsetMin += dir
            Field.ALARM_HOUR -> alarmHour = (alarmHour + dir + 24) % 24
            Field.ALARM_MINUTE -> alarmMinute = (alarmMinute + dir + 60) % 60
            Field.ALARM_ON -> alarmOn = !alarmOn
        }
    }

    fun nextField(dir: Int) {
        val all = Field.entries
        field = all[(all.indexOf(field) + dir + all.size) % all.size]
    }
}

/** The displayed time: RTC + the user's offset, normalised into the day. */
private fun displayed(now: WallTime, offsetMin: Int): WallTime {
    val total = (now.hour * 60 + now.minute + offsetMin).mod(24 * 60)
    return WallTime(total / 60, total % 60, now.second, now.weekday, now.day, now.month)
}

// ---------------------------------------------------------------- app

/**
 * A clock for a phone that does nothing else.
 *
 * Power is the design constraint, not a feature:
 *  - In SHOW mode the screen redraws once per second (aligned to the tick);
 *    the frame clock only runs during the 2.5 s boot intro and while the
 *    alarm rings. Everything else about the minimal build — radios and the
 *    modem compiled out of the kernel, the A57 cluster offline, the A53s
 *    capped — lives in profiles/clock-minimal/, not here.
 *  - Keys: ENTER or VOL+/- enters SET and moves between fields, UP/DOWN
 *    adjusts, POWER leaves SET. Any key stops a ringing alarm.
 */
@Composable
internal fun ClockApp() {
    val state = remember { ClockState() }
    var now by remember { mutableStateOf(wallTime()) }
    var bootAt by remember { mutableStateOf(-1L) }

    // 1 Hz heartbeat, aligned to the wall second (the matrix clock's pattern).
    LaunchedEffect(Unit) {
        while (true) {
            delay(msToNextSecond())
            now = wallTime()
            val d = displayed(now, state.offsetMin)
            val key = d.hour * 60 + d.minute
            if (state.alarmOn && d.hour == state.alarmHour && d.minute == state.alarmMinute &&
                d.second == 0 && state.lastRungKey != key
            ) {
                state.ringing = true
                state.lastRungKey = key
            }
        }
    }

    DisposableEffect(state) {
        Device.onKey = { code, value ->
            if (value == 1) {  // press (not release / autorepeat)
                if (state.ringing) {
                    state.ringing = false
                } else if (state.setting) {
                    when (code) {
                        KEY_UP, KEY_VOLUMEUP -> state.adjust(+1)
                        KEY_DOWN, KEY_VOLUMEDOWN -> state.adjust(-1)
                        KEY_LEFT -> state.nextField(-1)
                        KEY_RIGHT, KEY_ENTER -> state.nextField(+1)
                        KEY_POWER -> state.setting = false
                    }
                } else {
                    when (code) {
                        KEY_ENTER, KEY_VOLUMEUP, KEY_VOLUMEDOWN -> state.setting = true
                    }
                }
                now = wallTime()  // repaint at once, don't wait for the tick
            }
        }
        onDispose {
            Device.onKey = null
        }
    }

    val disp = displayed(now, state.offsetMin)
    // Smooth frames only while something truly animates: the boot intro
    // (first 2.5 s) and the alarm. Otherwise the frame clock sleeps and the
    // 1 Hz loop above is the only thing that ever repaints.
    val millis by rememberFrameMillis(state.ringing || bootAt < 0L)
    if (bootAt < 0L && millis > 0L) bootAt = millis
    val intro = if (bootAt >= 0L) ((millis - bootAt) / 2500f).coerceIn(0f, 1f) else 0f
    val animating = state.ringing || intro < 1f

    Canvas(Modifier.fillMaxSize()) {
        drawClock(
            t = disp,
            frac = if (animating) (millis % 1000) / 1000f else 0f,
            intro = intro,
            state = state,
            battery = batteryPercent(),
            millis = millis,
        )
    }
}

// ---------------------------------------------------------------- drawing

private fun DrawScope.drawClock(
    t: WallTime,
    frac: Float,
    intro: Float,
    state: ClockState,
    battery: Int?,
    millis: Long,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = min(size.width, size.height) * 0.355f
    val ringC = Offset(cx, cy)

    // Breathing glow behind the ring (charger-scene style layered halos).
    // In the 1 Hz steady state this is drawn once per second and simply sits
    // there; during intro/ringing it actually breathes.
    val breathe = 0.5f + 0.5f * sin(millis / 900.0 * PI).toFloat()
    val glow = if (state.ringing) ALARM else ACCENT
    drawCircle(glow.a(0.05f + 0.04f * breathe), r + 90f, ringC)
    drawCircle(glow.a(0.05f), r + 46f, ringC)

    // Ring track, then the seconds arc: a full sweep per minute, eased in
    // during the boot intro (sweep grows 0 -> current), stepping once per
    // second in the steady state, smooth while animating.
    drawCircle(Color(0xff26313a), r, ringC, Stroke(10f))
    val seconds = t.second + if (state.ringing || intro < 1f) frac else 0f
    val sweep = 360f * (seconds / 60f) * (if (intro < 1f) easeOut(intro) else 1f)
    drawArc(
        if (state.ringing) ALARM else ACCENT, -90f, sweep, false,
        Offset(cx - r, cy - r), Size(r * 2, r * 2), Stroke(10f),
    )
    // The travelling tip of the arc.
    val tipA = (-90f + sweep) * PI.toFloat() / 180f
    drawCircle(
        if (state.ringing) ALARM else ACCENT, 13f,
        Offset(cx + r * cos(tipA), cy + r * sin(tipA)),
    )

    // Ringing: two expanding pulse rings, charger's ripple in amber.
    if (state.ringing) {
        for (k in 0..1) {
            val p = ((millis / 1400f) + k * 0.5f) % 1f
            drawCircle(ALARM.a(0.35f * (1f - p)), r + 30f + p * 150f, ringC, Stroke(6f))
        }
    }

    // The time itself. The toolkit's font is monospace cells, so measuring
    // "88:88" gives the cell grid; scale to ~74% of the panel width.
    val unit = measureText("88:88", 1)
    val scale = (size.width * 0.74f / unit.width).toInt().coerceAtLeast(1)
    val colon = if (t.second % 2 == 0) ":" else " "
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    val timeText = hh + colon + mm
    val tw = measureText(timeText, scale)
    val timeColor = when {
        state.ringing && t.second % 2 == 0 -> ALARM
        state.setting -> INK
        else -> INK.a(0.35f + 0.65f * (if (intro < 1f) easeOut(intro) else 1f))
    }
    drawText(timeText, Offset(cx - tw.width / 2f, cy - tw.height / 2f), timeColor, scale)

    // SET mode: underline the field being edited (it also blinks once per
    // second with the colon — no extra frames needed).
    if (state.setting && t.second % 2 == 0) {
        val cellW = unit.width / 5f * scale
        val (fx, fw) = when (state.field) {
            Field.HOUR -> (cx - tw.width / 2f) to cellW * 2
            Field.MINUTE -> (cx - tw.width / 2f + cellW * 3) to cellW * 2
            else -> 0f to 0f  // alarm fields underline the alarm line instead
        }
        if (fw > 0f) drawRect(ACCENT, Offset(fx, cy + tw.height / 2f + 18f), Size(fw, 8f))
    }

    // Date below the ring (inside the ring the arc would strike through it).
    val date = "${DAYS[t.weekday]} ${t.day} ${MONTHS[t.month]}"
    val ds = (scale / 3).coerceAtLeast(1)
    val dw = measureText(date, ds)
    val ringR = min(size.width, size.height) * 0.355f
    drawText(date, Offset(cx - dw.width / 2f, cy + ringR + 56f), DIM, ds)

    // Alarm line: what SET edits when an alarm field is active.
    val alarmText = if (state.alarmOn) {
        "ALARM " + state.alarmHour.toString().padStart(2, '0') + ":" + state.alarmMinute.toString().padStart(2, '0')
    } else {
        "ALARM OFF"
    }
    val as_ = (scale / 4).coerceAtLeast(1)
    val aw = measureText(alarmText, as_)
    val alarmY = cy + ringR + 56f + dw.height + 36f
    val alarmColor = when {
        state.ringing -> ALARM
        state.setting && state.field != Field.HOUR && state.field != Field.MINUTE -> ACCENT
        else -> DIM
    }
    drawText(alarmText, Offset(cx - aw.width / 2f, alarmY), alarmColor, as_)
    if (state.setting && t.second % 2 == 0 && state.field != Field.HOUR && state.field != Field.MINUTE) {
        val cellW = measureText("8", as_).width
        val (fx, fw) = when (state.field) {
            Field.ALARM_HOUR -> (cx - aw.width / 2f + cellW * 6) to cellW * 2
            Field.ALARM_MINUTE -> (cx - aw.width / 2f + cellW * 9) to cellW * 2
            else -> (cx - aw.width / 2f + cellW * 6) to cellW * 5  // ALARM_ON: the ON/OFF word
        }
        drawRect(ACCENT, Offset(fx, alarmY + measureText(alarmText, as_).height + 12f), Size(fw, 6f))
    }

    // Battery, top right — one sysfs read per repaint (1 Hz), nothing more.
    if (battery != null) {
        val bs = (scale / 5).coerceAtLeast(1)
        val bt = "$battery%"
        val bw = measureText(bt, bs)
        drawText(bt, Offset(size.width - bw.width - 36f, 30f), DIM, bs)
    }

    // In SET mode, say so (the phone has no other chrome to explain itself).
    if (state.setting) {
        val hs = (scale / 5).coerceAtLeast(1)
        val hint = "SET: VOL +/- ADJUST - POWER DONE"
        val hw = measureText(hint, hs)
        drawText(hint, Offset(cx - hw.width / 2f, size.height - 90f), DIM, hs)
    }
}

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
