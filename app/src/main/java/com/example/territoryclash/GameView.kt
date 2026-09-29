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
import kotlin.random.Random

class GameView(
    context: Context,
    private val music: BackgroundMusic
) : View(context) {

    data class Cell(
        var terrain: Int = LAND,
        var owner: Int = NEUTRAL,
        var troops: Float = 1f,
        var structure: Int = STRUCT_NONE
    )

    data class FactionState(
        var money: Float = 260f,
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

        const val COST_CITY = 120
        const val COST_FACTORY = 160
        const val COST_PORT = 140
        const val COST_FORT = 100
        const val COST_SILO = 260
        const val COST_NUKE = 380
    }

    private val cols = 26
    private val rows = 15
    private val factions = 5
    private val cells = Array(rows) { Array(cols) { Cell() } }
    private val states = Array(factions) { FactionState() }

    private val colors = intArrayOf(
        Color.rgb(49, 145, 255),
        Color.rgb(235, 72, 86),
        Color.rgb(255, 164, 55),
        Color.rgb(172, 92, 235),
        Color.rgb(67, 205, 127)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var selectedX = -1
    private var selectedY = -1
    private var cursorX = 3
    private var cursorY = rows / 2
    private var attackPercent = 0.55f
    private var last = System.nanoTime()
    private var botClock = 0f
    private var economyClock = 0f
    private var gameOver: String? = null
    private var nukeTargetMode = false
    private var status = "Захватывай территорию и строй экономику"
    private var statusTimer = 3f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        reset()
    }

    private fun reset() {
        for (id in 0 until factions) {
            states[id].money = 260f
            states[id].nukes = 0
        }

        for (y in 0 until rows) for (x in 0 until cols) {
            val water =
                (x in 8..10 && y in 2..5) ||
                (x in 15..17 && y in 9..12) ||
                (x in 19..21 && y in 3..4) ||
                (x in 5..6 && y in 10..12)

            val cell = cells[y][x]
            if (water) {
                cell.terrain = WATER
                cell.owner = WATER_OWNER
                cell.troops = 0f
                cell.structure = STRUCT_NONE
            } else {
                cell.terrain = LAND
                cell.owner = NEUTRAL
                cell.troops = 2f + Random.nextFloat() * 5f
                cell.structure = STRUCT_NONE
            }
        }

        val starts = listOf(
            3 to rows / 2,
            cols - 4 to rows / 2,
            cols / 2 to 2,
            cols / 2 to rows - 3,
            cols / 2 to rows / 2
        )

        starts.forEachIndexed { id, (sx, sy) ->
            for (dy in -1..1) for (dx in -1..1) {
                val x = (sx + dx).coerceIn(0, cols - 1)
                val y = (sy + dy).coerceIn(0, rows - 1)
                val c = cells[y][x]
                c.terrain = LAND
                c.owner = id
                c.troops = if (dx == 0 && dy == 0) 28f else 10f
                c.structure = if (dx == 0 && dy == 0) STRUCT_CITY else STRUCT_NONE
            }
        }

        selectedX = -1
        selectedY = -1
        cursorX = 3
        cursorY = rows / 2
        attackPercent = 0.55f
        nukeTargetMode = false
        gameOver = null
        status = "Город приносит деньги. Фабрика растит армию. Порт даёт морскую дальность."
        statusTimer = 6f
        botClock = 0f
        economyClock = 0f
        last = System.nanoTime()
        invalidate()
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
        if (gameOver != null) return

        economyClock += dt
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            if (c.owner < 0 || c.terrain == WATER) continue

            var growth = 0.035f
            growth += when (c.structure) {
                STRUCT_CITY -> 0.32f
                STRUCT_FACTORY -> 0.78f
                STRUCT_PORT -> 0.10f
                STRUCT_FORT -> 0.03f
                STRUCT_SILO -> 0.04f
                else -> 0f
            }
            c.troops = min(250f, c.troops + dt * growth)
        }

        if (economyClock >= 0.25f) {
            val step = economyClock
            economyClock = 0f
            for (id in 0 until factions) {
                val income = 2.3f +
                    land(id) * 0.025f +
                    countStructure(id, STRUCT_CITY) * 0.9f +
                    countStructure(id, STRUCT_PORT) * 0.45f +
                    countStructure(id, STRUCT_FACTORY) * 0.15f
                states[id].money += income * step
            }
        }

        botClock += dt
        if (botClock >= 0.75f) {
            botClock = 0f
            for (id in 1 until factions) botTurn(id)
            checkEnd()
        }
    }

    private fun botTurn(id: Int) {
        if (land(id) == 0) return

        botBuild(id)

        if (states[id].nukes > 0 && Random.nextFloat() < 0.10f) {
            val target = strongestEnemyTarget(id)
            if (target != null) {
                launchNuke(id, target.first, target.second)
                return
            }
        }

        val sources = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            if (c.owner != id || c.troops < 8f) continue
            val hasBorder = neighbors(x, y).any {
                val t = cells[it.second][it.first]
                t.terrain == LAND && t.owner != id
            }
            val naval = c.structure == STRUCT_PORT && coastalTargetsFrom(x, y, id).isNotEmpty()
            if (hasBorder || naval) sources += x to y
        }
        if (sources.isEmpty()) return

        val (sx, sy) = sources.random()
        val normal = neighbors(sx, sy).filter {
            val t = cells[it.second][it.first]
            t.terrain == LAND && t.owner != id
        }

        val target = if (normal.isNotEmpty()) {
            normal.minByOrNull { effectiveDefense(cells[it.second][it.first]) }
        } else {
            coastalTargetsFrom(sx, sy, id).minByOrNull {
                effectiveDefense(cells[it.second][it.first])
            }
        } ?: return

        move(sx, sy, target.first, target.second, id, 0.58f)
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
            if (neighbors(x, y).any { cells[it.second][it.first].owner != id && cells[it.second][it.first].terrain == LAND }) {
                border += x to y
            }
            if (isCoastal(x, y)) coast += x to y
        }

        if (countStructure(id, STRUCT_SILO) > 0 && state.money >= COST_NUKE && state.nukes < 2 && Random.nextFloat() < 0.22f) {
            state.money -= COST_NUKE
            state.nukes++
            return
        }

        if (own.isEmpty() || Random.nextFloat() > 0.42f) return

        val choice = when {
            state.money >= COST_SILO && countStructure(id, STRUCT_SILO) == 0 && land(id) >= 18 ->
                Triple(STRUCT_SILO, COST_SILO, own.random())
            state.money >= COST_FACTORY && countStructure(id, STRUCT_FACTORY) < 3 ->
                Triple(STRUCT_FACTORY, COST_FACTORY, own.random())
            state.money >= COST_CITY && countStructure(id, STRUCT_CITY) < max(2, land(id) / 12) ->
                Triple(STRUCT_CITY, COST_CITY, own.random())
            state.money >= COST_PORT && coast.isNotEmpty() && countStructure(id, STRUCT_PORT) < 2 ->
                Triple(STRUCT_PORT, COST_PORT, coast.random())
            state.money >= COST_FORT && border.isNotEmpty() ->
                Triple(STRUCT_FORT, COST_FORT, border.random())
            else -> null
        }

        if (choice != null) {
            val (structure, cost, pos) = choice
            cells[pos.second][pos.first].structure = structure
            state.money -= cost
        }
    }

    private fun strongestEnemyTarget(id: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var score = -1f
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = cells[y][x]
            if (c.terrain != LAND || c.owner < 0 || c.owner == id) continue
            val s = c.troops + when (c.structure) {
                STRUCT_CITY -> 50f
                STRUCT_FACTORY -> 45f
                STRUCT_SILO -> 80f
                STRUCT_PORT -> 30f
                STRUCT_FORT -> 35f
                else -> 0f
            }
            if (s > score) {
                score = s
                best = x to y
            }
        }
        return best
    }

    private fun drawWorld(canvas: Canvas) {
        canvas.drawColor(Color.rgb(13, 18, 27))

        val top = 82f
        val toolbarTop = height - 126f
        val bottom = toolbarTop - 4f
        val cw = width / cols.toFloat()
        val ch = (bottom - top) / rows.toFloat()

        for (y in 0 until rows) for (x in 0 until cols) {
            val cell = cells[y][x]
            paint.style = Paint.Style.FILL
            paint.color = when {
                cell.terrain == WATER -> Color.rgb(24, 66, 94)
                cell.owner < 0 -> Color.rgb(55, 61, 72)
                else -> colors[cell.owner]
            }
            canvas.drawRect(
                x * cw + 1f,
                top + y * ch + 1f,
                (x + 1) * cw - 1f,
                top + (y + 1) * ch - 1f,
                paint
            )

            if (cell.terrain == LAND && cw > 24f && ch > 18f) {
                text.textAlign = Paint.Align.CENTER
                text.textSize = min(cw, ch) * 0.28f
                text.color = Color.WHITE
                canvas.drawText(
                    cell.troops.toInt().toString(),
                    x * cw + cw / 2f,
                    top + y * ch + ch * 0.65f,
                    text
                )
                drawStructure(canvas, cell, x * cw, top + y * ch, cw, ch)
            }
        }

        fun outline(x: Int, y: Int, color: Int, stroke: Float) {
            if (x < 0 || y < 0) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = color
            canvas.drawRect(
                x * cw + 3f,
                top + y * ch + 3f,
                (x + 1) * cw - 3f,
                top + (y + 1) * ch - 3f,
                paint
            )
        }

        outline(selectedX, selectedY, Color.WHITE, 5f)
        outline(cursorX, cursorY, Color.YELLOW, 3f)

        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        text.textSize = 25f
        canvas.drawText(
            "Territory Clash   Земля ${land(0)}   Армия ${army(0).toInt()}   Кредиты ${states[0].money.toInt()}   Ядерки ${states[0].nukes}",
            14f,
            31f,
            text
        )
        text.textSize = 16f
        canvas.drawText(
            "Тап: выбрать/атаковать • порт: морской удар до 7 клеток • 1-5 стройки • N купить • K цель • M музыка",
            14f,
            58f,
            text
        )

        drawToolbar(canvas, toolbarTop)

        if (nukeTargetMode) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 21f
            text.color = Color.rgb(255, 230, 80)
            canvas.drawText("РЕЖИМ ЯДЕРНОЙ ЦЕЛИ: выбери вражескую клетку", width / 2f, 78f, text)
        }

        gameOver?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 0, 0, 0)
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 38f
            text.color = Color.WHITE
            canvas.drawText("$it — R для новой игры", width / 2f, (top + bottom) / 2f, text)
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
        if (cell.structure == STRUCT_NONE) return
        val label = when (cell.structure) {
            STRUCT_CITY -> "C"
            STRUCT_FACTORY -> "F"
            STRUCT_PORT -> "P"
            STRUCT_FORT -> "D"
            STRUCT_SILO -> "S"
            else -> ""
        }
        text.textAlign = Paint.Align.LEFT
        text.textSize = min(cw, ch) * 0.28f
        text.color = when (cell.structure) {
            STRUCT_CITY -> Color.rgb(255, 245, 170)
            STRUCT_FACTORY -> Color.rgb(230, 230, 230)
            STRUCT_PORT -> Color.rgb(130, 235, 255)
            STRUCT_FORT -> Color.rgb(255, 205, 120)
            STRUCT_SILO -> Color.rgb(255, 120, 120)
            else -> Color.WHITE
        }
        canvas.drawText(label, left + 4f, top + text.textSize + 1f, text)
    }

    private fun drawToolbar(canvas: Canvas, toolbarTop: Float) {
        val labels = arrayOf(
            "Город\n$COST_CITY",
            "Фабрика\n$COST_FACTORY",
            "Порт\n$COST_PORT",
            "Форт\n$COST_FORT",
            "Шахта\n$COST_SILO",
            "Ядерка\n$COST_NUKE",
            "ЦЕЛЬ",
            if (music.muted) "Музыка OFF" else "Музыка ON"
        )
        val buttonW = width / labels.size.toFloat()
        val buttonTop = toolbarTop + 5f
        val buttonBottom = toolbarTop + 62f

        for (i in labels.indices) {
            paint.style = Paint.Style.FILL
            paint.color = if (i == 6 && nukeTargetMode) {
                Color.rgb(120, 75, 30)
            } else {
                Color.rgb(35, 43, 56)
            }
            canvas.drawRect(
                i * buttonW + 2f,
                buttonTop,
                (i + 1) * buttonW - 2f,
                buttonBottom,
                paint
            )

            val parts = labels[i].split("\n")
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            text.textSize = 14f
            canvas.drawText(parts[0], i * buttonW + buttonW / 2f, buttonTop + 21f, text)
            if (parts.size > 1) {
                text.textSize = 12f
                text.color = Color.LTGRAY
                canvas.drawText(parts[1], i * buttonW + buttonW / 2f, buttonTop + 42f, text)
            }
        }

        text.textAlign = Paint.Align.CENTER
        text.textSize = 18f
        text.color = Color.WHITE
        canvas.drawText("-   Атака ${(attackPercent * 100).toInt()}%   +", width / 2f, toolbarTop + 91f, text)

        text.textSize = 14f
        text.color = if (statusTimer > 0f) Color.rgb(205, 225, 255) else Color.LTGRAY
        canvas.drawText(
            if (statusTimer > 0f) status else "Выбери свою клетку и соседнюю цель",
            width / 2f,
            toolbarTop + 115f,
            text
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (event.action != MotionEvent.ACTION_DOWN) return true

        val toolbarTop = height - 126f
        if (event.y >= toolbarTop) {
            handleToolbar(event.x, event.y, toolbarTop)
            return true
        }

        val top = 82f
        val bottom = toolbarTop - 4f
        if (event.y !in top..bottom) return true

        val x = (event.x / (width / cols.toFloat())).toInt().coerceIn(0, cols - 1)
        val y = ((event.y - top) / ((bottom - top) / rows)).toInt().coerceIn(0, rows - 1)
        cursorX = x
        cursorY = y

        if (nukeTargetMode) {
            tryPlayerNuke(x, y)
        } else {
            activate(x, y)
        }
        return true
    }

    private fun handleToolbar(x: Float, y: Float, toolbarTop: Float) {
        if (y <= toolbarTop + 64f) {
            val index = (x / (width / 8f)).toInt().coerceIn(0, 7)
            when (index) {
                0 -> buildPlayerStructure(STRUCT_CITY, COST_CITY)
                1 -> buildPlayerStructure(STRUCT_FACTORY, COST_FACTORY)
                2 -> buildPlayerStructure(STRUCT_PORT, COST_PORT)
                3 -> buildPlayerStructure(STRUCT_FORT, COST_FORT)
                4 -> buildPlayerStructure(STRUCT_SILO, COST_SILO)
                5 -> buyPlayerNuke()
                6 -> toggleNukeMode()
                7 -> {
                    val on = music.toggle()
                    flash(if (on) "Музыка включена" else "Музыка выключена")
                }
            }
        } else if (y <= toolbarTop + 103f) {
            attackPercent = if (x < width / 2f) {
                max(0.10f, attackPercent - 0.10f)
            } else {
                min(0.90f, attackPercent + 0.10f)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            attackPercent = (
                attackPercent +
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) 0.05f else -0.05f
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

            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (nukeTargetMode) tryPlayerNuke(cursorX, cursorY) else activate(cursorX, cursorY)
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
            KeyEvent.KEYCODE_NUMPAD_ADD -> attackPercent = min(0.90f, attackPercent + 0.10f)

            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> attackPercent = max(0.10f, attackPercent - 0.10f)

            KeyEvent.KEYCODE_R -> reset()
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                nukeTargetMode = false
                selectedX = -1
                selectedY = -1
            }

            else -> return super.onKeyDown(code, event)
        }
        return true
    }

    private fun activate(x: Int, y: Int) {
        if (gameOver != null) return
        val cell = cells[y][x]
        if (cell.terrain == WATER) {
            flash("По воде ходить нельзя. Используй порт для морского удара.")
            return
        }

        if (selectedX < 0) {
            if (cell.owner == 0) {
                selectedX = x
                selectedY = y
            }
            return
        }

        if (x == selectedX && y == selectedY) {
            selectedX = -1
            selectedY = -1
            return
        }

        if (canMove(selectedX, selectedY, x, y, 0)) {
            move(selectedX, selectedY, x, y, 0, attackPercent)
            if (cells[y][x].owner == 0) {
                selectedX = x
                selectedY = y
            } else {
                selectedX = -1
                selectedY = -1
            }
            checkEnd()
        } else if (cell.owner == 0) {
            selectedX = x
            selectedY = y
        } else {
            flash("Цель вне досягаемости. Из порта можно бить по побережью до 7 клеток.")
        }
    }

    private fun move(
        sx: Int,
        sy: Int,
        tx: Int,
        ty: Int,
        id: Int,
        pct: Float
    ) {
        if (!canMove(sx, sy, tx, ty, id)) return
        val source = cells[sy][sx]
        val target = cells[ty][tx]
        if (source.owner != id || target.terrain != LAND) return

        val amount = min(
            max(0f, source.troops - 1f),
            max(1f, source.troops * pct)
        )
        if (amount <= 0f) return

        source.troops -= amount

        if (target.owner == id) {
            target.troops = min(250f, target.troops + amount)
            return
        }

        val defense = effectiveDefense(target)
        if (amount > defense) {
            target.owner = id
            target.troops = max(1f, amount - defense)
            if (target.structure == STRUCT_SILO) target.structure = STRUCT_NONE
        } else {
            val multiplier = defenseMultiplier(target)
            target.troops = max(0.5f, target.troops - amount / multiplier)
        }
    }

    private fun canMove(sx: Int, sy: Int, tx: Int, ty: Int, id: Int): Boolean {
        if (sx !in 0 until cols || tx !in 0 until cols || sy !in 0 until rows || ty !in 0 until rows) return false
        val source = cells[sy][sx]
        val target = cells[ty][tx]
        if (source.owner != id || source.terrain != LAND || target.terrain != LAND) return false

        if (abs(sx - tx) + abs(sy - ty) == 1) return true

        if (source.structure == STRUCT_PORT && isCoastal(sx, sy) && isCoastal(tx, ty)) {
            val distance = abs(sx - tx) + abs(sy - ty)
            return distance in 2..7
        }
        return false
    }

    private fun effectiveDefense(cell: Cell): Float = cell.troops * defenseMultiplier(cell)

    private fun defenseMultiplier(cell: Cell): Float = when (cell.structure) {
        STRUCT_FORT -> 1.80f
        STRUCT_CITY -> 1.15f
        else -> 1f
    }

    private fun buildPlayerStructure(structure: Int, cost: Int) {
        val pos = selectedOwnedCell() ?: run {
            flash("Сначала выбери свою клетку.")
            return
        }
        val cell = cells[pos.second][pos.first]
        if (cell.structure != STRUCT_NONE) {
            flash("На этой клетке уже есть постройка.")
            return
        }
        if (structure == STRUCT_PORT && !isCoastal(pos.first, pos.second)) {
            flash("Порт можно строить только рядом с водой.")
            return
        }
        if (states[0].money < cost) {
            flash("Не хватает кредитов: нужно $cost.")
            return
        }

        states[0].money -= cost
        cell.structure = structure
        flash(
            when (structure) {
                STRUCT_CITY -> "Город построен: больше дохода и прироста."
                STRUCT_FACTORY -> "Фабрика построена: армия растёт быстрее."
                STRUCT_PORT -> "Порт построен: доступна морская дальность 7 клеток."
                STRUCT_FORT -> "Форт построен: защита клетки x1.8."
                STRUCT_SILO -> "Ракетная шахта готова. Теперь можно купить ядерку."
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
            flash("Ядерку можно собрать только в ракетной шахте.")
            return
        }
        if (states[0].money < COST_NUKE) {
            flash("Для ядерки нужно $COST_NUKE кредитов.")
            return
        }
        states[0].money -= COST_NUKE
        states[0].nukes++
        flash("Ядерка готова. Нажми ЦЕЛЬ или K.")
    }

    private fun toggleNukeMode() {
        if (states[0].nukes <= 0) {
            flash("Нет готовых ядерок.")
            nukeTargetMode = false
            return
        }
        nukeTargetMode = !nukeTargetMode
        flash(if (nukeTargetMode) "Выбери вражескую клетку для удара." else "Ядерный режим отменён.")
    }

    private fun tryPlayerNuke(x: Int, y: Int) {
        if (states[0].nukes <= 0) {
            nukeTargetMode = false
            flash("Нет готовых ядерок.")
            return
        }
        val target = cells[y][x]
        if (target.terrain != LAND || target.owner == 0) {
            flash("Выбери вражескую или нейтральную клетку.")
            return
        }

        launchNuke(0, x, y)
        nukeTargetMode = false
        selectedX = -1
        selectedY = -1
        flash("Ядерный удар нанесён. Центр зоны стал нейтральным.")
        checkEnd()
    }

    private fun launchNuke(id: Int, x: Int, y: Int) {
        if (states[id].nukes <= 0) return
        states[id].nukes--

        for (yy in max(0, y - 1)..min(rows - 1, y + 1)) {
            for (xx in max(0, x - 1)..min(cols - 1, x + 1)) {
                val c = cells[yy][xx]
                if (c.terrain != LAND) continue

                if (xx == x && yy == y) {
                    c.owner = NEUTRAL
                    c.troops = 4f
                    c.structure = STRUCT_NONE
                } else {
                    c.troops = max(1f, c.troops * 0.22f)
                    if (c.structure != STRUCT_CITY && Random.nextFloat() < 0.65f) {
                        c.structure = STRUCT_NONE
                    }
                }
            }
        }
    }

    private fun selectedOwnedCell(): Pair<Int, Int>? {
        if (selectedX >= 0 && selectedY >= 0 && cells[selectedY][selectedX].owner == 0) {
            return selectedX to selectedY
        }
        if (cells[cursorY][cursorX].owner == 0) {
            return cursorX to cursorY
        }
        return null
    }

    private fun coastalTargetsFrom(x: Int, y: Int, id: Int): List<Pair<Int, Int>> {
        if (cells[y][x].structure != STRUCT_PORT || !isCoastal(x, y)) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        for (ty in 0 until rows) for (tx in 0 until cols) {
            if (cells[ty][tx].terrain != LAND || cells[ty][tx].owner == id || !isCoastal(tx, ty)) continue
            val distance = abs(x - tx) + abs(y - ty)
            if (distance in 2..7) out += tx to ty
        }
        return out
    }

    private fun isCoastal(x: Int, y: Int): Boolean {
        if (cells[y][x].terrain != LAND) return false
        return neighborsAll(x, y).any { cells[it.second][it.first].terrain == WATER }
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
        for (row in cells) for (cell in row) {
            if (cell.owner == id && cell.structure == structure) count++
        }
        return count
    }

    private fun land(id: Int): Int {
        var total = 0
        for (row in cells) for (cell in row) if (cell.owner == id) total++
        return total
    }

    private fun army(id: Int): Float {
        var total = 0f
        for (row in cells) for (cell in row) if (cell.owner == id) total += cell.troops
        return total
    }

    private fun flash(message: String) {
        status = message
        statusTimer = 3.5f
    }

    private fun checkEnd() {
        if (land(0) == 0) {
            gameOver = "Поражение"
            return
        }
        if ((1 until factions).all { land(it) == 0 }) {
            gameOver = "Победа!"
        }
    }
}
