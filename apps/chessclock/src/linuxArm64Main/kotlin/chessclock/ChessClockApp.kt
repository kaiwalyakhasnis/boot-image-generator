package chessclock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.delay
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec
import ui.Canvas
import ui.Color
import ui.Device
import ui.DrawScope
import ui.Modifier
import ui.Offset
import ui.PointerEventType
import ui.Size
import ui.fillMaxSize
import ui.pointerInput
import ui.rememberFrameMillis
import kotlin.math.max
import kotlin.math.min

// evdev key codes (linux/input.h). On the phone: the volume keys and Power
// (gpio-keys / qpnp-power-on); in QEMU: the keyboard.
private const val KEY_ENTER = 28
private const val KEY_LEFT = 105
private const val KEY_RIGHT = 106
private const val KEY_UP = 103
private const val KEY_DOWN = 108
private const val KEY_VOLUMEDOWN = 114
private const val KEY_VOLUMEUP = 115
private const val KEY_POWER = 116

private val INK = Color(0xfff2f5f7)
private val DIM = Color(0xff8a97a3)
private val DARK = Color(0xff101216)
private val ACCENT = Color(0xff3fb950)     // to-move green
private val AMBER = Color(0xffffb300)
private val RED = Color(0xfff85149)
private val BLUE = Color(0xff58a6ff)

private fun Color.a(alpha: Float) = copy(alpha = alpha.coerceIn(0f, 1f))

// The big tappable button on the SETUP / CUSTOM screens (fractions of the
// screen; the draw code and the touch handler share these).
private const val BTN_X0 = 0.14f
private const val BTN_X1 = 0.86f
private const val SETUP_BTN_Y = 0.775f
private const val CUSTOM_BTN_Y = 0.745f
private const val BTN_H = 148f

private fun monoMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

// ---------------------------------------------------------------- model

private enum class Side { WHITE, BLACK; fun other() = if (this == WHITE) BLACK else WHITE }
private enum class Mode { SETUP, CUSTOM, RUN, PAUSE, FLAG }
private enum class CField { W_MIN, B_MIN, INC, DELAY, START }

private data class Preset(val name: String, val detail: String, val wMs: Long, val bMs: Long, val incMs: Long, val dlyMs: Long)

private val PRESETS = listOf(
    Preset("BULLET 1+0", "1 MIN EACH - NO INCREMENT", 60_000, 60_000, 0, 0),
    Preset("BLITZ 3+0", "3 MIN EACH - NO INCREMENT", 180_000, 180_000, 0, 0),
    Preset("BLITZ 3+2", "3 MIN EACH - +2S PER MOVE", 180_000, 180_000, 2_000, 0),
    Preset("BLITZ 5+0", "5 MIN EACH - NO INCREMENT", 300_000, 300_000, 0, 0),
    Preset("BLITZ 5+3", "5 MIN EACH - +3S PER MOVE", 300_000, 300_000, 3_000, 0),
    Preset("RAPID 10+0", "10 MIN EACH - NO INCREMENT", 600_000, 600_000, 0, 0),
    Preset("RAPID 15+10", "15 MIN EACH - +10S PER MOVE", 900_000, 900_000, 10_000, 0),
    Preset("CLASSICAL 30+0", "30 MIN EACH - NO INCREMENT", 1_800_000, 1_800_000, 0, 0),
    Preset("CLASSICAL 90+30", "90 MIN EACH - +30S PER MOVE", 5_400_000, 5_400_000, 30_000, 0),
    Preset("HANDICAP 10 vs 5", "WHITE 10 MIN - BLACK 5 MIN - ODDS GAME", 600_000, 300_000, 0, 0),
    Preset("CUSTOM", "YOUR OWN TIMES - PER-SIDE MINUTES, INCREMENT, DELAY", 0, 0, 0, 0),
)

private class ChessState {
    var mode by mutableStateOf(Mode.SETUP)
    var presetIdx by mutableStateOf(4)              // BLITZ 5+3, the club default
    var active by mutableStateOf(Side.WHITE)
    var label by mutableStateOf(PRESETS[4].name)
    var remW by mutableStateOf(300_000L)
    var remB by mutableStateOf(300_000L)
    var initW by mutableStateOf(300_000L)
    var initB by mutableStateOf(300_000L)
    var incMs by mutableStateOf(3_000L)
    var dlyMs by mutableStateOf(0L)
    var movesW by mutableStateOf(0)
    var movesB by mutableStateOf(0)
    var field by mutableStateOf(CField.W_MIN)
    var cWMin by mutableStateOf(10)
    var cBMin by mutableStateOf(10)
    var cInc by mutableStateOf(5)
    var cDly by mutableStateOf(0)
    var demo by mutableStateOf(false)

    var turnStart = monoMs()        // monotonic ms when the active turn began
    var remAtTurnStart = 300_000L   // active side's remaining at turnStart
    var bootAt = monoMs()
    var flagAt = 0L
    var lastAuto = 0L
    var everKey = false
    var panelW = 1f                 // last drawn size, for touch regions
    var panelH = 1f

    fun rem(s: Side) = if (s == Side.WHITE) remW else remB
    private fun setRem(s: Side, v: Long) { if (s == Side.WHITE) remW = v else remB = v }

    /** Recompute the running clock from the monotonic clock; handles the flag. */
    fun tick() {
        if (mode != Mode.RUN) return
        val elapsed = monoMs() - turnStart
        val eff = max(0L, elapsed - dlyMs)          // Bronstein delay: the first dlyMs of a turn are free
        val cur = remAtTurnStart - eff
        if (cur <= 0L) {
            setRem(active, 0L)
            mode = Mode.FLAG
            flagAt = monoMs()
        } else {
            setRem(active, cur)
        }
    }

    fun startGame(wMs: Long, bMs: Long, inc: Long, dly: Long, name: String) {
        initW = wMs; initB = bMs; remW = wMs; remB = bMs
        incMs = inc; dlyMs = dly; label = name
        movesW = 0; movesB = 0
        active = Side.WHITE
        turnStart = monoMs(); remAtTurnStart = wMs
        mode = Mode.RUN
    }

    fun startPreset() {
        val p = PRESETS[presetIdx]
        startGame(p.wMs, p.bMs, p.incMs, p.dlyMs, p.name)
    }

    fun startCustom() = startGame(
        cWMin * 60_000L, cBMin * 60_000L, cInc * 1_000L, cDly * 1_000L,
        "CUSTOM ${cWMin}v${cBMin} +${cInc}",
    )

    /** The active player slaps the clock: bank the increment, hand over the turn. */
    fun press(side: Side) {
        if (mode != Mode.RUN || side != active) return
        tick()
        if (mode != Mode.RUN) return                // the press came too late: flag already fell
        setRem(side, rem(side) + incMs)             // Fischer increment
        if (side == Side.WHITE) movesW++ else movesB++
        active = side.other()
        turnStart = monoMs()
        remAtTurnStart = rem(active)
        lastAuto = monoMs()
    }

    fun pause() { if (mode == Mode.RUN) { tick(); mode = Mode.PAUSE } }
    fun resume() {
        if (mode != Mode.PAUSE) return
        turnStart = monoMs()                        // delay refreshes on resume (documented)
        remAtTurnStart = rem(active)
        mode = Mode.RUN
    }

    fun toSetup() { mode = Mode.SETUP; demo = false; bootAt = monoMs() }

    fun browse(dir: Int) {
        presetIdx = (presetIdx + dir + PRESETS.size) % PRESETS.size
    }

    fun adjustCustom(dir: Int) {
        when (field) {
            CField.W_MIN -> cWMin = (cWMin + dir).coerceIn(1, 180)
            CField.B_MIN -> cBMin = (cBMin + dir).coerceIn(1, 180)
            CField.INC -> cInc = (cInc + dir).coerceIn(0, 60)
            CField.DELAY -> cDly = (cDly + dir).coerceIn(0, 30)
            CField.START -> {}
        }
    }

    fun nextField() {
        val all = CField.entries
        field = all[(all.indexOf(field) + 1) % all.size]
    }

    /** Arbiter correction while paused: +/-10 s gifts, VOL+ to White, VOL- to Black. */
    fun gift(side: Side) { setRem(side, (rem(side) + 10_000L).coerceAtMost(9 * 3_600_000L)); if (side == active) remAtTurnStart = rem(side) }

    /** Self-playing attract mode so a freshly booted phone (and the QEMU
     *  preview) shows a living clock without anyone touching it. Any real
     *  key or tap takes over instantly. */
    fun autopilot() {
        val now = monoMs()
        if (!demo && mode == Mode.SETUP && !everKey && now - bootAt > 9_000L) {
            demo = true
            val p = PRESETS[4]
            startGame(p.wMs, p.bMs, p.incMs, p.dlyMs, p.name)
            lastAuto = now
        } else if (demo && mode == Mode.RUN && now - lastAuto > 2_300L) {
            press(active)
        } else if (demo && mode == Mode.FLAG && now - flagAt > 3_500L) {
            toSetup()                               // bootAt re-arms: the demo loops
        }
    }
}

// ---------------------------------------------------------------- format

private fun fmt(ms: Long): String {
    val t = max(0L, ms)
    val totalSec = t / 1000
    return when {
        totalSec >= 3600 -> "${totalSec / 3600}:${(totalSec % 3600 / 60).toString().padStart(2, '0')}:${(totalSec % 60).toString().padStart(2, '0')}"
        totalSec >= 60 -> "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
        else -> "0:${totalSec.toString().padStart(2, '0')}.${(t % 1000) / 100}"
    }
}

private fun fmtShort(ms: Long): String {
    val totalSec = ms / 1000
    return if (totalSec >= 60) "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}" else "${totalSec}S"
}

// ---------------------------------------------------------------- app

/**
 * A chess clock for a phone that does nothing else.
 *
 * Controls (the phone has only Vol keys, Power, and a touchscreen):
 *  SETUP   VOL/arrows or tap top/bottom half: browse formats. ENTER: start
 *          (CUSTOM opens the editor: per-side minutes, Fischer increment,
 *          Bronstein delay — fields with ENTER, values with VOL, POWER back).
 *  PLAY    Tap your half, or VOL+ = White pressed / VOL- = Black pressed
 *          (in QEMU: LEFT = White, RIGHT = Black). ENTER or POWER: pause.
 *  PAUSED  ENTER: resume. VOL+ / VOL-: arbiter's +10 s gift to White / Black.
 *          POWER: abandon the game, back to SETUP.
 *  FLAG    Any key or tap: back to SETUP.
 *  Idle on SETUP for 9 s and it starts a self-playing demo game.
 */
@Composable
internal fun ChessClockApp() {
    val state = remember { ChessState() }

    LaunchedEffect(Unit) {
        while (true) {
            state.tick()
            state.autopilot()
            delay(40)
        }
    }

    DisposableEffect(state) {
        Device.onKey = { code, value ->
            if (value == 1) {  // press (not release / autorepeat)
                state.everKey = true
                if (state.demo) state.demo = false
                when (state.mode) {
                    Mode.SETUP -> when (code) {
                        KEY_UP, KEY_VOLUMEUP, KEY_LEFT -> state.browse(-1)
                        KEY_DOWN, KEY_VOLUMEDOWN, KEY_RIGHT -> state.browse(+1)
                        KEY_ENTER -> if (state.presetIdx == PRESETS.lastIndex) {
                            state.field = CField.W_MIN; state.mode = Mode.CUSTOM
                        } else state.startPreset()
                    }
                    Mode.CUSTOM -> when (code) {
                        KEY_UP, KEY_VOLUMEUP -> state.adjustCustom(+1)
                        KEY_DOWN, KEY_VOLUMEDOWN -> state.adjustCustom(-1)
                        KEY_ENTER -> if (state.field == CField.START) state.startCustom() else state.nextField()
                        KEY_POWER -> state.mode = Mode.SETUP
                    }
                    Mode.RUN -> when (code) {
                        KEY_VOLUMEUP, KEY_LEFT -> state.press(Side.WHITE)
                        KEY_VOLUMEDOWN, KEY_RIGHT -> state.press(Side.BLACK)
                        KEY_ENTER, KEY_POWER -> state.pause()
                    }
                    Mode.PAUSE -> when (code) {
                        KEY_ENTER -> state.resume()
                        KEY_VOLUMEUP -> state.gift(Side.WHITE)
                        KEY_VOLUMEDOWN -> state.gift(Side.BLACK)
                        KEY_POWER -> state.toSetup()
                    }
                    Mode.FLAG -> state.toSetup()
                }
            }
        }
        onDispose { Device.onKey = null }
    }

    // Smooth frames while a clock runs or a flag flashes; otherwise the
    // 40 ms state loop above still repaints often enough for menus.
    val millis by rememberFrameMillis(state.mode == Mode.RUN || state.mode == Mode.FLAG)

    Canvas(
        Modifier.fillMaxSize().pointerInput { e ->
            if (e.type == PointerEventType.Press) {
                state.everKey = true
                if (state.demo) state.demo = false
                val top = e.position.y < state.panelH / 2f
                val inButton = e.position.x > state.panelW * BTN_X0 && e.position.x < state.panelW * BTN_X1
                when (state.mode) {
                    Mode.SETUP -> if (inButton && e.position.y > state.panelH * SETUP_BTN_Y) {
                        if (state.presetIdx == PRESETS.lastIndex) {
                            state.field = CField.W_MIN; state.mode = Mode.CUSTOM
                        } else state.startPreset()
                    } else state.browse(if (top) -1 else +1)
                    Mode.CUSTOM -> if (inButton && e.position.y > state.panelH * CUSTOM_BTN_Y) {
                        state.startCustom()
                    } else if (!top) state.adjustCustom(+1) else state.adjustCustom(-1)
                    Mode.RUN -> state.press(if (top) Side.BLACK else Side.WHITE)
                    Mode.PAUSE -> state.resume()
                    Mode.FLAG -> state.toSetup()
                }
            }
        },
    ) {
        state.panelW = size.width
        state.panelH = size.height
        when (state.mode) {
            Mode.SETUP -> drawSetup(state)
            Mode.CUSTOM -> drawCustom(state)
            else -> drawGame(state, millis)
        }
    }
}

// ---------------------------------------------------------------- drawing

private fun DrawScope.textCentered(text: String, cx: Float, y: Float, color: Color, scale: Int) {
    val w = measureText(text, scale)
    drawText(text, Offset(cx - w.width / 2f, y), color, scale)
}

/** Largest integer text scale (capped) that keeps [text] within [frac] of the width. */
private fun DrawScope.fit(text: String, frac: Float, cap: Int = 8): Int =
    max(1, min(cap, (size.width * frac / measureText(text, 1).width).toInt()))

/** A big, obvious button: filled panel, bright edge, centred label. */
private fun DrawScope.drawButton(label: String, yFrac: Float) {
    val x0 = size.width * BTN_X0
    val bw = size.width * (BTN_X1 - BTN_X0)
    val y = size.height * yFrac
    drawRect(Color(0xff14532d), Offset(x0, y), Size(bw, BTN_H))
    drawRect(ACCENT, Offset(x0, y), Size(bw, 5f))
    drawRect(ACCENT, Offset(x0, y + BTN_H - 5f), Size(bw, 5f))
    drawRect(ACCENT, Offset(x0, y), Size(5f, BTN_H))
    drawRect(ACCENT, Offset(x0 + bw - 5f, y), Size(5f, BTN_H))
    val sc = fit(label, (BTN_X1 - BTN_X0) * 0.86f, 5)
    val th = measureText(label, sc).height
    textCentered(label, size.width / 2f, y + BTN_H / 2f - th / 2f, INK, sc)
}

private fun DrawScope.drawGame(s: ChessState, millis: Long) {
    val w = size.width
    val h = size.height
    val half = h / 2f
    drawPanel(s, Side.BLACK, 0f, half, millis)
    drawPanel(s, Side.WHITE, half, half, millis)

    // Centre strip: format, side to move, move count.
    val stripH = 92f
    drawRect(Color(0xff0d1117), Offset(0f, half - stripH / 2f), Size(w, stripH))
    drawRect(ACCENT.a(0.5f), Offset(0f, half - stripH / 2f), Size(w, 3f))
    drawRect(ACCENT.a(0.5f), Offset(0f, half + stripH / 2f - 3f), Size(w, 3f))
    val status = when (s.mode) {
        Mode.RUN -> (if (s.demo) "DEMO - " else "") + s.label + "  -  " + (if (s.active == Side.WHITE) "WHITE" else "BLACK") + " TO MOVE"
        Mode.PAUSE -> "PAUSED - ENTER RESUME - POWER NEW GAME"
        Mode.FLAG -> "TIME! " + (if (s.active == Side.WHITE) "BLACK" else "WHITE") + " WINS - ANY KEY FOR SETUP"
        else -> s.label
    }
    val sc = max(1, (w * 0.92f / measureText(status, 1).width).toInt())
    textCentered(status, w / 2f, half - measureText(status, sc).height / 2f, if (s.mode == Mode.FLAG) RED else INK, sc)
    if (s.mode == Mode.PAUSE) {
        val hint = "ARBITER: VOL+ GIVES WHITE +10S - VOL- GIVES BLACK +10S"
        val hs = max(1, (w * 0.92f / measureText(hint, 1).width).toInt())
        textCentered(hint, w / 2f, half + stripH / 2f + 26f, AMBER, hs)
        // A visible way back into the game (any tap also resumes).
        drawRect(Color(0xff14532d), Offset(w * 0.32f, half + 130f), Size(w * 0.36f, 110f))
        drawRect(ACCENT, Offset(w * 0.32f, half + 130f), Size(w * 0.36f, 4f))
        drawRect(ACCENT, Offset(w * 0.32f, half + 236f), Size(w * 0.36f, 4f))
        val rs = fit("RESUME", 0.3f, 4)
        textCentered("RESUME", w / 2f, half + 130f + 55f - measureText("RESUME", rs).height / 2f, INK, rs)
    }
}

private fun DrawScope.drawPanel(s: ChessState, side: Side, top: Float, height: Float, millis: Long) {
    val w = size.width
    val rem = s.rem(side)
    val init = if (side == Side.WHITE) s.initW else s.initB
    val isActive = s.active == side && s.mode == Mode.RUN
    val flagged = s.mode == Mode.FLAG && s.active == side
    val low = rem < 10_000L && s.mode == Mode.RUN

    val bg = when {
        flagged -> if ((millis / 450) % 2 == 0L) Color(0xff5a1616) else Color(0xff2a0d0d)
        side == Side.WHITE && isActive -> Color(0xfff0ead2)
        side == Side.WHITE -> Color(0xff43413c)
        isActive -> Color(0xff1d4e6e)
        else -> Color(0xff14161a)
    }
    drawRect(bg, Offset(0f, top), Size(w, height))
    // Dark text ONLY on the cream panel (White running); everywhere else the
    // panels are dark, so the digits are light. (Pause/flag used to get dark
    // text on a dark panel here — invisible.)
    val fg = if (side == Side.WHITE && isActive) DARK else INK
    val sub = if (side == Side.WHITE && isActive) Color(0xff5a5648) else DIM

    // Remaining-time bar along the edge nearest the centre strip.
    val frac = if (init > 0) (rem.toFloat() / init).coerceIn(0f, 1f) else 0f
    val barY = if (side == Side.BLACK) top + height - 10f else top
    drawRect(if (low) RED else ACCENT, Offset(0f, barY), Size(w * frac, 10f))

    // Label row: side name + moves + increment badge.
    val label = if (side == Side.WHITE) "WHITE" else "BLACK"
    val moves = if (side == Side.WHITE) s.movesW else s.movesB
    val ls = max(2, (w * 0.045f / measureText("8", 1).width).toInt())
    drawText(label, Offset(44f, top + 40f), fg, ls)
    val mv = "MOVES $moves"
    val mw = measureText(mv, ls)
    drawText(mv, Offset(w - mw.width - 44f, top + 40f), sub, ls)
    if (isActive) {
        val dotY = top + 40f + measureText(label, ls).height / 2f
        drawCircle(ACCENT, 16f, Offset(44f + measureText(label, ls).width + 42f, dotY))
        drawText("TO MOVE", Offset(44f + measureText(label, ls).width + 76f, top + 40f), ACCENT, ls)
    }

    // The time, as large as the panel allows. Under a minute it gains tenths.
    val timeText = fmt(rem)
    val sample = if (rem >= 60_000L) "88:88" else "8:88.8"
    val unit = measureText(sample, 1)
    val scale = min(
        (w * 0.86f / unit.width).toInt(),
        (height * 0.44f / unit.height).toInt(),
    ).coerceAtLeast(1)
    val tw = measureText(timeText, scale)
    val timeColor = when {
        flagged -> RED
        low && isActive -> if ((millis / 300) % 2 == 0L) RED else fg
        else -> fg
    }
    drawText(timeText, Offset(w / 2f - tw.width / 2f, top + height / 2f - tw.height / 2f), timeColor, scale)

    // Footer row: base + increment summary, or the delay chip while it burns.
    val foot = when {
        s.mode == Mode.RUN && isActive && s.dlyMs > 0 && monoMs() - s.turnStart < s.dlyMs -> "DELAY " + fmtShort(s.dlyMs - (monoMs() - s.turnStart)) + " FREE"
        s.incMs > 0 -> "+" + fmtShort(s.incMs) + " PER MOVE"
        else -> "NO INCREMENT"
    }
    val fs = max(1, (w * 0.033f / measureText("8", 1).width).toInt())
    val fw = measureText(foot, fs)
    drawText(foot, Offset(w / 2f - fw.width / 2f, top + height - measureText(foot, fs).height - 68f), if (foot.startsWith("DELAY")) AMBER else sub, fs)
}

private fun DrawScope.drawSetup(s: ChessState) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xff0d1117), Offset(0f, 0f), Size(w, h))
    val title = "CHESS CLOCK"
    textCentered(title, w / 2f, h * 0.10f, INK, fit(title, 0.86f))
    val sub = "NEXUS 5X - ONE JOB, DONE PROPERLY"
    textCentered(sub, w / 2f, h * 0.10f + measureText(title, fit(title, 0.86f)).height + 28f, DIM, fit(sub, 0.8f))

    val p = PRESETS[s.presetIdx]
    val cy = h * 0.335f
    drawRect(Color(0xff161b22), Offset(w * 0.06f, cy - 175f), Size(w * 0.88f, 390f))
    drawRect(BLUE, Offset(w * 0.06f, cy - 175f), Size(w * 0.88f, 6f))
    val name = "<  " + p.name + "  >"
    textCentered(name, w / 2f, cy - 105f, INK, fit(name, 0.78f))
    textCentered(p.detail, w / 2f, cy + 55f, ACCENT, fit(p.detail, 0.8f))
    val fmtLine = "FORMAT " + (s.presetIdx + 1) + " OF " + PRESETS.size
    textCentered(fmtLine, w / 2f, cy + 140f, DIM, fit(fmtLine, 0.6f))

    val hints = listOf(
        "VOL / ARROWS / TAP TOP-BOTTOM: BROWSE FORMATS",
        "IN GAME: TAP YOUR HALF TO PRESS YOUR CLOCK",
        "VOL+ = WHITE PRESSED - VOL- = BLACK PRESSED",
        "LEAVE IT HERE: A DEMO GAME STARTS ITSELF",
    )
    var y = h * 0.545f
    for (hint in hints) {
        textCentered(hint, w / 2f, y, DIM, fit(hint, 0.92f))
        y += 66f
    }

    drawButton(if (s.presetIdx == PRESETS.lastIndex) "OPEN CUSTOM EDITOR" else "START GAME", SETUP_BTN_Y)
    val orKey = "OR PRESS ENTER"
    textCentered(orKey, w / 2f, h * SETUP_BTN_Y + BTN_H + 24f, DIM, fit(orKey, 0.5f))
}

private fun DrawScope.drawCustom(s: ChessState) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xff0d1117), Offset(0f, 0f), Size(w, h))
    textCentered("CUSTOM FORMAT", w / 2f, h * 0.12f, INK, max(1, (w * 0.07f / measureText("8", 1).width).toInt()))
    textCentered("DIFFERENT TIMES PER SIDE - HANDICAP YOUR GAMES", w / 2f, h * 0.12f + 155f, DIM, max(1, (w * 0.034f / measureText("8", 1).width).toInt()))

    val rows = listOf(
        CField.W_MIN to ("WHITE TIME" to (s.cWMin.toString() + " MIN")),
        CField.B_MIN to ("BLACK TIME" to (s.cBMin.toString() + " MIN")),
        CField.INC to ("INCREMENT (FISCHER)" to ("+" + s.cInc + "S / MOVE")),
        CField.DELAY to ("DELAY (BRONSTEIN)" to (s.cDly.toString() + "S FREE / TURN")),
        CField.START to ("START GAME" to ""),
    )
    var y = h * 0.30f
    val ls = max(1, (w * 0.045f / measureText("8", 1).width).toInt())
    val vs = max(1, (w * 0.055f / measureText("8", 1).width).toInt())
    for ((f, pair) in rows) {
        val sel = s.field == f
        if (sel) drawRect(Color(0xff161b22), Offset(w * 0.05f, y - 18f), Size(w * 0.9f, 108f))
        drawText(pair.first, Offset(w * 0.09f, y), if (sel) INK else DIM, ls)
        if (pair.second.isNotEmpty()) {
            val vw = measureText(pair.second, vs)
            drawText(pair.second, Offset(w * 0.91f - vw.width, y - 8f), if (sel) ACCENT else INK, vs)
        } else {
            textCentered("PRESS ENTER TO PLAY", w / 2f, y, if (sel) ACCENT else DIM, ls)
        }
        y += 128f
    }
    drawButton("START GAME", CUSTOM_BTN_Y)
    val hints = listOf(
        "VOL / TAP TOP-BOTTOM: CHANGE VALUE",
        "ENTER: NEXT FIELD - POWER: BACK",
    )
    var hy = h * (CUSTOM_BTN_Y) + BTN_H + 30f
    for (hint in hints) {
        textCentered(hint, w / 2f, hy, DIM, fit(hint, 0.92f))
        hy += 62f
    }
}
