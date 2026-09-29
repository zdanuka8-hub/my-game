package com.example.territoryclash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(
    context: Context,
    private val music: BackgroundMusic
) : View(context) {

    data class Cell(
        var terrain: Int = LAND,
        var owner: Int = NEUTRAL,
        var strength: Float = 3f,
        var structure: Int = STRUCT_NONE,
        var pulse: Float = 0f
    )

    data class FactionState(
        var balance: Float = 330f,
        var money: Float = 240f,
        var nukes: Int = 0
    )

    companion object {
        const val LAND = 0
        const val WATER = 1
        const val NEUTRAL = -1
        const val WATER_OWNER = -2

        const val STRUCT_NONE = 0
        const val STRUCT_CITY = 1
        const val STRUCT_FACTORY = 2
        const val STRUCT_PORT = 3
        const val STRUCT_FORT = 4
        const val STRUCT_SILO = 5

        const val COST_CITY = 140
        const val COST_FACTORY = 180
        const val COST_PORT = 160
        const val COST_FORT = 120
        const val COST_SILO = 300
        const val COST_NUKE = 420
    }

    private val cols = 38
    private val rows = 22
    private val factions = 6

    private val cells = Array(rows) { Array(cols) { Cell() } }
    private val states = Array(factions) { FactionState() }

    private val colors = intArrayOf(
        Color.rgb(52, 151, 255),
        Color.rgb(237, 77, 91),
        Color.rgb(255, 169, 64),
        Color.rgb(174, 91, 235),
        Color.rgb(71, 205, 129),
        Color.rgb(236, 102, 196)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var selectedX = -1
    private var selectedY = -1
    private var cursorX = 4
    private var cursorY = rows / 2
    private var attackPercent = 0.35f

    private var dragging = false
    private var lastDragX = -1
    private var lastDragY = -1

    private var last = System.nanoTime()
    private var botClock = 0f
    private var economyClock = 0f
    private var gameOver: String? = null
    private var nukeTargetMode = false
    private var status = "Расширяй страну, копи армию, строй экономику"
    private var statusTimer = 5f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        reset()
    }

    private fun reset() {
        for (id in 0 until factions) {
            states[id].balance = 330f
            states[id].money = 240f
            states[id].nukes = 0
        }

        generateMap()

        val starts = listOf(
            4 to rows / 2,
            cols - 5 to rows / 2,
            cols / 2 to 3,
            cols / 2 to rows - 4,
            10 to 4,
            cols - 11 to rows - 5
        )

        starts.forEachIndexed { id, (sx, sy) ->
            carveStart(id, sx, sy)
        }

        selectedX = -1
        selectedY = -1
        cursorX = 4
        cursorY = rows / 2
        attackPercent = 0.35f
        dragging = false
        nukeTargetMode = false
        gameOver = null
        status = "Зажми и веди пальцем по соседним клеткам — территория будет захватываться цепочкой."
        statusTimer = 7f
        botClock = 0f
        economyClock = 0f
        last = System.nanoTime()
        invalidate()
    }

    private fun generateMap() {
        for (y in 0 until rows) for (x in 0 until cols) {
            val nx = x.toFloat() / cols
            val ny = y.toFloat() / rows
            val coast =
                sin(nx * 13.0).toFloat() * 0.12f +
                sin(ny * 17.0).toFloat() * 0.10f +
                sin((nx + ny) * 19.0).toFloat() * 0.07f

            val lakeA = ((x - 13) * (x - 13) + (y - 6) * (y - 6)) < 12
            val lakeB = ((x - 25) * (x - 25) + (y - 15) * (y - 15)) < 15
            val channel = x in 18..19 && y in 6..15
            val edgeSea = y == 0 || y == rows - 1 || x == 0 || x == cols - 1
            val noiseSea = coast > 0.21f && (x + y) % 3 != 0

            val c = cells[y][x]
            if (lakeA || lakeB || channel || edgeSea || noiseSea) {
                c.terrain = WATER
                c.owner = WATER_OWNER
                c.strength = 0f
                c.structure = STRUCT_NONE
            } else {
                c.terrain = LAND
                c.owner = NEUTRAL
                c.strength = 2.5f + Random.nextFloat() * 7f
                c.structure = STRUCT_NONE
            }
            c.pulse = 0f
        }
    }

    private fun carveStart(id: Int, sx: Int, sy: Int) {
        for (dy in -2..2) for (dx in -2..2) {
            if (abs(dx) + abs(dy) > 3) continue
            val x = (sx + dx).coerceIn(1, cols - 2)
            val y = (sy + dy).coerceIn(1, rows - 2)
            val c = cells[y][x]
            c.terrain = LAND
            c.owner = id
            c.strength = if (dx == 0 && dy == 0) 18f else 7f
            c.structure = if (dx == 0 && dy == 0) STRUCT_CITY else STRUCT_NONE
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
        last = now

        update(dt)
        drawWorld(canvas)
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        if (statusTimer > 0f) statusTimer -= dt
        for (row in cells) for (c in row) c.pulse = max(0f, c.pulse - dt * 2.8f)

        if (gameOver != null) return

        economyClock += dt
        if (economyClock >= 0.20f) {
            val step = economyClock
            economyClock = 0f

            for (id in 0 until factions) {
                if (land(id) <= 0) continue

                val cities = countStructure(id, STRUCT_CITY)
                val factories = countStructure(id, STRUCT_FACTORY)
                val ports = countStructure(id, STRUCT_PORT)

                val balanceGrowth =
                    2.8f +
                    land(id) * 0.075f +
                    cities * 1.35f +
                    factories * 2.75f

                val moneyGrowth =
                    1.7f +
                    land(id) * 0.035f +
                    cities * 1.25f +
                    ports * 0.65f

                states[id].balance = min(balanceCap(id), states[id].balance + balanceGrowth * step)
                states[id].money += moneyGrowth * step
            }

            for (y in 0 until rows) for (x in 0 until cols) {
                val c = cells[y][x]
                if (c.owner < 0 || c.terrain != LAND) continue
                val regen = when (c.structure) {
                    STRUCT_FORT -> 0.75f
                    STRUCT_CITY -> 0.32f
                    STRUCT_SILO -> 0.16f
                    else -> 0.09f
                }
                c.strength = min(55f, c.strength + regen * step)
            }
        }

        botClock += dt
        if (botClock >= 0.42f) {
            botClock = 0f
            for (id in 1 until factions) botTurn(id)
            checkEnd()
        }
    }

    private fun balanceCap(id: Int): Float {
        return 260f +
            land(id) * 19f +
            countStructure(id, STRUCT_CITY) * 85f +
            countStructure(id, STRUCT_FACTORY) * 55f
    }

    private fun botTurn(id: Int) {
        if (land(id) <= 0) return

        botBuild(id)

        if (states[id].nukes > 0 && Random.nextFloat() < 0.055f) {
            strongestEnemyTarget(id)?.let {
                launchNuke(id, it.first, it.second)
                return
            }
        }

        if (states[id].balance < 28f) return

        val sources = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            if (c.owner != id) continue

            val border = neighbors(x, y).any {
                val t = cells[it.second][it.first]
                t.terrain == LAND && t.owner != id
            }

            val naval = c.structure == STRUCT_PORT && coastalTargetsFrom(x, y, id).isNotEmpty()
            if (border || naval) sources += x to y
        }

        if (sources.isEmpty()) return

        val source = sources.random()
        val sx = source.first
        val sy = source.second

        val normalTargets = neighbors(sx, sy)
            .filter {
                val t = cells[it.second][it.first]
                t.terrain == LAND && t.owner != id
            }
            .sortedBy {
                val t = cells[it.second][it.first]
                effectiveDefense(t) + if (t.owner == NEUTRAL) -4f else 8f
            }

        val target = if (normalTargets.isNotEmpty()) {
            normalTargets.first()
        } else {
            coastalTargetsFrom(sx, sy, id)
                .minByOrNull { effectiveDefense(cells[it.second][it.first]) }
        } ?: return

        val pct = if (cells[target.second][target.first].owner == NEUTRAL) {
            0.18f + Random.nextFloat() * 0.12f
        } else {
            0.25f + Random.nextFloat() * 0.22f
        }

        attack(sx, sy, target.first, target.second, id, pct)
    }

    private fun botBuild(id: Int) {
        val state = states[id]
        val own = mutableListOf<Pair<Int, Int>>()
        val border = mutableListOf<Pair<Int, Int>>()
        val coast = mutableListOf<Pair<Int, Int>>()

        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            if (c.owner != id || c.structure != STRUCT_NONE) continue
            own += x to y
            if (isCoastal(x, y)) coast += x to y
            if (neighbors(x, y).any {
                    val n = cells[it.second][it.first]
                    n.terrain == LAND && n.owner != id
                }) {
                border += x to y
            }
        }

        if (countStructure(id, STRUCT_SILO) > 0 &&
            state.money >= COST_NUKE &&
            state.nukes < 2 &&
            Random.nextFloat() < 0.10f
        ) {
            state.money -= COST_NUKE
            state.nukes++
            return
        }

        if (own.isEmpty() || Random.nextFloat() > 0.22f) return

        val plan = when {
            land(id) >= 30 &&
                state.money >= COST_SILO &&
                countStructure(id, STRUCT_SILO) == 0 ->
                Triple(STRUCT_SILO, COST_SILO, own.random())

            state.money >= COST_FACTORY &&
                countStructure(id, STRUCT_FACTORY) < max(1, land(id) / 22) ->
                Triple(STRUCT_FACTORY, COST_FACTORY, own.random())

            state.money >= COST_CITY &&
                countStructure(id, STRUCT_CITY) < max(2, land(id) / 18) ->
                Triple(STRUCT_CITY, COST_CITY, own.random())

            state.money >= COST_PORT &&
                coast.isNotEmpty() &&
                countStructure(id, STRUCT_PORT) < 2 ->
                Triple(STRUCT_PORT, COST_PORT, coast.random())

            state.money >= COST_FORT && border.isNotEmpty() ->
                Triple(STRUCT_FORT, COST_FORT, border.random())

            else -> null
        }

        plan?.let { (structure, cost, pos) ->
            cells[pos.second][pos.first].structure = structure
            state.money -= cost
        }
    }

    private fun drawWorld(canvas: Canvas) {
        canvas.drawColor(Color.rgb(10, 15, 23))

        val top = 78f
        val toolbarTop = height - 132f
        val bottom = toolbarTop - 4f
        val cw = width / cols.toFloat()
        val ch = (bottom - top) / rows.toFloat()

        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            paint.style = Paint.Style.FILL
            paint.color = when {
                c.terrain == WATER -> {
                    val wave = ((x * 7 + y * 11) % 9) * 2
                    Color.rgb(20 + wave, 57 + wave, 86 + wave)
                }
                c.owner < 0 -> {
                    val v = (50 + min(24f, c.strength * 1.8f)).toInt()
                    Color.rgb(v, v + 5, v + 12)
                }
                else -> territoryColor(colors[c.owner], c.strength, c.pulse)
            }

            val left = x * cw
            val cellTop = top + y * ch
            canvas.drawRect(left, cellTop, left + cw + 0.4f, cellTop + ch + 0.4f, paint)

            if (c.terrain == LAND) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (c.owner >= 0) 0.8f else 0.45f
                paint.color = Color.argb(if (c.owner >= 0) 55 else 28, 0, 0, 0)
                canvas.drawRect(left, cellTop, left + cw, cellTop + ch, paint)

                if (c.structure != STRUCT_NONE && cw > 17f && ch > 14f) {
                    drawStructure(canvas, c, left, cellTop, cw, ch)
                }
            }
        }

        drawSelection(canvas, top, bottom, cw, ch)

        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        text.textSize = 23f
        canvas.drawText(
            "Territory Clash 0.3   Земля ${land(0)}   Армия ${states[0].balance.toInt()}/${balanceCap(0).toInt()}   $${states[0].money.toInt()}   ☢${states[0].nukes}",
            13f,
            29f,
            text
        )

        text.textSize = 15f
        text.color = Color.rgb(205, 220, 238)
        canvas.drawText(
            "Зажми и веди для захвата • атака тратит общий резерв • порт = дальняя высадка • 1–5 стройки • N/K ядерка",
            13f,
            55f,
            text
        )

        if (nukeTargetMode) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 20f
            text.color = Color.rgb(255, 226, 84)
            canvas.drawText("☢ ВЫБЕРИ ЦЕЛЬ ЯДЕРНОГО УДАРА", width / 2f, 75f, text)
        }

        drawToolbar(canvas, toolbarTop)

        gameOver?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 0, 0, 0)
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            text.textSize = 40f
            canvas.drawText(it, width / 2f, (top + bottom) / 2f - 8f, text)
            text.textSize = 20f
            canvas.drawText("R — новая игра", width / 2f, (top + bottom) / 2f + 30f, text)
        }
    }

    private fun territoryColor(base: Int, strength: Float, pulse: Float): Int {
        val r = Color.red(base)
        val g = Color.green(base)
        val b = Color.blue(base)
        val factor = (0.72f + min(0.20f, strength / 250f) + pulse * 0.10f).coerceIn(0.55f, 1.08f)
        return Color.rgb(
            (r * factor).toInt().coerceIn(0, 255),
            (g * factor).toInt().coerceIn(0, 255),
            (b * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun drawSelection(canvas: Canvas, top: Float, bottom: Float, cw: Float, ch: Float) {
        fun outline(x: Int, y: Int, color: Int, stroke: Float) {
            if (x !in 0 until cols || y !in 0 until rows) return
            val left = x * cw
            val cellTop = top + y * ch
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = color
            canvas.drawRect(
                left + stroke / 2f,
                cellTop + stroke / 2f,
                left + cw - stroke / 2f,
                cellTop + ch - stroke / 2f,
                paint
            )
        }

        outline(selectedX, selectedY, Color.WHITE, 3.5f)
        outline(cursorX, cursorY, Color.rgb(255, 230, 90), 2f)

        if (selectedX >= 0 && selectedY >= 0) {
            val source = cells[selectedY][selectedX]
            if (source.structure == STRUCT_PORT && source.owner == 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.3f
                paint.color = Color.argb(100, 130, 235, 255)
                val cx = selectedX * cw + cw / 2
                val cy = top + selectedY * ch + ch / 2
                val radius = min(width.toFloat(), bottom - top) * 0.24f
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
    }

    private fun drawStructure(
        canvas: Canvas,
        cell: Cell,
        left: Float,
        top: Float,
        cw: Float,
        ch: Float
    ) {
        val cx = left + cw * 0.5f
        val cy = top + ch * 0.50f
        val s = min(cw, ch) * 0.30f

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(225, 245, 245, 245)

        when (cell.structure) {
            STRUCT_CITY -> {
                canvas.drawRect(cx - s, cy - s * 0.45f, cx - s * 0.25f, cy + s, paint)
                canvas.drawRect(cx + s * 0.05f, cy - s, cx + s * 0.75f, cy + s, paint)
            }

            STRUCT_FACTORY -> {
                canvas.drawRect(cx - s, cy - s * 0.2f, cx + s, cy + s, paint)
                canvas.drawRect(cx + s * 0.45f, cy - s, cx + s * 0.85f, cy + s * 0.1f, paint)
            }

            STRUCT_PORT -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.5f, s * 0.22f)
                paint.color = Color.rgb(160, 240, 255)
                canvas.drawCircle(cx, cy - s * 0.2f, s * 0.45f, paint)
                canvas.drawLine(cx, cy + s * 0.15f, cx, cy + s, paint)
                canvas.drawLine(cx - s * 0.65f, cy + s * 0.55f, cx + s * 0.65f, cy + s * 0.55f, paint)
            }

            STRUCT_FORT -> {
                paint.color = Color.rgb(255, 220, 150)
                canvas.drawRect(cx - s, cy - s * 0.65f, cx + s, cy + s, paint)
                paint.color = Color.rgb(90, 72, 52)
                canvas.drawRect(cx - s * 0.35f, cy + s * 0.15f, cx + s * 0.35f, cy + s, paint)
            }

            STRUCT_SILO -> {
                paint.color = Color.rgb(255, 145, 145)
                val path = android.graphics.Path()
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s * 0.70f, cy + s)
                path.lineTo(cx - s * 0.70f, cy + s)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawToolbar(canvas: Canvas, toolbarTop: Float) {
        val labels = arrayOf(
            "ГОРОД\n$COST_CITY",
            "ЗАВОД\n$COST_FACTORY",
            "ПОРТ\n$COST_PORT",
            "ФОРТ\n$COST_FORT",
            "ШАХТА\n$COST_SILO",
            "☢\n$COST_NUKE",
            "ЦЕЛЬ",
            if (music.muted) "♪ OFF" else "♪ ON"
        )

        val buttonW = width / labels.size.toFloat()
        val top = toolbarTop + 4f
        val bottom = toolbarTop + 63f

        for (i in labels.indices) {
            paint.style = Paint.Style.FILL
            paint.color = when {
                i == 6 && nukeTargetMode -> Color.rgb(121, 77, 28)
                i == 7 && !music.muted -> Color.rgb(36, 76, 61)
                else -> Color.rgb(31, 39, 52)
            }
            canvas.drawRoundRect(
                i * buttonW + 2f,
                top,
                (i + 1) * buttonW - 2f,
                bottom,
                7f,
                7f,
                paint
            )

            val parts = labels[i].split("\n")
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            text.textSize = 12.5f
            canvas.drawText(parts[0], i * buttonW + buttonW / 2f, top + 22f, text)

            if (parts.size > 1) {
                text.textSize = 11f
                text.color = Color.rgb(180, 194, 210)
                canvas.drawText(parts[1], i * buttonW + buttonW / 2f, top + 43f, text)
            }
        }

        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 18f
        canvas.drawText(
            "−      АТАКА ${(attackPercent * 100).toInt()}%      +",
            width / 2f,
            toolbarTop + 91f,
            text
        )

        text.textSize = 13.5f
        text.color = if (statusTimer > 0f) Color.rgb(206, 225, 255) else Color.LTGRAY
        canvas.drawText(
            if (statusTimer > 0f) status else "Выбери свою территорию и веди в сторону цели",
            width / 2f,
            toolbarTop + 119f,
            text
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        val toolbarTop = height - 132f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y >= toolbarTop) {
                    handleToolbar(event.x, event.y, toolbarTop)
                    dragging = false
                    return true
                }

                val pos = worldCell(event.x, event.y, toolbarTop) ?: return true
                cursorX = pos.first
                cursorY = pos.second

                if (nukeTargetMode) {
                    tryPlayerNuke(pos.first, pos.second)
                    dragging = false
                    return true
                }

                handleWorldPress(pos.first, pos.second)
                dragging = selectedX >= 0 && selectedY >= 0
                lastDragX = pos.first
                lastDragY = pos.second
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging || nukeTargetMode) return true
                val pos = worldCell(event.x, event.y, toolbarTop) ?: return true
                cursorX = pos.first
                cursorY = pos.second

                if (pos.first == lastDragX && pos.second == lastDragY) return true
                lastDragX = pos.first
                lastDragY = pos.second
                dragInto(pos.first, pos.second)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                lastDragX = -1
                lastDragY = -1
            }
        }

        return true
    }

    private fun worldCell(xPx: Float, yPx: Float, toolbarTop: Float): Pair<Int, Int>? {
        val top = 78f
        val bottom = toolbarTop - 4f
        if (yPx !in top..bottom) return null

        val x = (xPx / (width / cols.toFloat())).toInt().coerceIn(0, cols - 1)
        val y = ((yPx - top) / ((bottom - top) / rows)).toInt().coerceIn(0, rows - 1)
        return x to y
    }

    private fun handleWorldPress(x: Int, y: Int) {
        if (gameOver != null) return
        val c = cells[y][x]
        if (c.terrain == WATER) {
            flash("Это море. Для дальних атак выбери свой порт и береговую цель.")
            return
        }

        if (c.owner == 0) {
            selectedX = x
            selectedY = y
            return
        }

        if (selectedX >= 0 && selectedY >= 0) {
            if (canAttack(selectedX, selectedY, x, y, 0)) {
                attack(selectedX, selectedY, x, y, 0, attackPercent)
                if (cells[y][x].owner == 0) {
                    selectedX = x
                    selectedY = y
                }
                checkEnd()
            }
        }
    }

    private fun dragInto(x: Int, y: Int) {
        if (selectedX < 0 || selectedY < 0) return
        val target = cells[y][x]

        if (target.terrain != LAND) return

        if (target.owner == 0 && abs(selectedX - x) + abs(selectedY - y) == 1) {
            selectedX = x
            selectedY = y
            return
        }

        if (!canAttack(selectedX, selectedY, x, y, 0)) return

        attack(selectedX, selectedY, x, y, 0, attackPercent)
        if (cells[y][x].owner == 0) {
            selectedX = x
            selectedY = y
        }
        checkEnd()
    }

    private fun handleToolbar(x: Float, y: Float, toolbarTop: Float) {
        if (y <= toolbarTop + 66f) {
            val index = (x / (width / 8f)).toInt().coerceIn(0, 7)
            when (index) {
                0 -> buildPlayerStructure(STRUCT_CITY, COST_CITY)
                1 -> buildPlayerStructure(STRUCT_FACTORY, COST_FACTORY)
                2 -> buildPlayerStructure(STRUCT_PORT, COST_PORT)
                3 -> buildPlayerStructure(STRUCT_FORT, COST_FORT)
                4 -> buildPlayerStructure(STRUCT_SILO, COST_SILO)
                5 -> buyPlayerNuke()
                6 -> toggleNukeMode()
                7 -> flash(if (music.toggle()) "Музыка включена" else "Музыка выключена")
            }
        } else if (y <= toolbarTop + 103f) {
            attackPercent = if (x < width / 2f) {
                max(0.10f, attackPercent - 0.05f)
            } else {
                min(0.90f, attackPercent + 0.05f)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            attackPercent = (
                attackPercent +
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f) 0.05f else -0.05f
                ).coerceIn(0.10f, 0.90f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT -> cursorX = max(0, cursorX - 1)
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX = min(cols - 1, cursorX + 1)
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP -> cursorY = max(0, cursorY - 1)
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN -> cursorY = min(rows - 1, cursorY + 1)

            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (nukeTargetMode) tryPlayerNuke(cursorX, cursorY)
                else handleWorldPress(cursorX, cursorY)
            }

            KeyEvent.KEYCODE_1 -> buildPlayerStructure(STRUCT_CITY, COST_CITY)
            KeyEvent.KEYCODE_2 -> buildPlayerStructure(STRUCT_FACTORY, COST_FACTORY)
            KeyEvent.KEYCODE_3 -> buildPlayerStructure(STRUCT_PORT, COST_PORT)
            KeyEvent.KEYCODE_4 -> buildPlayerStructure(STRUCT_FORT, COST_FORT)
            KeyEvent.KEYCODE_5 -> buildPlayerStructure(STRUCT_SILO, COST_SILO)
            KeyEvent.KEYCODE_N -> buyPlayerNuke()
            KeyEvent.KEYCODE_K -> toggleNukeMode()
            KeyEvent.KEYCODE_M -> music.toggle()

            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_NUMPAD_ADD ->
                attackPercent = min(0.90f, attackPercent + 0.05f)

            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT ->
                attackPercent = max(0.10f, attackPercent - 0.05f)

            KeyEvent.KEYCODE_R -> reset()

            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BACK -> {
                nukeTargetMode = false
                selectedX = -1
                selectedY = -1
            }

            else -> return super.onKeyDown(code, event)
        }
        return true
    }

    private fun attack(
        sx: Int,
        sy: Int,
        tx: Int,
        ty: Int,
        id: Int,
        pct: Float
    ) {
        if (!canAttack(sx, sy, tx, ty, id)) return

        val attacker = states[id]
        if (attacker.balance <= 6f) return

        val source = cells[sy][sx]
        val target = cells[ty][tx]

        if (target.owner == id) return

        val spend = min(
            attacker.balance - 5f,
            max(3f, attacker.balance * pct)
        )
        if (spend <= 0f) return

        attacker.balance -= spend

        val defense = effectiveDefense(target)
        if (target.owner >= 0 && target.owner != id) {
            states[target.owner].balance = max(0f, states[target.owner].balance - spend * 0.10f)
        }

        if (spend > defense) {
            val previousOwner = target.owner
            target.owner = id
            target.strength = min(45f, 2f + (spend - defense) * 0.17f)
            target.pulse = 1f

            if (target.structure == STRUCT_SILO && previousOwner >= 0 && previousOwner != id) {
                target.structure = STRUCT_NONE
            }

            attacker.balance = min(balanceCap(id), attacker.balance + min(12f, spend * 0.06f))
        } else {
            target.strength = max(0.5f, target.strength - spend / defenseMultiplier(target))
            target.pulse = 0.65f
        }

        source.pulse = 0.35f
    }

    private fun canAttack(sx: Int, sy: Int, tx: Int, ty: Int, id: Int): Boolean {
        if (sx !in 0 until cols || sy !in 0 until rows ||
            tx !in 0 until cols || ty !in 0 until rows
        ) return false

        val source = cells[sy][sx]
        val target = cells[ty][tx]

        if (source.owner != id || source.terrain != LAND || target.terrain != LAND) return false
        if (target.owner == id) return false

        if (abs(sx - tx) + abs(sy - ty) == 1) return true

        if (source.structure == STRUCT_PORT &&
            isCoastal(sx, sy) &&
            isCoastal(tx, ty)
        ) {
            val distance = abs(sx - tx) + abs(sy - ty)
            return distance in 2..11
        }

        return false
    }

    private fun effectiveDefense(cell: Cell): Float {
        val ownerReserve = if (cell.owner >= 0) states[cell.owner].balance * 0.014f else 0f
        return (cell.strength + ownerReserve) * defenseMultiplier(cell)
    }

    private fun defenseMultiplier(cell: Cell): Float = when (cell.structure) {
        STRUCT_FORT -> 2.15f
        STRUCT_CITY -> 1.25f
        STRUCT_SILO -> 1.12f
        else -> 1f
    }

    private fun buildPlayerStructure(structure: Int, cost: Int) {
        val pos = selectedOwnedCell() ?: run {
            flash("Сначала выбери свою территорию.")
            return
        }

        val c = cells[pos.second][pos.first]
        if (c.structure != STRUCT_NONE) {
            flash("Здесь уже есть постройка.")
            return
        }

        if (structure == STRUCT_PORT && !isCoastal(pos.first, pos.second)) {
            flash("Порт можно строить только у воды.")
            return
        }

        if (states[0].money < cost) {
            flash("Не хватает денег: нужно $cost.")
            return
        }

        states[0].money -= cost
        c.structure = structure
        c.pulse = 1f

        flash(
            when (structure) {
                STRUCT_CITY -> "Город: больше денег, армии и лимита резерва."
                STRUCT_FACTORY -> "Завод: заметно ускоряет прирост армии."
                STRUCT_PORT -> "Порт: выбери его и тапни по чужому побережью."
                STRUCT_FORT -> "Форт: защита территории усилена более чем вдвое."
                STRUCT_SILO -> "Ракетная шахта готова — теперь можно собрать ядерку."
                else -> "Постройка готова."
            }
        )
    }

    private fun buyPlayerNuke() {
        val pos = selectedOwnedCell() ?: run {
            flash("Выбери свою ракетную шахту.")
            return
        }

        if (cells[pos.second][pos.first].structure != STRUCT_SILO) {
            flash("Ядерку можно собирать только в ракетной шахте.")
            return
        }

        if (states[0].money < COST_NUKE) {
            flash("На ядерку нужно $COST_NUKE.")
            return
        }

        states[0].money -= COST_NUKE
        states[0].nukes++
        flash("Ядерная боеголовка готова. Нажми ЦЕЛЬ или K.")
    }

    private fun toggleNukeMode() {
        if (states[0].nukes <= 0) {
            nukeTargetMode = false
            flash("Нет готовой ядерной боеголовки.")
            return
        }

        nukeTargetMode = !nukeTargetMode
        flash(if (nukeTargetMode) "Выбери любую вражескую наземную цель." else "Ядерный режим отменён.")
    }

    private fun tryPlayerNuke(x: Int, y: Int) {
        if (states[0].nukes <= 0) {
            nukeTargetMode = false
            flash("Ядерок больше нет.")
            return
        }

        val target = cells[y][x]
        if (target.terrain != LAND || target.owner == 0) {
            flash("Нужна вражеская или нейтральная наземная цель.")
            return
        }

        launchNuke(0, x, y)
        nukeTargetMode = false
        selectedX = -1
        selectedY = -1
        flash("☢ Удар нанесён. Центр взрыва потерял владельца.")
        checkEnd()
    }

    private fun launchNuke(id: Int, x: Int, y: Int) {
        if (states[id].nukes <= 0) return
        states[id].nukes--

        for (yy in max(0, y - 2)..min(rows - 1, y + 2)) {
            for (xx in max(0, x - 2)..min(cols - 1, x + 2)) {
                val c = cells[yy][xx]
                if (c.terrain != LAND) continue

                val d = abs(xx - x) + abs(yy - y)
                when {
                    d == 0 -> {
                        c.owner = NEUTRAL
                        c.strength = 3f
                        c.structure = STRUCT_NONE
                        c.pulse = 1f
                    }

                    d <= 2 -> {
                        c.strength = max(1f, c.strength * 0.18f)
                        c.pulse = 1f
                        if (Random.nextFloat() < 0.72f) c.structure = STRUCT_NONE
                        if (c.owner >= 0) {
                            states[c.owner].balance *= 0.92f
                        }
                    }

                    else -> {
                        c.strength = max(1f, c.strength * 0.55f)
                        c.pulse = 0.8f
                    }
                }
            }
        }
    }

    private fun strongestEnemyTarget(id: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestScore = -1f

        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            if (c.terrain != LAND || c.owner < 0 || c.owner == id) continue

            val score =
                c.strength +
                when (c.structure) {
                    STRUCT_CITY -> 55f
                    STRUCT_FACTORY -> 48f
                    STRUCT_SILO -> 95f
                    STRUCT_PORT -> 34f
                    STRUCT_FORT -> 40f
                    else -> 0f
                } +
                states[c.owner].balance * 0.03f

            if (score > bestScore) {
                bestScore = score
                best = x to y
            }
        }

        return best
    }

    private fun selectedOwnedCell(): Pair<Int, Int>? {
        if (selectedX in 0 until cols &&
            selectedY in 0 until rows &&
            cells[selectedY][selectedX].owner == 0
        ) {
            return selectedX to selectedY
        }

        if (cursorX in 0 until cols &&
            cursorY in 0 until rows &&
            cells[cursorY][cursorX].owner == 0
        ) {
            return cursorX to cursorY
        }

        return null
    }

    private fun coastalTargetsFrom(x: Int, y: Int, id: Int): List<Pair<Int, Int>> {
        if (cells[y][x].structure != STRUCT_PORT || !isCoastal(x, y)) return emptyList()

        val out = mutableListOf<Pair<Int, Int>>()
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val t = cells[ty][tx]
            if (t.terrain != LAND || t.owner == id || !isCoastal(tx, ty)) continue
            val distance = abs(x - tx) + abs(y - ty)
            if (distance in 2..11) out += tx to ty
        }
        return out
    }

    private fun isCoastal(x: Int, y: Int): Boolean {
        if (cells[y][x].terrain != LAND) return false
        return neighborsAll(x, y).any {
            cells[it.second][it.first].terrain == WATER
        }
    }

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> = buildList {
        if (x > 0) add(x - 1 to y)
        if (x < cols - 1) add(x + 1 to y)
        if (y > 0) add(x to y - 1)
        if (y < rows - 1) add(x to y + 1)
    }

    private fun neighborsAll(x: Int, y: Int): List<Pair<Int, Int>> = buildList {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until cols && ny in 0 until rows) add(nx to ny)
        }
    }

    private fun countStructure(id: Int, structure: Int): Int {
        var count = 0
        for (row in cells) for (c in row) {
            if (c.owner == id && c.structure == structure) count++
        }
        return count
    }

    private fun land(id: Int): Int {
        var count = 0
        for (row in cells) for (c in row) if (c.owner == id) count++
        return count
    }

    private fun flash(message: String) {
        status = message
        statusTimer = 3.8f
    }

    private fun checkEnd() {
        if (land(0) == 0) {
            gameOver = "Поражение"
            dragging = false
            return
        }

        if ((1 until factions).all { land(it) == 0 }) {
            gameOver = "Победа!"
            dragging = false
        }
    }
}
