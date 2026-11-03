package snake

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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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
private val ACCENT = Color(0xff3fb950)     // snake green
private val HEAD = Color(0xff7ee787)
private val FOOD = Color(0xfff85149)
private val AMBER = Color(0xffffb300)
private val BOARDBG = Color(0xff101820)
private val PANEL = Color(0xff161b22)

private fun Color.a(alpha: Float) = copy(alpha = alpha.coerceIn(0f, 1f))

private fun monoMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

// ---------------------------------------------------------------- model

private const val COLS = 18
private const val ROWS = 26

private data class Cell(val x: Int, val y: Int)

private enum class Dir(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    fun opposite() = when (this) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT }
    fun turnRight() = when (this) { UP -> RIGHT; RIGHT -> DOWN; DOWN -> LEFT; LEFT -> UP }
    fun turnLeft() = when (this) { UP -> LEFT; LEFT -> DOWN; DOWN -> RIGHT; RIGHT -> UP }
}

private enum class Mode { TITLE, RUN, PAUSE, OVER }

private class SnakeState {
    var mode by mutableStateOf(Mode.TITLE)
    var snake by mutableStateOf<List<Cell>>(emptyList())
    var food by mutableStateOf(Cell(13, 8))
    var score by mutableStateOf(0)
    var best by mutableStateOf(0)
    var demo by mutableStateOf(false)

    var dir = Dir.RIGHT               // current heading
    var pending = Dir.RIGHT           // heading queued for the next step
    var stepMs = 150L
    var lastStep = 0L
    var bootAt = monoMs()
    var overAt = 0L
    var everKey = false
    var panelW = 1f                   // last drawn size + board geometry,
    var panelH = 1f                   // shared with the touch handler
    var boardL = 0f
    var boardT = 0f
    var cellPx = 10f

    fun reset() {
        val cy = ROWS / 2
        val cx = COLS / 3
        snake = listOf(Cell(cx, cy), Cell(cx - 1, cy), Cell(cx - 2, cy), Cell(cx - 3, cy))
        dir = Dir.RIGHT
        pending = Dir.RIGHT
        score = 0
        stepMs = 150L
        lastStep = monoMs()
        spawnFood()
    }

    fun startGame() {
        reset()
        mode = Mode.RUN
    }

    fun spawnFood() {
        val taken = snake.toSet()
        var c: Cell
        do {
            c = Cell(Random.nextInt(COLS), Random.nextInt(ROWS))
        } while (c in taken)
        food = c
    }

    /** Would moving from the head along [d] kill the snake this step? */
    private fun dies(d: Dir, head: Cell): Boolean {
        val nh = Cell(head.x + d.dx, head.y + d.dy)
        if (nh.x !in 0 until COLS || nh.y !in 0 until ROWS) return true
        val eating = nh == food
        val body = if (eating) snake else snake.dropLast(1)   // the tail vacates unless we eat
        return nh in body
    }

    fun steer(d: Dir) {
        if (d != dir.opposite()) pending = d
    }

    fun tick() {
        val now = monoMs()
        if (!demo && mode == Mode.TITLE && !everKey && now - bootAt > 6_000L) {
            demo = true
            startGame()
            return
        }
        if (demo && mode == Mode.OVER && now - overAt > 3_000L) {
            mode = Mode.TITLE               // bootAt re-arms: the demo loops
            bootAt = now
            demo = false
            return
        }
        if (mode != Mode.RUN) return
        if (now - lastStep < stepMs) return
        lastStep = now
        if (demo) pending = aiDir()
        dir = pending
        val head = snake.first()
        if (dies(dir, head)) {
            if (score > best) best = score
            mode = Mode.OVER
            overAt = now
            return
        }
        val nh = Cell(head.x + dir.dx, head.y + dir.dy)
        val eating = nh == food
        snake = listOf(nh) + if (eating) snake else snake.dropLast(1)
        if (eating) {
            score++
            stepMs = max(90L, 150L - score * 3L)   // a touch faster per food
            spawnFood()
        }
    }

    /** Demo driver: head for the food by Manhattan distance, never into a
     *  wall or the body if any safe heading exists. */
    private fun aiDir(): Dir {
        val head = snake.first()
        val options = Dir.entries
            .filter { it != dir.opposite() }
            .sortedBy { d ->
                val nh = Cell(head.x + d.dx, head.y + d.dy)
                abs(nh.x - food.x) + abs(nh.y - food.y)
            }
        for (d in options) if (!dies(d, head)) return d
        return dir
    }

    fun pauseToggle() {
        when (mode) {
            Mode.RUN -> mode = Mode.PAUSE
            Mode.PAUSE -> { mode = Mode.RUN; lastStep = monoMs() }
            else -> {}
        }
    }

    fun toTitle() {
        mode = Mode.TITLE
        demo = false
        bootAt = monoMs()
    }
}

// ---------------------------------------------------------------- app

/**
 * Snake for a phone that does nothing else.
 *
 * Rules: eat the food to grow (+1 segment each); hitting a boundary wall
 * or your own body ends the game. Speed rises slightly per food.
 *
 * Controls (touchscreen + the phone's only keys, all mapped):
 *  TOUCH   Tap the side of the board to head toward (above = up, ...).
 *  VOL+    Turn right (clockwise).  VOL-  Turn left.
 *  ENTER   Start / pause / resume / play again.  POWER  Pause; on the
 *          game-over screen, back to title.  QEMU: arrow keys steer.
 *  Idle ~6 s on the title screen and a self-playing demo starts (the
 *  QEMU preview runs on it); any real key or tap takes over instantly.
 */
@Composable
internal fun SnakeApp() {
    val state = remember { SnakeState() }

    LaunchedEffect(Unit) {
        while (true) {
            state.tick()
            delay(30)
        }
    }

    DisposableEffect(state) {
        Device.onKey = { code, value ->
            if (value == 1) {  // press (not release / autorepeat)
                state.everKey = true
                if (state.demo) state.demo = false
                when (state.mode) {
                    Mode.TITLE -> when (code) {
                        KEY_ENTER -> state.startGame()
                    }
                    Mode.RUN -> when (code) {
                        KEY_UP -> state.steer(Dir.UP)
                        KEY_DOWN -> state.steer(Dir.DOWN)
                        KEY_LEFT -> state.steer(Dir.LEFT)
                        KEY_RIGHT -> state.steer(Dir.RIGHT)
                        KEY_VOLUMEUP -> state.steer(state.dir.turnRight())
                        KEY_VOLUMEDOWN -> state.steer(state.dir.turnLeft())
                        KEY_ENTER, KEY_POWER -> state.pauseToggle()
                    }
                    Mode.PAUSE -> when (code) {
                        KEY_ENTER, KEY_POWER -> state.pauseToggle()
                    }
                    Mode.OVER -> when (code) {
                        KEY_ENTER -> state.startGame()
                        KEY_POWER -> state.toTitle()
                    }
                }
            }
        }
        onDispose { Device.onKey = null }
    }

    // Continuous frames while playing (food pulse, smooth steps); menus
    // still repaint from the 30 ms state loop.
    val millis by rememberFrameMillis(state.mode == Mode.RUN)

    Canvas(
        Modifier.fillMaxSize().pointerInput { e ->
            if (e.type == PointerEventType.Press) {
                state.everKey = true
                if (state.demo) state.demo = false
                when (state.mode) {
                    Mode.TITLE -> state.startGame()
                    Mode.RUN -> {
                        // Steer toward the tapped side, relative to the
                        // board's centre.
                        val cx = state.boardL + state.cellPx * COLS / 2f
                        val cy = state.boardT + state.cellPx * ROWS / 2f
                        val dx = e.position.x - cx
                        val dy = e.position.y - cy
                        state.steer(
                            if (abs(dx) > abs(dy)) {
                                if (dx > 0) Dir.RIGHT else Dir.LEFT
                            } else {
                                if (dy > 0) Dir.DOWN else Dir.UP
                            },
                        )
                    }
                    Mode.PAUSE -> state.pauseToggle()
                    Mode.OVER -> state.startGame()
                }
            }
        },
    ) {
        state.panelW = size.width
        state.panelH = size.height
        when (state.mode) {
            Mode.TITLE -> drawTitle(state)
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

/** The board's geometry for this frame; also published for touch steering. */
private fun DrawScope.board(s: SnakeState): Triple<Float, Float, Float> {
    val cell = (size.width * 0.92f / COLS)
    val left = (size.width - cell * COLS) / 2f
    val top = (size.height - cell * ROWS) / 2f + size.height * 0.03f
    s.boardL = left
    s.boardT = top
    s.cellPx = cell
    return Triple(left, top, cell)
}

private fun DrawScope.drawTitle(s: SnakeState) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xff0d1117), Offset(0f, 0f), Size(w, h))
    val title = "SNAKE"
    textCentered(title, w / 2f, h * 0.13f, HEAD, fit(title, 0.8f))
    val sub = "EAT - GROW - DO NOT HIT THE WALLS OR YOURSELF"
    textCentered(sub, w / 2f, h * 0.13f + measureText(title, fit(title, 0.8f)).height + 30f, DIM, fit(sub, 0.9f))

    // A decorative snake slithering across the middle.
    val cell = w * 0.92f / COLS
    val y0 = h * 0.36f
    for (i in 0 until 9) {
        val cx = (size.width - cell * COLS) / 2f + cell * (4 + i)
        val cy = y0 + if (i % 2 == 0) 0f else cell * 0.7f
        drawRect(if (i == 8) HEAD else ACCENT, Offset(cx + 3f, cy + 3f), Size(cell - 6f, cell - 6f))
    }
    drawCircle(FOOD, cell * 0.32f, Offset((size.width - cell * COLS) / 2f + cell * 14.5f, y0 + cell * 0.4f))

    val hints = listOf(
        "TOUCH: TAP A SIDE OF THE BOARD TO STEER",
        "VOL+ TURN RIGHT - VOL- TURN LEFT",
        "ARROWS WORK TOO - ENTER PAUSES",
        "LEAVE IT HERE: A DEMO GAME STARTS ITSELF",
    )
    var y = h * 0.52f
    for (hint in hints) {
        textCentered(hint, w / 2f, y, DIM, fit(hint, 0.92f))
        y += 64f
    }
    if (s.best > 0) {
        val b = "BEST " + s.best
        textCentered(b, w / 2f, y + 6f, AMBER, fit(b, 0.5f))
    }

    // The big START button (any tap starts too; this is the obvious target).
    val bx = w * 0.14f
    val bw = w * 0.72f
    val by = h * 0.775f
    val bh = 148f
    drawRect(Color(0xff14532d), Offset(bx, by), Size(bw, bh))
    drawRect(ACCENT, Offset(bx, by), Size(bw, 5f))
    drawRect(ACCENT, Offset(bx, by + bh - 5f), Size(bw, 5f))
    drawRect(ACCENT, Offset(bx, by), Size(5f, bh))
    drawRect(ACCENT, Offset(bx + bw - 5f, by), Size(5f, bh))
    val label = "START GAME"
    val sc = fit(label, 0.62f, 5)
    textCentered(label, w / 2f, by + bh / 2f - measureText(label, sc).height / 2f, INK, sc)
    val orKey = "OR PRESS ENTER"
    textCentered(orKey, w / 2f, by + bh + 24f, DIM, fit(orKey, 0.5f))
}

private fun DrawScope.drawGame(s: SnakeState, millis: Long) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xff0d1117), Offset(0f, 0f), Size(w, h))
    val (left, top, cell) = board(s)

    // HUD: score left, best right.
    val hs = max(1, (w * 0.038f / measureText("8", 1).width).toInt())
    val score = "SCORE " + s.score
    drawText(score, Offset(left, top - measureText(score, hs).height - 26f), INK, hs)
    val best = "BEST " + max(s.best, s.score)
    val bw = measureText(best, hs)
    drawText(best, Offset(left + cell * COLS - bw.width, top - bw.height - 26f), DIM, hs)
    if (s.demo) {
        val d = "DEMO"
        textCentered(d, w / 2f, top - measureText(d, hs).height - 26f, AMBER, hs)
    }

    // Board + frame.
    drawRect(BOARDBG, Offset(left, top), Size(cell * COLS, cell * ROWS))
    drawRect(DIM.a(0.6f), Offset(left - 3f, top - 3f), Size(cell * COLS + 6f, 3f))
    drawRect(DIM.a(0.6f), Offset(left - 3f, top + cell * ROWS + 3f), Size(cell * COLS + 6f, 3f))
    drawRect(DIM.a(0.6f), Offset(left - 3f, top - 3f), Size(3f, cell * ROWS + 6f))
    drawRect(DIM.a(0.6f), Offset(left + cell * COLS + 3f, top - 3f), Size(3f, cell * ROWS + 6f))

    // Food, gently pulsing.
    val pulse = 0.30f + 0.07f * ((millis / 300) % 2).toFloat()
    drawCircle(
        FOOD, cell * pulse,
        Offset(left + (s.food.x + 0.5f) * cell, top + (s.food.y + 0.5f) * cell),
    )

    // Snake: head bright, body green, tail dimmer.
    val n = s.snake.size
    for ((i, c) in s.snake.withIndex()) {
        val inset = if (i == 0) 2f else 3.5f
        val col = when {
            i == 0 -> HEAD
            i > n - 3 && n > 5 -> ACCENT.a(0.55f)
            else -> ACCENT
        }
        drawRect(col, Offset(left + c.x * cell + inset, top + c.y * cell + inset), Size(cell - inset * 2, cell - inset * 2))
        if (i == 0) {  // eyes, so the heading reads at a glance
            val ex = left + c.x * cell
            val ey = top + c.y * cell
            val r = cell * 0.07f
            val fx = when (s.dir) { Dir.LEFT -> 0.30f; Dir.RIGHT -> 0.70f; else -> 0.5f }
            val fy = when (s.dir) { Dir.UP -> 0.30f; Dir.DOWN -> 0.70f; else -> 0.5f }
            drawCircle(Color(0xff0d1117), r, Offset(ex + cell * (fx - 0.16f), ey + cell * (fy - 0.16f)))
            drawCircle(Color(0xff0d1117), r, Offset(ex + cell * (fx + 0.16f), ey + cell * (fy + 0.16f)))
        }
    }

    // Footer hint.
    val hint = "TAP SIDES / VOL: STEER - ENTER: PAUSE"
    textCentered(hint, w / 2f, top + cell * ROWS + 34f, DIM, fit(hint, 0.9f))

    if (s.mode == Mode.PAUSE) {
        drawRect(Color(0xff000000).a(0.55f), Offset(0f, 0f), Size(w, h))
        val p = "PAUSED"
        textCentered(p, w / 2f, h * 0.42f, INK, fit(p, 0.7f))
        val r = "ENTER OR TAP: RESUME"
        textCentered(r, w / 2f, h * 0.42f + measureText(p, fit(p, 0.7f)).height + 36f, ACCENT, fit(r, 0.8f))
    }
    if (s.mode == Mode.OVER) {
        drawRect(Color(0xff000000).a(0.55f), Offset(0f, 0f), Size(w, h))
        val g = "GAME OVER"
        textCentered(g, w / 2f, h * 0.36f, FOOD, fit(g, 0.8f))
        val sc = "SCORE " + s.score + "  -  BEST " + max(s.best, s.score)
        textCentered(sc, w / 2f, h * 0.36f + measureText(g, fit(g, 0.8f)).height + 40f, INK, fit(sc, 0.8f))
        val again = "TAP OR ENTER: PLAY AGAIN"
        textCentered(again, w / 2f, h * 0.58f, ACCENT, fit(again, 0.85f))
        val back = "POWER: TITLE"
        textCentered(back, w / 2f, h * 0.58f + 74f, DIM, fit(back, 0.5f))
    }
}
