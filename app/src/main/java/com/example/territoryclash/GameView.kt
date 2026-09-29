package com.example.territoryclash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class GameView(
    context: Context,
    private val music: BackgroundMusic
) : View(context) {

    private val cols = EuropeScenario.COLS
    private val rows = EuropeScenario.ROWS
    private val nations = EuropeScenario.COUNTRY_COUNT

    private val map = Array(rows) { Array(cols) { Province() } }
    private val nation = Array(nations) { NationState() }
    private val divisions = mutableListOf<Division>()
    private val battles = mutableListOf<Battle>()

    private var nationColors = IntArray(nations) { Color.rgb(120, 120, 120) }

    private var nationNames = Array(nations) { "Страна ${it + 1}" }
    private var nationTags = Array(nations) { "N${it + 1}" }

    private var screen = SCREEN_MAIN_MENU
    private var selectedCountry = EuropeScenario.GERMANY
    private var menuMusicOn = true

    private val settings = GameSettings()
    private var activePanel = 0
    private var settingsOpen = false
    private var currentEvent: HistoricalEvent? = null
    private val historicalEvents = mutableListOf<HistoricalEvent>()

    companion object {
        private const val SCREEN_MAIN_MENU = 0
        private const val SCREEN_COUNTRY_SELECT = 1
        private const val SCREEN_GAME = 2
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private var nextDivisionId = 1
    private var selectedProvinceX = -1
    private var selectedProvinceY = -1
    private var cursorX = 4
    private var cursorY = rows / 2

    private var lastFrame = System.nanoTime()
    private var economyClock = 0f
    private var aiClock = 0f
    private var dayClock = 0f
    private var day = 1

    private val speeds = floatArrayOf(0f, 1f, 2f, 4f)
    private var speedIndex = 1

    private var status = "Выбери дивизии, затем провинцию для движения или атаки."
    private var statusTimer = 6f
    private var nukeTargetMode = false
    private var gameOver: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        reset()
        screen = SCREEN_MAIN_MENU
    }

    private fun reset() {
        divisions.clear()
        battles.clear()
        nextDivisionId = 1
        day = 1
        dayClock = 0f
        economyClock = 0f
        aiClock = 0f
        speedIndex = 1
        nukeTargetMode = false
        gameOver = null
        activePanel = 0
        settingsOpen = false
        currentEvent = null
        historicalEvents.clear()
        historicalEvents += HistoricalEvent(
            67,
            "Ремилитаризация Рейнской области",
            "7 марта 1936 года германские войска вошли в демилитаризованную Рейнскую область.",
            "Напряжённость в Западной Европе растёт."
        )
        historicalEvents += HistoricalEvent(
            199,
            "Гражданская война в Испании",
            "17 июля 1936 года военный мятеж в Испании перерос в гражданскую войну.",
            "Испанский фронт становится нестабильным."
        )
        historicalEvents += HistoricalEvent(
            802,
            "Аншлюс Австрии",
            "В марте 1938 года Австрия была присоединена к Германии.",
            "Баланс сил в Центральной Европе меняется."
        )
        historicalEvents += HistoricalEvent(
            1003,
            "Мюнхенское соглашение",
            "29–30 сентября 1938 года было заключено Мюнхенское соглашение по Судетской области.",
            "Чехословацкая граница оказывается под новым давлением."
        )

        for (i in 0 until nations) {
            nation[i] = NationState(
                manpower = if (i == 0) 95f else 88f,
                equipment = 1500f,
                tanks = 260f,
                aircraft = 170f,
                fuel = 1050f,
                money = 620f,
                nukes = 0
            )
        }

        val setup = EuropeScenario.build(map, selectedCountry)
        nationNames = setup.names
        nationTags = setup.tags
        nationColors = setup.colors
        val starts = setup.starts

        starts.forEachIndexed { owner, (sx, sy) ->
            createStartingArmy(owner, sx, sy)
        }

        clearSelection()
        selectedProvinceX = starts[0].first
        selectedProvinceY = starts[0].second
        cursorX = selectedProvinceX
        cursorY = selectedProvinceY

        status = "Европа 1936: командуй дивизиями, держи снабжение и ломай фронт противника."
        statusTimer = 8f
        lastFrame = System.nanoTime()
        invalidate()
    }

    private fun generateMap() {
        val rng = Random(404)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = map[y][x]

                val sea =
                    x == 0 || y == 0 || x == cols - 1 || y == rows - 1 ||
                    ((x - 10) * (x - 10) + (y - 5) * (y - 5) < 8) ||
                    ((x - 21) * (x - 21) + (y - 12) * (y - 12) < 10) ||
                    (x in 14..15 && y in 6..10)

                if (sea) {
                    p.terrain = WarRules.WATER
                    p.owner = WarRules.WATER_OWNER
                    p.garrison = 0f
                    p.fort = 0
                    p.city = false
                    p.port = false
                    p.silo = false
                    continue
                }

                val roll = rng.nextFloat()
                p.terrain = when {
                    roll < 0.19f -> WarRules.FOREST
                    roll < 0.32f -> WarRules.HILLS
                    else -> WarRules.PLAINS
                }
                p.owner = WarRules.NEUTRAL
                p.garrison = 6f + rng.nextFloat() * 6f
                p.fort = 0
                p.city = false
                p.port = false
                p.silo = false
            }
        }
    }

    private fun createCountry(owner: Int, sx: Int, sy: Int) {
        for (dy in -2..2) {
            for (dx in -3..3) {
                if (abs(dx) + abs(dy) > 4) continue
                val x = (sx + dx).coerceIn(1, cols - 2)
                val y = (sy + dy).coerceIn(1, rows - 2)
                val p = map[y][x]
                p.terrain = if (p.terrain == WarRules.WATER) WarRules.PLAINS else p.terrain
                p.owner = owner
                p.garrison = 5f
            }
        }

        val capital = map[sy][sx]
        capital.city = true
        capital.garrison = 16f

        val coast = findNearestCoast(owner, sx, sy)
        if (coast != null) {
            map[coast.second][coast.first].port = true
        }
    }

    private fun createStartingArmy(owner: Int, sx: Int, sy: Int) {
        addDivision(owner, WarRules.INFANTRY, sx, sy)
        addDivision(owner, WarRules.INFANTRY, (sx - 1).coerceAtLeast(1), sy)
        addDivision(owner, WarRules.INFANTRY, sx, (sy + 1).coerceAtMost(rows - 2))
        addDivision(owner, WarRules.ARMOR, (sx + 1).coerceAtMost(cols - 2), sy)
        addDivision(owner, WarRules.AIR, sx, sy)

        if (owner == 0) {
            addDivision(owner, WarRules.ARMOR, sx, (sy - 1).coerceAtLeast(1))
            addDivision(owner, WarRules.INFANTRY, (sx + 1).coerceAtMost(cols - 2), (sy + 1).coerceAtMost(rows - 2))
        }
    }

    private fun addDivision(owner: Int, type: Int, x: Int, y: Int) {
        divisions += Division(
            id = nextDivisionId++,
            owner = owner,
            type = type,
            x = x,
            y = y,
            strength = 100f,
            org = 100f
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val rawDt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrame = now

        if (screen == SCREEN_MAIN_MENU) {
            drawMainMenu(canvas)
            postInvalidateOnAnimation()
            return
        }

        if (screen == SCREEN_COUNTRY_SELECT) {
            drawCountrySelect(canvas)
            postInvalidateOnAnimation()
            return
        }

        val dt = rawDt * speeds[speedIndex]
        if (statusTimer > 0f) statusTimer -= rawDt

        if (dt > 0f && gameOver == null) {
            updateGame(dt)
        }

        drawGame(canvas)
        postInvalidateOnAnimation()
    }

    private fun updateGame(dt: Float) {
        dayClock += dt
        if (dayClock >= 2.2f) {
            val passed = (dayClock / 2.2f).toInt()
            day += passed
            dayClock -= passed * 2.2f
            checkHistoricalEvents()
        }

        economyClock += dt
        if (economyClock >= 0.5f) {
            val step = economyClock
            economyClock = 0f
            updateEconomy(step)
        }

        updateDivisions(dt)
        updateBattles(dt)

        aiClock += dt
        if (aiClock >= 0.8f) {
            aiClock = 0f
            for (owner in 1 until nations) aiTurn(owner)
        }

        checkVictory()
    }

    private fun updateEconomy(dt: Float) {
        for (owner in 0 until nations) {
            if (provinceCount(owner) == 0) continue

            val cities = countCities(owner)
            val ports = countPorts(owner)

            val s = nation[owner]
            s.money += dt * (4.0f + cities * 5.0f + provinceCount(owner) * 0.22f)
            s.manpower = min(130f, s.manpower + dt * (0.025f + cities * 0.055f))
            s.equipment += dt * (2.0f + cities * 1.8f)
            s.tanks += dt * (0.20f + cities * 0.22f)
            s.aircraft += dt * (0.15f + cities * 0.18f)
            s.fuel += dt * (1.2f + ports * 1.1f + provinceCount(owner) * 0.035f)
        }
    }

    private fun updateDivisions(dt: Float) {
        val activeIds = mutableSetOf<Int>()
        for (b in battles) activeIds += b.attackers
        for (b in battles) {
            for (d in landDivisionsAt(b.targetX, b.targetY)) activeIds += d.id
        }

        for (d in divisions.toList()) {
            if (d.strength <= 2f) {
                divisions.remove(d)
                continue
            }

            if (d.type == WarRules.AIR) {
                d.org = min(100f, d.org + dt * 2.4f)
                continue
            }

            val supply = supplyLevel(d.owner, d.x, d.y)
            val fighting = d.id in activeIds

            if (!fighting) {
                d.org = min(100f, d.org + dt * (3.5f * supply))
                d.entrenchment = min(1f, d.entrenchment + dt * 0.045f * supply)
                reinforceDivision(d, dt, supply)
            } else {
                d.entrenchment = max(0f, d.entrenchment - dt * 0.12f)
            }

            if (d.order.isNotEmpty() && !fighting) {
                d.moveTimer -= dt
                if (d.moveTimer <= 0f) {
                    val next = d.order.removeAt(0)
                    if (map[next.second][next.first].owner == d.owner &&
                        map[next.second][next.first].terrain != WarRules.WATER
                    ) {
                        d.x = next.first
                        d.y = next.second
                        d.org = max(5f, d.org - if (d.type == WarRules.ARMOR) 1.5f else 0.9f)
                        d.entrenchment = 0f
                    } else {
                        d.order.clear()
                    }

                    d.moveTimer = if (d.type == WarRules.ARMOR) 0.36f else 0.55f

                    if (d.order.isEmpty() && d.attackX >= 0 && d.attackY >= 0) {
                        if (adjacent(d.x, d.y, d.attackX, d.attackY)) {
                            joinBattle(d.owner, d.attackX, d.attackY, d.id)
                        }
                    }
                }
            }
        }
    }

    private fun reinforceDivision(d: Division, dt: Float, supply: Float) {
        if (d.strength >= 100f || supply < 0.45f) return
        val state = nation[d.owner]

        when (d.type) {
            WarRules.INFANTRY -> {
                if (state.equipment < 0.2f || state.manpower < 0.01f) return
                val amount = min(100f - d.strength, dt * 0.75f * supply)
                d.strength += amount
                state.equipment = max(0f, state.equipment - amount * 0.10f)
                state.manpower = max(0f, state.manpower - amount * 0.004f)
            }

            WarRules.ARMOR -> {
                if (state.tanks < 0.05f || state.equipment < 0.1f) return
                val amount = min(100f - d.strength, dt * 0.52f * supply)
                d.strength += amount
                state.tanks = max(0f, state.tanks - amount * 0.055f)
                state.equipment = max(0f, state.equipment - amount * 0.05f)
                state.manpower = max(0f, state.manpower - amount * 0.0025f)
            }
        }
    }

    private fun updateBattles(dt: Float) {
        val iterator = battles.iterator()
        while (iterator.hasNext()) {
            val battle = iterator.next()
            battle.age += dt

            battle.attackers.removeAll { id ->
                val d = divisionById(id)
                d == null ||
                    d.owner != battle.attackerOwner ||
                    d.type == WarRules.AIR ||
                    d.strength <= 4f ||
                    d.org <= 2f ||
                    !adjacent(d.x, d.y, battle.targetX, battle.targetY)
            }

            if (battle.attackers.isEmpty()) {
                iterator.remove()
                continue
            }

            val target = map[battle.targetY][battle.targetX]
            if (target.owner == battle.attackerOwner) {
                iterator.remove()
                continue
            }

            val defenderOwner = target.owner
            val defenders = landDivisionsAt(battle.targetX, battle.targetY)
                .filter { it.owner == defenderOwner }

            val attackerPower = battle.attackers.sumOf { id ->
                divisionById(id)?.let {
                    combatPower(it, attacking = true, target.terrain)
                }?.toDouble() ?: 0.0
            }.toFloat() * airSupportMultiplier(battle.attackerOwner, battle.targetX, battle.targetY)

            val defenderPowerUnits = defenders.sumOf {
                combatPower(it, attacking = false, target.terrain).toDouble()
            }.toFloat()

            val provinceDefense =
                target.garrison *
                    terrainDefense(target.terrain) *
                    (1f + target.fort * 0.28f)

            val defenderAir = if (defenderOwner >= 0) {
                airSupportMultiplier(defenderOwner, battle.targetX, battle.targetY)
            } else {
                1f
            }

            val defenderPower = (defenderPowerUnits + provinceDefense) * defenderAir

            applyCombatLosses(
                battle = battle,
                defenders = defenders,
                attackerPower = attackerPower,
                defenderPower = defenderPower,
                dt = dt
            )

            if (defenders.isEmpty()) {
                target.garrison = max(0f, target.garrison - dt * attackerPower * 0.055f)
            }

            val ratio = attackerPower / max(8f, defenderPower)
            val push =
                when {
                    ratio >= 2.0f -> 21f
                    ratio >= 1.45f -> 14f
                    ratio >= 1.10f -> 8f
                    ratio >= 0.82f -> 2f
                    else -> -5f
                }

            battle.progress = (battle.progress + dt * push).coerceIn(-35f, 100f)

            val attackersBroken = battle.attackers.all { id ->
                val d = divisionById(id)
                d == null || d.org < 8f || d.strength < 12f
            }

            if (attackersBroken || battle.progress <= -35f) {
                for (id in battle.attackers) {
                    divisionById(id)?.let {
                        it.attackX = -1
                        it.attackY = -1
                        it.org = max(6f, it.org)
                    }
                }
                iterator.remove()
                continue
            }

            if (battle.progress >= 100f ||
                (defenders.isEmpty() && target.garrison <= 0.2f && battle.age > 0.8f)
            ) {
                val oldOwner = target.owner
                target.owner = battle.attackerOwner
                target.garrison = 5f
                target.fort = max(0, target.fort - 1)

                for (id in battle.attackers) {
                    divisionById(id)?.let { d ->
                        d.x = battle.targetX
                        d.y = battle.targetY
                        d.org = max(12f, d.org - 8f)
                        d.attackX = -1
                        d.attackY = -1
                        d.order.clear()
                        d.entrenchment = 0f
                    }
                }

                if (oldOwner >= 0) {
                    nation[oldOwner].manpower = max(0f, nation[oldOwner].manpower - 0.25f)
                }

                iterator.remove()
            }
        }
    }

    private fun applyCombatLosses(
        battle: Battle,
        defenders: List<Division>,
        attackerPower: Float,
        defenderPower: Float,
        dt: Float
    ) {
        val aCount = max(1, battle.attackers.size)
        val dCount = max(1, defenders.size)

        for (id in battle.attackers) {
            val d = divisionById(id) ?: continue
            val supply = supplyLevel(d.owner, d.x, d.y)
            val orgLoss = dt * (1.5f + defenderPower / (aCount * 32f))
            val strengthLoss = dt * defenderPower / (aCount * 250f)

            d.org = max(0f, d.org - orgLoss / max(0.45f, supply))
            d.strength = max(0f, d.strength - strengthLoss)

            if (d.type == WarRules.ARMOR) {
                nation[d.owner].fuel = max(0f, nation[d.owner].fuel - dt * 1.4f)
            }
        }

        for (d in defenders) {
            val supply = supplyLevel(d.owner, d.x, d.y)
            val entrenchBonus = 1f + d.entrenchment * 0.35f
            val orgLoss = dt * (1.7f + attackerPower / (dCount * 30f))
            val strengthLoss = dt * attackerPower / (dCount * 225f)

            d.org = max(0f, d.org - orgLoss / (max(0.4f, supply) * entrenchBonus))
            d.strength = max(0f, d.strength - strengthLoss / entrenchBonus)

            if (d.type == WarRules.ARMOR) {
                nation[d.owner].fuel = max(0f, nation[d.owner].fuel - dt * 1.0f)
            }

            if (d.org <= 3f && d.strength > 10f) {
                retreatDivision(d)
            }
        }

        divisions.removeAll { it.strength <= 2f }
    }

    private fun retreatDivision(d: Division) {
        val candidates = neighbors(d.x, d.y).filter {
            map[it.second][it.first].owner == d.owner &&
                map[it.second][it.first].terrain != WarRules.WATER
        }

        if (candidates.isNotEmpty()) {
            val dest = candidates.maxByOrNull {
                supplyLevel(d.owner, it.first, it.second)
            }!!
            d.x = dest.first
            d.y = dest.second
            d.org = 12f
            d.entrenchment = 0f
        } else {
            d.strength = 0f
        }
    }

    private fun combatPower(d: Division, attacking: Boolean, terrain: Int): Float {
        val supply = supplyLevel(d.owner, d.x, d.y)
        val state = nation[d.owner]

        val base = when (d.type) {
            WarRules.INFANTRY -> if (attacking) 17f else 20f
            WarRules.ARMOR -> if (attacking) 31f else 24f
            else -> 0f
        }

        val terrainMod = when (d.type) {
            WarRules.INFANTRY -> when (terrain) {
                WarRules.FOREST -> if (attacking) 0.93f else 1.13f
                WarRules.HILLS -> if (attacking) 0.90f else 1.16f
                else -> 1f
            }

            WarRules.ARMOR -> when (terrain) {
                WarRules.FOREST -> 0.68f
                WarRules.HILLS -> 0.62f
                else -> 1.22f
            }

            else -> 1f
        }

        val fuelMod = if (d.type == WarRules.ARMOR && state.fuel < 80f) 0.58f else 1f
        val entrench = if (!attacking) 1f + d.entrenchment * 0.30f else 1f

        return base *
            (d.strength / 100f).coerceAtLeast(0.12f) *
            (d.org / 100f).coerceAtLeast(0.10f) *
            supply.coerceIn(0.35f, 1f) *
            terrainMod *
            fuelMod *
            entrench
    }

    private fun airSupportMultiplier(owner: Int, x: Int, y: Int): Float {
        if (owner < 0) return 1f
        var support = 0f
        for (d in divisions) {
            if (d.owner != owner || d.type != WarRules.AIR) continue
            if (d.missionX < 0 || d.missionY < 0) continue
            if (abs(d.missionX - x) + abs(d.missionY - y) > 2) continue

            val range = abs(d.x - d.missionX) + abs(d.y - d.missionY)
            if (range > 7) continue
            if (nation[owner].fuel <= 1f) continue

            support += (d.strength / 100f) * (d.org / 100f) * 0.16f
            nation[owner].fuel = max(0f, nation[owner].fuel - 0.035f)
        }
        return 1f + min(0.34f, support)
    }

    private fun terrainDefense(terrain: Int): Float = when (terrain) {
        WarRules.FOREST -> 1.24f
        WarRules.HILLS -> 1.32f
        else -> 1f
    }

    private fun supplyLevel(owner: Int, startX: Int, startY: Int): Float {
        if (owner < 0) return 0.4f
        val start = map[startY][startX]
        if (start.owner != owner) return 0.35f
        if (start.city || start.port) return 1f

        val seen = Array(rows) { BooleanArray(cols) }
        val q: ArrayDeque<Triple<Int, Int, Int>> = ArrayDeque()
        q.add(Triple(startX, startY, 0))
        seen[startY][startX] = true

        while (q.isNotEmpty()) {
            val (x, y, d) = q.removeFirst()
            if (d > 14) break

            val p = map[y][x]
            if ((p.city || p.port) && p.owner == owner) {
                return when {
                    d <= 4 -> 1f
                    d <= 8 -> 0.82f
                    d <= 12 -> 0.62f
                    else -> 0.45f
                }
            }

            for ((nx, ny) in neighbors(x, y)) {
                if (seen[ny][nx]) continue
                val np = map[ny][nx]
                if (np.owner != owner || np.terrain == WarRules.WATER) continue
                seen[ny][nx] = true
                q.add(Triple(nx, ny, d + 1))
            }
        }

        return 0.38f
    }

    private fun aiTurn(owner: Int) {
        if (provinceCount(owner) == 0) return

        aiProduction(owner)

        val air = divisions.filter { it.owner == owner && it.type == WarRules.AIR }
        val ownBattle = battles.firstOrNull { it.attackerOwner == owner || map[it.targetY][it.targetX].owner == owner }
        if (ownBattle != null) {
            for (wing in air) {
                wing.missionX = ownBattle.targetX
                wing.missionY = ownBattle.targetY
            }
        }

        val candidates = divisions.filter {
            it.owner == owner &&
                it.type != WarRules.AIR &&
                it.order.isEmpty() &&
                it.attackX < 0 &&
                it.org > 42f &&
                it.strength > 45f &&
                battles.none { b -> it.id in b.attackers }
        }

        val d = candidates.randomOrNull() ?: return

        val enemyAdjacent = neighbors(d.x, d.y)
            .filter {
                val p = map[it.second][it.first]
                p.terrain != WarRules.WATER && p.owner != owner
            }
            .minByOrNull {
                localDefenseScore(it.first, it.second)
            }

        if (enemyAdjacent != null) {
            d.attackX = enemyAdjacent.first
            d.attackY = enemyAdjacent.second
            joinBattle(owner, enemyAdjacent.first, enemyAdjacent.second, d.id)
            return
        }

        val friendlySteps = neighbors(d.x, d.y)
            .filter {
                map[it.second][it.first].owner == owner &&
                    map[it.second][it.first].terrain != WarRules.WATER
            }

        val next = friendlySteps.minByOrNull {
            distanceToNearestEnemy(owner, it.first, it.second)
        }

        if (next != null && distanceToNearestEnemy(owner, next.first, next.second) <
            distanceToNearestEnemy(owner, d.x, d.y)
        ) {
            d.order.clear()
            d.order += next
            d.moveTimer = if (d.type == WarRules.ARMOR) 0.25f else 0.42f
        }
    }

    private fun aiProduction(owner: Int) {
        val state = nation[owner]
        val cities = ownedProvinces(owner).filter { map[it.second][it.first].city }
        if (cities.isEmpty()) return
        val spawn = cities.random()

        val landCount = divisions.count { it.owner == owner && it.type != WarRules.AIR }
        if (landCount < max(5, provinceCount(owner) / 6) && Random.nextFloat() < 0.22f) {
            if (state.manpower >= 6f && state.equipment >= 120f) {
                trainDivision(owner, WarRules.INFANTRY, spawn.first, spawn.second, ai = true)
                return
            }
        }

        if (landCount >= 4 && Random.nextFloat() < 0.10f) {
            if (state.manpower >= 4f && state.tanks >= 70f && state.equipment >= 45f) {
                trainDivision(owner, WarRules.ARMOR, spawn.first, spawn.second, ai = true)
                return
            }
        }

        if (divisions.count { it.owner == owner && it.type == WarRules.AIR } < 2 &&
            state.aircraft >= 45f &&
            state.manpower >= 1f &&
            Random.nextFloat() < 0.08f
        ) {
            trainDivision(owner, WarRules.AIR, spawn.first, spawn.second, ai = true)
        }
    }

    private fun localDefenseScore(x: Int, y: Int): Float {
        val p = map[y][x]
        val unitDefense = landDivisionsAt(x, y).sumOf { it.strength.toDouble() }.toFloat() * 0.18f
        return p.garrison + p.fort * 8f + unitDefense
    }

    private fun distanceToNearestEnemy(owner: Int, x: Int, y: Int): Int {
        var best = 999
        for (yy in 0 until rows) {
            for (xx in 0 until cols) {
                val p = map[yy][xx]
                if (p.terrain == WarRules.WATER || p.owner == owner || p.owner == WarRules.NEUTRAL) continue
                best = min(best, abs(xx - x) + abs(yy - y))
            }
        }
        if (best == 999) {
            for (yy in 0 until rows) {
                for (xx in 0 until cols) {
                    val p = map[yy][xx]
                    if (p.terrain != WarRules.WATER && p.owner != owner) {
                        best = min(best, abs(xx - x) + abs(yy - y))
                    }
                }
            }
        }
        return best
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(23, 28, 32))

        val top = 66f
        val bottomPanel = 184f
        val mapBottom = height - bottomPanel
        val cw = width / cols.toFloat()
        val ch = (mapBottom - top) / rows.toFloat()

        drawTopBar(canvas)
        drawMap(canvas, top, mapBottom, cw, ch)
        if (settings.showProvinceBorders) drawProvinceBorders(canvas, top, cw, ch)
        if (settings.showCountryLabels) drawCountryLabels(canvas, top, cw, ch)
        drawFrontLines(canvas, top, cw, ch)
        drawBattles(canvas, top, cw, ch)
        drawDivisions(canvas, top, cw, ch)
        drawCursor(canvas, top, cw, ch)
        drawBottomPanel(canvas, mapBottom)
        drawEventOverlay(canvas)

        gameOver?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 8, 10, 12)
            canvas.drawRect(0f, top, width.toFloat(), mapBottom, paint)

            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            text.textSize = 42f
            text.isFakeBoldText = true
            canvas.drawText(it, width / 2f, (top + mapBottom) / 2f - 10f, text)
            text.isFakeBoldText = false
            text.textSize = 19f
            canvas.drawText("R — начать новую кампанию", width / 2f, (top + mapBottom) / 2f + 28f, text)
        }
    }


    private fun drawMainMenu(canvas: Canvas) {
        canvas.drawColor(Color.rgb(18, 23, 27))

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(28, 35, 40)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val cx = width / 2f
        val titleY = height * 0.26f

        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(232, 235, 232)
        text.textSize = 44f
        text.isFakeBoldText = true
        canvas.drawText("EUROPE 1936", cx, titleY, text)

        text.textSize = 17f
        text.isFakeBoldText = false
        text.color = Color.rgb(164, 176, 182)
        canvas.drawText("оперативная стратегия • дивизии • снабжение • фронт", cx, titleY + 34f, text)

        val bw = min(420f, width * 0.48f)
        val bh = 58f
        val left = cx - bw / 2f
        val firstY = titleY + 88f

        drawMenuButton(canvas, left, firstY, bw, bh, "НОВАЯ КАМПАНИЯ", true)
        drawMenuButton(canvas, left, firstY + 72f, bw, bh, if (music.muted) "МУЗЫКА: ВЫКЛ" else "МУЗЫКА: ВКЛ", false)

        text.textSize = 12.5f
        text.color = Color.rgb(118, 132, 140)
        canvas.drawText("Версия 0.5 • Android", cx, height - 28f, text)
    }

    private fun drawMenuButton(
        canvas: Canvas,
        left: Float,
        top: Float,
        widthPx: Float,
        heightPx: Float,
        label: String,
        primary: Boolean
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (primary) Color.rgb(73, 91, 98) else Color.rgb(39, 47, 52)
        canvas.drawRoundRect(left, top, left + widthPx, top + heightPx, 7f, 7f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.rgb(105, 119, 124)
        canvas.drawRoundRect(left, top, left + widthPx, top + heightPx, 7f, 7f, paint)

        text.textAlign = Paint.Align.CENTER
        text.textSize = 16f
        text.isFakeBoldText = primary
        text.color = Color.rgb(232, 235, 235)
        canvas.drawText(label, left + widthPx / 2f, top + heightPx * 0.62f, text)
        text.isFakeBoldText = false
    }

    private fun handleMainMenuTouch(x: Float, y: Float) {
        val cx = width / 2f
        val titleY = height * 0.26f
        val bw = min(420f, width * 0.48f)
        val left = cx - bw / 2f
        val firstY = titleY + 88f

        if (x in left..(left + bw) && y in firstY..(firstY + 58f)) {
            screen = SCREEN_COUNTRY_SELECT
            invalidate()
            return
        }

        val musicY = firstY + 72f
        if (x in left..(left + bw) && y in musicY..(musicY + 58f)) {
            menuMusicOn = music.toggle()
            invalidate()
        }
    }

    private fun drawCountrySelect(canvas: Canvas) {
        canvas.drawColor(Color.rgb(18, 23, 27))

        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(232, 235, 232)
        text.textSize = 31f
        text.isFakeBoldText = true
        canvas.drawText("ВЫБЕРИ СТРАНУ", width / 2f, 54f, text)
        text.isFakeBoldText = false

        text.textSize = 13.5f
        text.color = Color.rgb(157, 169, 177)
        canvas.drawText("Кампания начинается в 1936 году.  Нажми на карточку страны.", width / 2f, 79f, text)

        val names = arrayOf("Германия", "Франция", "Польша", "Италия", "СССР", "Великобритания")
        val tags = arrayOf("GER", "FRA", "POL", "ITA", "USSR", "UK")
        val colors = intArrayOf(
            Color.rgb(85, 100, 112),
            Color.rgb(84, 108, 142),
            Color.rgb(145, 118, 75),
            Color.rgb(105, 126, 89),
            Color.rgb(143, 80, 76),
            Color.rgb(111, 101, 80)
        )

        val columns = 3
        val gap = 18f
        val cardW = min(270f, (width - gap * 4f) / columns)
        val cardH = min(150f, (height - 150f) / 2f - 14f)
        val totalW = cardW * columns + gap * (columns - 1)
        val startX = width / 2f - totalW / 2f
        val startY = 108f

        for (i in names.indices) {
            val col = i % columns
            val row = i / columns
            val left = startX + col * (cardW + gap)
            val top = startY + row * (cardH + gap)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(31, 38, 43)
            canvas.drawRoundRect(left, top, left + cardW, top + cardH, 9f, 9f, paint)

            paint.color = colors[i]
            canvas.drawRoundRect(left + 10f, top + 10f, left + 74f, top + 50f, 5f, 5f, paint)

            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            text.textSize = 15f
            text.isFakeBoldText = true
            canvas.drawText(tags[i], left + 42f, top + 36f, text)

            text.textAlign = Paint.Align.LEFT
            text.textSize = 19f
            text.color = Color.rgb(229, 233, 234)
            canvas.drawText(names[i], left + 88f, top + 38f, text)
            text.isFakeBoldText = false

            text.textSize = 11.5f
            text.color = Color.rgb(156, 169, 176)
            canvas.drawText(
                when (i) {
                    EuropeScenario.GERMANY -> "сильная промышленность и бронетехника"
                    EuropeScenario.FRANCE -> "сильная оборона и укреплённый фронт"
                    EuropeScenario.POLAND -> "сложная позиция между крупными державами"
                    EuropeScenario.ITALY -> "горная война и Средиземноморье"
                    EuropeScenario.USSR -> "большие резервы и длинный фронт"
                    else -> "островная база, авиация и морские порты"
                },
                left + 16f,
                top + 79f,
                text
            )

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(61, 73, 79)
            canvas.drawRoundRect(left + 16f, top + cardH - 43f, left + cardW - 16f, top + cardH - 12f, 5f, 5f, paint)

            text.textAlign = Paint.Align.CENTER
            text.textSize = 12.5f
            text.color = Color.WHITE
            canvas.drawText("НАЧАТЬ КАМПАНИЮ", left + cardW / 2f, top + cardH - 22f, text)
        }

        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f
        text.color = Color.rgb(135, 147, 153)
        canvas.drawText("Назад: системная кнопка Back / Esc", 14f, height - 16f, text)
    }

    private fun handleCountrySelectTouch(x: Float, y: Float) {
        val columns = 3
        val gap = 18f
        val cardW = min(270f, (width - gap * 4f) / columns)
        val cardH = min(150f, (height - 150f) / 2f - 14f)
        val totalW = cardW * columns + gap * (columns - 1)
        val startX = width / 2f - totalW / 2f
        val startY = 108f

        for (i in 0 until 6) {
            val col = i % columns
            val row = i / columns
            val left = startX + col * (cardW + gap)
            val top = startY + row * (cardH + gap)

            if (x in left..(left + cardW) && y in top..(top + cardH)) {
                startCountry(i)
                return
            }
        }
    }

    private fun startCountry(country: Int) {
        selectedCountry = country.coerceIn(0, EuropeScenario.COUNTRY_COUNT - 1)
        reset()
        screen = SCREEN_GAME
        speedIndex = 1
        flash("Кампания началась: ${nationNames[0]}. Удерживай снабжение и управляй дивизиями.")
        invalidate()
    }

    private fun drawCountryLabels(canvas: Canvas, top: Float, cw: Float, ch: Float) {
        for (owner in 0 until nations) {
            var sx = 0f
            var sy = 0f
            var count = 0
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (map[y][x].owner == owner) {
                        sx += x + 0.5f
                        sy += y + 0.5f
                        count++
                    }
                }
            }

            if (count < 3) continue
            val cx = (sx / count) * cw
            val cy = top + (sy / count) * ch

            text.textAlign = Paint.Align.CENTER
            text.textSize = if (count > 25) 18f else 13f
            text.isFakeBoldText = true
            text.color = Color.argb(95, 240, 240, 235)
            canvas.drawText(nationTags[owner], cx, cy, text)
            text.isFakeBoldText = false
        }
    }

    private fun drawTopBar(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(28, 34, 39)
        canvas.drawRect(0f, 0f, width.toFloat(), 66f, paint)

        paint.color = Color.rgb(48, 57, 64)
        canvas.drawRect(0f, 63f, width.toFloat(), 66f, paint)

        val s = nation[0]

        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(232, 235, 238)
        text.textSize = 20f
        text.isFakeBoldText = true
        canvas.drawText("EUROPE 1936 — HIGH COMMAND", 14f, 25f, text)

        text.isFakeBoldText = false
        text.textSize = 13.5f
        text.color = Color.rgb(182, 194, 202)
        canvas.drawText(
            "ЛС ${String.format("%.1f", s.manpower)}k   Винтовки ${s.equipment.toInt()}   Танки ${s.tanks.toInt()}   Самолёты ${s.aircraft.toInt()}   Топливо ${s.fuel.toInt()}   $${s.money.toInt()}",
            14f,
            49f,
            text
        )

        val speedX = width - 275f
        text.textAlign = Paint.Align.CENTER
        text.textSize = 15f
        text.color = Color.rgb(220, 225, 230)
        canvas.drawText("День $day", speedX - 56f, 25f, text)

        val labels = arrayOf("Ⅱ", "▶", "▶▶", "▶▶▶")
        val bw = 52f
        for (i in labels.indices) {
            paint.color = if (i == speedIndex) Color.rgb(66, 82, 91) else Color.rgb(35, 42, 48)
            canvas.drawRoundRect(speedX + i * bw, 8f, speedX + i * bw + 46f, 51f, 5f, 5f, paint)
            text.color = if (i == speedIndex) Color.WHITE else Color.rgb(165, 174, 180)
            text.textSize = 14f
            canvas.drawText(labels[i], speedX + i * bw + 23f, 35f, text)
        }
    }

    private fun drawMap(canvas: Canvas, top: Float, bottom: Float, cw: Float, ch: Float) {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = map[y][x]
                val left = x * cw
                val t = top + y * ch

                paint.style = Paint.Style.FILL
                paint.color = provinceColor(p)
                canvas.drawRect(left, t, left + cw + 0.5f, t + ch + 0.5f, paint)

                if (settings.showTerrainTexture) drawTerrainTexture(canvas, p, left, t, cw, ch)

                drawProvinceStructures(canvas, p, left, t, cw, ch)
            }
        }
    }

    private fun provinceColor(p: Province): Int {
        if (p.terrain == WarRules.WATER) return Color.rgb(43, 65, 76)

        val base = if (p.owner >= 0) nationColors[p.owner] else Color.rgb(102, 102, 94)
        val terrainFactor = when (p.terrain) {
            WarRules.FOREST -> 0.82f
            WarRules.HILLS -> 0.90f
            else -> 1f
        }

        return Color.rgb(
            (Color.red(base) * terrainFactor).toInt().coerceIn(0, 255),
            (Color.green(base) * terrainFactor).toInt().coerceIn(0, 255),
            (Color.blue(base) * terrainFactor).toInt().coerceIn(0, 255)
        )
    }

    private fun drawTerrainTexture(
        canvas: Canvas,
        p: Province,
        left: Float,
        top: Float,
        cw: Float,
        ch: Float
    ) {
        if (p.terrain == WarRules.WATER) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            paint.color = Color.argb(55, 145, 174, 188)
            canvas.drawLine(left + 4f, top + ch * 0.35f, left + cw - 4f, top + ch * 0.35f, paint)
            canvas.drawLine(left + 8f, top + ch * 0.67f, left + cw - 8f, top + ch * 0.67f, paint)
            return
        }

        if (p.terrain == WarRules.FOREST) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            paint.color = Color.argb(60, 22, 42, 28)
            canvas.drawLine(left + cw * 0.25f, top + ch * 0.20f, left + cw * 0.15f, top + ch * 0.70f, paint)
            canvas.drawLine(left + cw * 0.55f, top + ch * 0.12f, left + cw * 0.45f, top + ch * 0.76f, paint)
            canvas.drawLine(left + cw * 0.80f, top + ch * 0.25f, left + cw * 0.72f, top + ch * 0.72f, paint)
        }

        if (p.terrain == WarRules.HILLS) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            paint.color = Color.argb(65, 44, 39, 30)
            val path = Path()
            path.moveTo(left + cw * 0.08f, top + ch * 0.72f)
            path.quadTo(left + cw * 0.28f, top + ch * 0.28f, left + cw * 0.48f, top + ch * 0.72f)
            path.quadTo(left + cw * 0.69f, top + ch * 0.35f, left + cw * 0.92f, top + ch * 0.72f)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawProvinceStructures(
        canvas: Canvas,
        p: Province,
        left: Float,
        top: Float,
        cw: Float,
        ch: Float
    ) {
        if (p.terrain == WarRules.WATER) return

        val iconY = top + 8f
        var iconX = left + 6f

        paint.style = Paint.Style.FILL

        if (p.city) {
            paint.color = Color.rgb(225, 220, 194)
            canvas.drawRect(iconX, iconY, iconX + 5f, iconY + 9f, paint)
            canvas.drawRect(iconX + 7f, iconY - 3f, iconX + 12f, iconY + 9f, paint)
            iconX += 16f
        }

        if (p.port) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f
            paint.color = Color.rgb(145, 207, 224)
            canvas.drawCircle(iconX + 4f, iconY + 2f, 3f, paint)
            canvas.drawLine(iconX + 4f, iconY + 5f, iconX + 4f, iconY + 10f, paint)
            canvas.drawLine(iconX, iconY + 8f, iconX + 8f, iconY + 8f, paint)
            iconX += 13f
        }

        if (p.fort > 0) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(203, 188, 147)
            canvas.drawRect(iconX, iconY + 1f, iconX + 10f, iconY + 9f, paint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 8f
            text.color = Color.rgb(70, 63, 50)
            canvas.drawText(p.fort.toString(), iconX + 5f, iconY + 8f, text)
            iconX += 14f
        }

        if (p.silo) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(209, 133, 125)
            val path = Path()
            path.moveTo(iconX + 5f, iconY - 2f)
            path.lineTo(iconX + 10f, iconY + 9f)
            path.lineTo(iconX, iconY + 9f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }


    private fun drawProvinceBorders(canvas: Canvas, top: Float, cw: Float, ch: Float) {
        paint.style = Paint.Style.STROKE

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = map[y][x]
                if (p.terrain == WarRules.WATER) continue

                val left = x * cw
                val cellTop = top + y * ch

                fun edge(
                    nx: Int,
                    ny: Int,
                    x1: Float,
                    y1: Float,
                    x2: Float,
                    y2: Float
                ) {
                    if (nx !in 0 until cols || ny !in 0 until rows) {
                        paint.strokeWidth = 1.5f
                        paint.color = Color.argb(150, 18, 22, 24)
                        canvas.drawLine(x1, y1, x2, y2, paint)
                        return
                    }

                    val n = map[ny][nx]
                    if (n.terrain == WarRules.WATER) {
                        paint.strokeWidth = 1.35f
                        paint.color = Color.argb(155, 18, 24, 28)
                        canvas.drawLine(x1, y1, x2, y2, paint)
                        return
                    }

                    if (n.owner != p.owner) {
                        paint.strokeWidth = 2.2f
                        paint.color = Color.argb(210, 28, 31, 32)
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    } else if (n.regionId != p.regionId) {
                        paint.strokeWidth = 0.65f
                        paint.color = Color.argb(105, 25, 28, 30)
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }

                edge(x + 1, y, left + cw, cellTop, left + cw, cellTop + ch)
                edge(x, y + 1, left, cellTop + ch, left + cw, cellTop + ch)
            }
        }
    }

    private fun drawFrontLines(canvas: Canvas, top: Float, cw: Float, ch: Float) {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = map[y][x]
                if (p.terrain == WarRules.WATER || p.owner < 0) continue

                val left = x * cw
                val t = top + y * ch

                fun drawEdge(nx: Int, ny: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
                    if (nx !in 0 until cols || ny !in 0 until rows) return
                    val n = map[ny][nx]
                    if (n.terrain == WarRules.WATER || n.owner < 0 || n.owner == p.owner) return
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f
                    paint.color = Color.argb(220, 40, 31, 28)
                    canvas.drawLine(x1, y1, x2, y2, paint)
                    paint.strokeWidth = 0.8f
                    paint.color = Color.argb(220, 214, 151, 97)
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }

                drawEdge(x + 1, y, left + cw, t, left + cw, t + ch)
                drawEdge(x, y + 1, left, t + ch, left + cw, t + ch)
            }
        }
    }

    private fun drawDivisions(canvas: Canvas, top: Float, cw: Float, ch: Float) {
        val grouped = divisions.groupBy { it.x to it.y }

        for ((pos, units) in grouped) {
            val (x, y) = pos
            if (x !in 0 until cols || y !in 0 until rows) continue

            val maxVisible = min(3, units.size)
            for (i in 0 until maxVisible) {
                val d = units[i]
                val w = min(48f, cw * 0.86f)
                val h = min(28f, ch * 0.66f)
                val cx = x * cw + cw / 2f + i * 5f
                val cy = top + y * ch + ch / 2f + i * 4f
                val left = cx - w / 2f
                val t = cy - h / 2f

                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(28, 32, 34)
                canvas.drawRoundRect(left, t, left + w, t + h, 3f, 3f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (d.selected) 2.5f else 1f
                paint.color = if (d.selected) Color.rgb(245, 215, 96) else Color.rgb(205, 207, 202)
                canvas.drawRoundRect(left, t, left + w, t + h, 3f, 3f, paint)

                paint.style = Paint.Style.FILL
                paint.color = unitTypeColor(d.type)
                canvas.drawRect(left, t, left + w, t + 3.5f, paint)

                text.textAlign = Paint.Align.CENTER
                text.color = Color.WHITE
                text.textSize = 10.5f
                text.isFakeBoldText = true
                canvas.drawText(unitTypeLabel(d.type), cx, t + 14f, text)
                text.isFakeBoldText = false

                val barW = w - 8f
                val barLeft = left + 4f
                val barTop = t + h - 8f

                paint.color = Color.rgb(65, 70, 72)
                canvas.drawRect(barLeft, barTop, barLeft + barW, barTop + 2.3f, paint)
                paint.color = Color.rgb(112, 183, 101)
                canvas.drawRect(barLeft, barTop, barLeft + barW * (d.strength / 100f), barTop + 2.3f, paint)

                paint.color = Color.rgb(61, 67, 72)
                canvas.drawRect(barLeft, barTop + 3.5f, barLeft + barW, barTop + 5.8f, paint)
                paint.color = Color.rgb(78, 145, 205)
                canvas.drawRect(barLeft, barTop + 3.5f, barLeft + barW * (d.org / 100f), barTop + 5.8f, paint)
            }

            if (units.size > maxVisible) {
                text.textAlign = Paint.Align.RIGHT
                text.textSize = 10f
                text.color = Color.WHITE
                canvas.drawText("+${units.size - maxVisible}", x * cw + cw - 2f, top + y * ch + ch - 3f, text)
            }
        }

        for (d in divisions) {
            if (d.type != WarRules.AIR || d.missionX < 0 || d.missionY < 0) continue
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = Color.argb(110, 103, 194, 224)

            val x1 = d.x * cw + cw / 2f
            val y1 = top + d.y * ch + ch / 2f
            val x2 = d.missionX * cw + cw / 2f
            val y2 = top + d.missionY * ch + ch / 2f
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    private fun unitTypeLabel(type: Int): String = when (type) {
        WarRules.INFANTRY -> "INF"
        WarRules.ARMOR -> "ARM"
        WarRules.AIR -> "AIR"
        else -> "?"
    }

    private fun unitTypeColor(type: Int): Int = when (type) {
        WarRules.INFANTRY -> Color.rgb(94, 151, 89)
        WarRules.ARMOR -> Color.rgb(191, 132, 69)
        WarRules.AIR -> Color.rgb(78, 159, 196)
        else -> Color.GRAY
    }

    private fun drawBattles(canvas: Canvas, top: Float, cw: Float, ch: Float) {
        for (b in battles) {
            val left = b.targetX * cw
            val t = top + b.targetY * ch

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(205, 28, 28, 28)
            canvas.drawRoundRect(left + 4f, t + ch - 13f, left + cw - 4f, t + ch - 4f, 3f, 3f, paint)

            val normalized = ((b.progress + 35f) / 135f).coerceIn(0f, 1f)
            paint.color = Color.rgb(193, 123, 75)
            canvas.drawRoundRect(
                left + 5f,
                t + ch - 12f,
                left + 5f + (cw - 10f) * normalized,
                t + ch - 5f,
                2f,
                2f,
                paint
            )

            text.textAlign = Paint.Align.CENTER
            text.textSize = 11f
            text.color = Color.rgb(255, 228, 194)
            canvas.drawText("⚔", left + cw / 2f, t + 15f, text)
        }
    }

    private fun drawCursor(canvas: Canvas, top: Float, cw: Float, ch: Float) {
        if (cursorX !in 0 until cols || cursorY !in 0 until rows) return

        val left = cursorX * cw
        val t = top + cursorY * ch

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(220, 235, 215, 116)
        canvas.drawRect(left + 1f, t + 1f, left + cw - 1f, t + ch - 1f, paint)

        if (selectedProvinceX in 0 until cols && selectedProvinceY in 0 until rows) {
            val sl = selectedProvinceX * cw
            val st = top + selectedProvinceY * ch
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(190, 230, 235, 238)
            canvas.drawRect(sl + 3f, st + 3f, sl + cw - 3f, st + ch - 3f, paint)
        }
    }

    private fun drawBottomPanel(canvas: Canvas, mapBottom: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(24, 29, 33)
        canvas.drawRect(0f, mapBottom, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.rgb(57, 66, 70)
        canvas.drawRect(0f, mapBottom, width.toFloat(), mapBottom + 2f, paint)

        val tabs = arrayOf("АРМИЯ", "СТРОИТЕЛЬСТВО", "ПРОИЗВОДСТВО", "СОБЫТИЯ", "НАСТРОЙКИ")
        val tabW = width / tabs.size.toFloat()

        for (i in tabs.indices) {
            paint.style = Paint.Style.FILL
            paint.color = if (i == activePanel) Color.rgb(62, 75, 80) else Color.rgb(34, 41, 45)
            canvas.drawRect(i * tabW, mapBottom + 3f, (i + 1) * tabW - 2f, mapBottom + 37f, paint)

            text.textAlign = Paint.Align.CENTER
            text.textSize = 11.5f * settings.uiScale
            text.color = if (i == activePanel) Color.WHITE else Color.rgb(166, 177, 182)
            canvas.drawText(tabs[i], i * tabW + tabW / 2f, mapBottom + 25f, text)
        }

        when (activePanel) {
            0 -> drawArmyPanel(canvas, mapBottom + 42f)
            1 -> drawConstructionPanel(canvas, mapBottom + 42f)
            2 -> drawProductionPanel(canvas, mapBottom + 42f)
            3 -> drawEventsPanel(canvas, mapBottom + 42f)
            4 -> drawSettingsPanel(canvas, mapBottom + 42f)
        }

        text.textAlign = Paint.Align.LEFT
        text.textSize = 11.5f * settings.uiScale
        text.color = if (statusTimer > 0f) Color.rgb(210, 219, 226) else Color.rgb(145, 156, 162)
        canvas.drawText(
            if (statusTimer > 0f) status else "ЛКМ — выбрать/приказать • ПКМ — снять выделение под курсором • Esc — снять всё • Back — меню",
            12f,
            height - 10f,
            text
        )
    }

    private fun drawArmyPanel(canvas: Canvas, top: Float) {
        val selected = selectedDivisions()
        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(226, 231, 233)
        text.textSize = 13.5f * settings.uiScale
        text.isFakeBoldText = true
        canvas.drawText(
            if (selected.isEmpty()) "ШТАБ СУХОПУТНЫХ ВОЙСК" else "ВЫБРАНО: ${selected.size} дивизий",
            12f,
            top + 22f,
            text
        )
        text.isFakeBoldText = false

        text.textSize = 11.5f * settings.uiScale
        text.color = Color.rgb(166, 180, 188)

        if (selected.isEmpty()) {
            canvas.drawText(
                "Выбери контры дивизий на карте. Для приказа оставь их выбранными и нажми на провинцию.",
                12f,
                top + 45f,
                text
            )
        } else {
            val inf = selected.count { it.type == WarRules.INFANTRY }
            val arm = selected.count { it.type == WarRules.ARMOR }
            val air = selected.count { it.type == WarRules.AIR }
            val org = selected.map { it.org }.average().toInt()
            val str = selected.map { it.strength }.average().toInt()
            canvas.drawText("INF $inf   ARM $arm   AIR $air   организация $org%   сила $str%", 12f, top + 45f, text)
        }

        drawActionButtons(
            canvas,
            top + 57f,
            arrayOf("ПЕХОТА", "ТАНКИ", "АВИАЦИЯ", "СНЯТЬ ВЫБОР"),
            arrayOf("6k / 120", "4k / 70", "1k / 45", "")
        )
    }

    private fun drawConstructionPanel(canvas: Canvas, top: Float) {
        val p = selectedProvince()
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * settings.uiScale
        text.color = Color.rgb(183, 194, 200)
        if (p != null) {
            canvas.drawText(
                "Провинция: ${ownerName(p.owner)}   инфраструктура ${p.infrastructure}/5   промышленность ${p.industry}/5   форт ${p.fort}/5",
                12f,
                top + 25f,
                text
            )
        } else {
            canvas.drawText("Выбери свою провинцию для строительства.", 12f, top + 25f, text)
        }

        drawActionButtons(
            canvas,
            top + 43f,
            arrayOf("ФОРТ", "ГОРОД", "ПОРТ", "ИНФРА +", "ЗАВОД +"),
            arrayOf("$${WarRules.COST_FORT}", "$${WarRules.COST_CITY}", "$${WarRules.COST_PORT}", "$120", "$170")
        )
    }

    private fun drawProductionPanel(canvas: Canvas, top: Float) {
        val s = nation[0]
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * settings.uiScale
        text.color = Color.rgb(187, 198, 203)
        canvas.drawText(
            "ЛС ${String.format("%.1f", s.manpower)}k   винтовки ${s.equipment.toInt()}   танки ${s.tanks.toInt()}   самолёты ${s.aircraft.toInt()}   топливо ${s.fuel.toInt()}",
            12f,
            top + 25f,
            text
        )

        drawActionButtons(
            canvas,
            top + 43f,
            arrayOf("СФОРМИРОВАТЬ INF", "СФОРМИРОВАТЬ ARM", "СФОРМИРОВАТЬ AIR", if (s.nukes > 0) "☢ ВЫБРАТЬ ЦЕЛЬ" else "ШАХТА / ☢"),
            arrayOf("120 винтовок", "70 танков", "45 самолётов", "$${WarRules.COST_SILO}+")
        )
    }

    private fun drawEventsPanel(canvas: Canvas, top: Float) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12.5f * settings.uiScale
        text.color = Color.rgb(215, 221, 224)
        canvas.drawText("ХРОНИКА КАМПАНИИ", 12f, top + 23f, text)

        text.textSize = 10.8f * settings.uiScale
        text.color = Color.rgb(159, 172, 178)
        val shown = historicalEvents.filter { it.shown }.takeLast(4)
        if (shown.isEmpty()) {
            canvas.drawText("Исторические события будут появляться по ходу времени.", 12f, top + 46f, text)
        } else {
            shown.forEachIndexed { i, e ->
                canvas.drawText("День ${e.day}: ${e.title}", 12f, top + 46f + i * 18f, text)
            }
        }
    }

    private fun drawSettingsPanel(canvas: Canvas, top: Float) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11.8f * settings.uiScale
        text.color = Color.rgb(181, 194, 201)
        canvas.drawText(
            "Масштаб UI: ${String.format("%.1f", settings.uiScale)}   границы провинций: ${if (settings.showProvinceBorders) "да" else "нет"}   рельеф: ${if (settings.showTerrainTexture) "да" else "нет"}",
            12f,
            top + 24f,
            text
        )

        drawActionButtons(
            canvas,
            top + 43f,
            arrayOf("UI −", "UI +", "ГРАНИЦЫ", "РЕЛЬЕФ", "ПОДПИСИ", if (music.muted) "МУЗЫКА OFF" else "МУЗЫКА ON"),
            arrayOf("", "", "", "", "", "")
        )
    }

    private fun drawActionButtons(
        canvas: Canvas,
        top: Float,
        labels: Array<String>,
        sublabels: Array<String>
    ) {
        val gap = 5f
        val left = 12f
        val usable = width - 24f
        val bw = (usable - gap * (labels.size - 1)) / labels.size

        for (i in labels.indices) {
            val x1 = left + i * (bw + gap)
            val x2 = x1 + bw

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(42, 51, 56)
            canvas.drawRoundRect(x1, top, x2, top + 49f, 5f, 5f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            paint.color = Color.rgb(75, 87, 92)
            canvas.drawRoundRect(x1, top, x2, top + 49f, 5f, 5f, paint)

            text.textAlign = Paint.Align.CENTER
            text.textSize = 10.7f * settings.uiScale
            text.color = Color.rgb(229, 232, 233)
            canvas.drawText(labels[i], (x1 + x2) / 2f, top + 20f, text)

            if (sublabels[i].isNotEmpty()) {
                text.textSize = 9.3f * settings.uiScale
                text.color = Color.rgb(148, 162, 169)
                canvas.drawText(sublabels[i], (x1 + x2) / 2f, top + 38f, text)
            }
        }
    }

    private fun checkHistoricalEvents() {
        if (currentEvent != null) return
        val event = historicalEvents.firstOrNull { !it.shown && day >= it.day } ?: return
        event.shown = true
        currentEvent = event
        speedIndex = 0

        when (event.day) {
            67 -> nation[0].money += 35f
            199 -> nation[0].fuel += 60f
            802 -> nation[0].equipment += 90f
            1003 -> nation[0].money += 50f
        }
    }

    private fun drawEventOverlay(canvas: Canvas) {
        val event = currentEvent ?: return

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 7, 10, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val w = min(width * 0.72f, 700f)
        val h = 230f
        val left = width / 2f - w / 2f
        val top = height / 2f - h / 2f

        paint.color = Color.rgb(35, 42, 46)
        canvas.drawRoundRect(left, top, left + w, top + h, 10f, 10f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = Color.rgb(104, 113, 115)
        canvas.drawRoundRect(left, top, left + w, top + h, 10f, 10f, paint)

        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(236, 234, 224)
        text.textSize = 21f * settings.uiScale
        text.isFakeBoldText = true
        canvas.drawText(event.title, width / 2f, top + 45f, text)
        text.isFakeBoldText = false

        text.textSize = 12.5f * settings.uiScale
        text.color = Color.rgb(188, 199, 203)
        canvas.drawText(event.body, width / 2f, top + 83f, text)
        canvas.drawText(event.effect, width / 2f, top + 111f, text)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(66, 80, 85)
        canvas.drawRoundRect(left + w * 0.30f, top + 158f, left + w * 0.70f, top + 205f, 6f, 6f, paint)

        text.textSize = 13f * settings.uiScale
        text.color = Color.WHITE
        canvas.drawText("ПРОДОЛЖИТЬ", width / 2f, top + 188f, text)
    }

    private fun ownerName(owner: Int): String = when {
        owner == WarRules.NEUTRAL -> "нейтрал"
        owner == WarRules.WATER_OWNER -> "море"
        owner in nationNames.indices -> if (owner == 0) "Вы — ${nationNames[owner]}" else nationNames[owner]
        else -> "неизвестно"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()

        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true

        if (screen == SCREEN_MAIN_MENU) {
            handleMainMenuTouch(event.x, event.y)
            return true
        }

        if (screen == SCREEN_COUNTRY_SELECT) {
            handleCountrySelectTouch(event.x, event.y)
            return true
        }

        if (currentEvent != null) {
            currentEvent = null
            speedIndex = 1
            invalidate()
            return true
        }

        if (event.y <= 66f) {
            handleTopTouch(event.x)
            return true
        }

        val mapBottom = height - 184f
        if (event.y >= mapBottom) {
            handleBottomTouch(event.x, event.y, mapBottom)
            return true
        }

        val top = 66f
        val cw = width / cols.toFloat()
        val ch = (mapBottom - top) / rows.toFloat()

        val x = (event.x / cw).toInt().coerceIn(0, cols - 1)
        val y = ((event.y - top) / ch).toInt().coerceIn(0, rows - 1)

        cursorX = x
        cursorY = y

        if (nukeTargetMode) {
            launchPlayerNuke(x, y)
            return true
        }

        handleProvinceTap(x, y)
        return true
    }

    private fun handleTopTouch(x: Float) {
        val speedX = width - 275f
        if (x < speedX) return
        val index = ((x - speedX) / 52f).toInt()
        if (index in speeds.indices) {
            speedIndex = index
            flash(if (speedIndex == 0) "Пауза" else "Скорость x${speeds[speedIndex].toInt()}")
        }
    }

    private fun handleBottomTouch(x: Float, y: Float, mapBottom: Float) {
        val tabW = width / 5f
        if (y <= mapBottom + 39f) {
            activePanel = (x / tabW).toInt().coerceIn(0, 4)
            return
        }

        val contentTop = mapBottom + 99f

        val count = when (activePanel) {
            0 -> 4
            1 -> 5
            2 -> 4
            3 -> 0
            else -> 6
        }
        if (count == 0) return
        if (y !in contentTop..(contentTop + 54f)) return

        val left = 12f
        val gap = 5f
        val usable = width - 24f
        val bw = (usable - gap * (count - 1)) / count
        val index = ((x - left) / (bw + gap)).toInt().coerceIn(0, count - 1)

        when (activePanel) {
            0 -> when (index) {
                0 -> playerTrain(WarRules.INFANTRY)
                1 -> playerTrain(WarRules.ARMOR)
                2 -> playerTrain(WarRules.AIR)
                3 -> {
                    clearSelection()
                    flash("Выделение снято.")
                }
            }

            1 -> when (index) {
                0 -> buildFort()
                1 -> buildCity()
                2 -> buildPort()
                3 -> upgradeInfrastructure()
                4 -> upgradeIndustry()
            }

            2 -> when (index) {
                0 -> playerTrain(WarRules.INFANTRY)
                1 -> playerTrain(WarRules.ARMOR)
                2 -> playerTrain(WarRules.AIR)
                3 -> nuclearButton()
            }

            4 -> when (index) {
                0 -> settings.uiScale = max(0.8f, settings.uiScale - 0.1f)
                1 -> settings.uiScale = min(1.3f, settings.uiScale + 0.1f)
                2 -> settings.showProvinceBorders = !settings.showProvinceBorders
                3 -> settings.showTerrainTexture = !settings.showTerrainTexture
                4 -> settings.showCountryLabels = !settings.showCountryLabels
                5 -> music.toggle()
            }
        }
    }

    private fun upgradeInfrastructure() {
        val p = selectedOwnedProvince() ?: return
        if (p.infrastructure >= 5) {
            flash("Инфраструктура уже максимального уровня.")
            return
        }
        if (nation[0].money < 120f) {
            flash("Нужно $120.")
            return
        }
        nation[0].money -= 120f
        p.infrastructure++
        flash("Инфраструктура улучшена до ${p.infrastructure}/5.")
    }

    private fun upgradeIndustry() {
        val p = selectedOwnedProvince() ?: return
        if (p.industry >= 5) {
            flash("Промышленность уже максимального уровня.")
            return
        }
        if (nation[0].money < 170f) {
            flash("Нужно $170.")
            return
        }
        nation[0].money -= 170f
        p.industry++
        flash("Промышленность улучшена до ${p.industry}/5.")
    }

    private fun handleProvinceTap(x: Int, y: Int) {
        val p = map[y][x]
        selectedProvinceX = x
        selectedProvinceY = y

        if (p.terrain == WarRules.WATER) {
            flash("Морская провинция. Наземные дивизии сюда не переходят.")
            return
        }

        val selected = selectedDivisions()
        if (selected.isEmpty()) {
            val ownUnits = divisions.filter { it.owner == 0 && it.x == x && it.y == y }
            if (ownUnits.isNotEmpty()) {
                clearSelection()
                ownUnits.forEach { it.selected = true }
                flash("Выбрано дивизий: ${ownUnits.size}. Теперь укажи приказ.")
            } else {
                flash(if (p.owner == 0) "Своя провинция выбрана." else "Провинция выбрана.")
            }
            return
        }

        val selectedCell = selected.firstOrNull()?.let { it.x == x && it.y == y } ?: false
        if (selectedCell && selected.all { it.x == x && it.y == y }) {
            clearSelection()
            flash("Выделение снято.")
            return
        }

        val air = selected.filter { it.type == WarRules.AIR }
        val land = selected.filter { it.type != WarRules.AIR }

        if (air.isNotEmpty()) {
            issueAirMission(air, x, y)
        }

        if (land.isNotEmpty()) {
            if (p.owner == 0) {
                issueFriendlyMove(land, x, y)
            } else {
                issueAttackOrder(land, x, y)
            }
        }
    }

    private fun issueAirMission(air: List<Division>, x: Int, y: Int) {
        var assigned = 0
        for (wing in air) {
            val range = abs(wing.x - x) + abs(wing.y - y)
            if (range <= 7) {
                wing.missionX = x
                wing.missionY = y
                assigned++
            }
        }

        if (assigned > 0) {
            flash("Авиация назначена в район: $assigned крыло(а).")
        } else {
            flash("Цель вне радиуса авиации. Перебазируй крыло ближе.")
        }
    }

    private fun issueFriendlyMove(land: List<Division>, tx: Int, ty: Int) {
        var ordered = 0
        for (d in land) {
            val path = findFriendlyPath(d.owner, d.x, d.y, tx, ty)
            if (path != null) {
                d.order.clear()
                d.order.addAll(path)
                d.attackX = -1
                d.attackY = -1
                d.moveTimer = if (d.type == WarRules.ARMOR) 0.20f else 0.35f
                ordered++
            }
        }

        flash(if (ordered > 0) "Приказ на перемещение: $ordered дивизий." else "Нет сухопутного маршрута по своей территории.")
    }

    private fun issueAttackOrder(land: List<Division>, tx: Int, ty: Int) {
        val target = map[ty][tx]
        if (target.terrain == WarRules.WATER) {
            flash("Наземная атака по морю невозможна.")
            return
        }

        var ordered = 0
        for (d in land) {
            d.order.clear()
            d.attackX = tx
            d.attackY = ty

            if (adjacent(d.x, d.y, tx, ty)) {
                joinBattle(d.owner, tx, ty, d.id)
                ordered++
                continue
            }

            val staging = neighbors(tx, ty)
                .filter {
                    map[it.second][it.first].owner == d.owner &&
                        map[it.second][it.first].terrain != WarRules.WATER
                }
                .mapNotNull { candidate ->
                    findFriendlyPath(d.owner, d.x, d.y, candidate.first, candidate.second)
                        ?.let { path -> candidate to path }
                }
                .minByOrNull { it.second.size }

            if (staging != null) {
                d.order.addAll(staging.second)
                d.moveTimer = if (d.type == WarRules.ARMOR) 0.20f else 0.35f
                ordered++
            } else {
                d.attackX = -1
                d.attackY = -1
            }
        }

        flash(
            if (ordered > 0) {
                "Наступление подготовлено: $ordered дивизий. Захват произойдёт только после победы в бою."
            } else {
                "Нет своих провинций рядом с целью — сначала подведи фронт."
            }
        )
    }

    private fun joinBattle(owner: Int, tx: Int, ty: Int, divisionId: Int) {
        val target = map[ty][tx]
        if (target.owner == owner || target.terrain == WarRules.WATER) return

        var battle = battles.firstOrNull { it.targetX == tx && it.targetY == ty }
        if (battle != null && battle.attackerOwner != owner) return

        if (battle == null) {
            battle = Battle(
                attackerOwner = owner,
                targetX = tx,
                targetY = ty,
                attackers = mutableListOf(),
                progress = 0f
            )
            battles += battle
        }

        if (divisionId !in battle.attackers) {
            battle.attackers += divisionId
        }
    }

    private fun playerTrain(type: Int) {
        val p = selectedProvince()
        if (p == null || selectedProvinceX < 0 || selectedProvinceY < 0) {
            flash("Выбери свою провинцию с городом.")
            return
        }

        if (p.owner != 0 || !p.city) {
            flash("Новые части формируются только в своей городской провинции.")
            return
        }

        trainDivision(0, type, selectedProvinceX, selectedProvinceY, ai = false)
    }

    private fun trainDivision(owner: Int, type: Int, x: Int, y: Int, ai: Boolean) {
        val s = nation[owner]

        when (type) {
            WarRules.INFANTRY -> {
                if (s.manpower < 6f || s.equipment < 120f) {
                    if (!ai) flash("Для пехоты нужно 6k людских ресурсов и 120 винтовок.")
                    return
                }
                s.manpower -= 6f
                s.equipment -= 120f
            }

            WarRules.ARMOR -> {
                if (s.manpower < 4f || s.tanks < 70f || s.equipment < 45f) {
                    if (!ai) flash("Для танковой дивизии нужно 4k ЛС, 70 танков и 45 снаряжения.")
                    return
                }
                s.manpower -= 4f
                s.tanks -= 70f
                s.equipment -= 45f
            }

            WarRules.AIR -> {
                if (s.manpower < 1f || s.aircraft < 45f) {
                    if (!ai) flash("Для авиакрыла нужно 1k ЛС и 45 самолётов.")
                    return
                }
                s.manpower -= 1f
                s.aircraft -= 45f
            }
        }

        addDivision(owner, type, x, y)
        divisions.last().org = 72f
        divisions.last().strength = 90f
        if (!ai) flash("Новая часть сформирована: ${unitTypeLabel(type)}.")
    }

    private fun buildFort() {
        val p = selectedOwnedProvince() ?: return
        if (p.fort >= 5) {
            flash("Максимальный уровень форта: 5.")
            return
        }
        if (nation[0].money < WarRules.COST_FORT) {
            flash("На форт нужно $${WarRules.COST_FORT}.")
            return
        }
        nation[0].money -= WarRules.COST_FORT
        p.fort++
        flash("Форт усилен до уровня ${p.fort}.")
    }

    private fun buildCity() {
        val p = selectedOwnedProvince() ?: return
        if (p.city) {
            flash("Здесь уже есть город.")
            return
        }
        if (nation[0].money < WarRules.COST_CITY) {
            flash("На город нужно $${WarRules.COST_CITY}.")
            return
        }
        nation[0].money -= WarRules.COST_CITY
        p.city = true
        flash("Город построен: снабжение и производство улучшены.")
    }

    private fun buildPort() {
        val p = selectedOwnedProvince() ?: return
        if (p.port) {
            flash("Здесь уже есть порт.")
            return
        }
        if (!isCoastal(selectedProvinceX, selectedProvinceY)) {
            flash("Порт можно построить только на побережье.")
            return
        }
        if (nation[0].money < WarRules.COST_PORT) {
            flash("На порт нужно $${WarRules.COST_PORT}.")
            return
        }
        nation[0].money -= WarRules.COST_PORT
        p.port = true
        flash("Порт построен: он стал источником снабжения.")
    }

    private fun nuclearButton() {
        val s = nation[0]

        if (s.nukes > 0) {
            nukeTargetMode = !nukeTargetMode
            flash(if (nukeTargetMode) "☢ Выбери вражескую провинцию." else "Ядерный режим отменён.")
            return
        }

        val p = selectedOwnedProvince() ?: return
        if (!p.silo) {
            if (s.money < WarRules.COST_SILO) {
                flash("На ракетную шахту нужно $${WarRules.COST_SILO}.")
                return
            }
            s.money -= WarRules.COST_SILO
            p.silo = true
            flash("Ракетная шахта построена. Нажми кнопку ещё раз для производства боеголовки.")
            return
        }

        if (s.money < WarRules.COST_NUKE) {
            flash("На ядерную боеголовку нужно $${WarRules.COST_NUKE}.")
            return
        }

        s.money -= WarRules.COST_NUKE
        s.nukes++
        flash("Боеголовка готова. Нажми кнопку ещё раз и укажи цель.")
    }

    private fun launchPlayerNuke(x: Int, y: Int) {
        val s = nation[0]
        if (s.nukes <= 0) {
            nukeTargetMode = false
            return
        }

        val target = map[y][x]
        if (target.terrain == WarRules.WATER || target.owner == 0) {
            flash("Нужна вражеская наземная цель.")
            return
        }

        s.nukes--
        nukeTargetMode = false

        for (yy in max(0, y - 1)..min(rows - 1, y + 1)) {
            for (xx in max(0, x - 1)..min(cols - 1, x + 1)) {
                val p = map[yy][xx]
                if (p.terrain == WarRules.WATER) continue

                p.garrison *= if (xx == x && yy == y) 0.05f else 0.30f
                p.fort = max(0, p.fort - 2)

                val units = divisions.filter { it.x == xx && it.y == yy }
                for (d in units) {
                    d.strength *= if (xx == x && yy == y) 0.20f else 0.48f
                    d.org *= 0.18f
                }

                if (xx == x && yy == y) {
                    p.owner = WarRules.NEUTRAL
                }
            }
        }

        divisions.removeAll { it.strength <= 2f }
        flash("☢ Ядерный удар: центр цели нейтрализован, соседние части тяжело повреждены.")
    }

    private fun selectedOwnedProvince(): Province? {
        val p = selectedProvince()
        if (p == null || p.owner != 0) {
            flash("Сначала выбери свою провинцию.")
            return null
        }
        return p
    }

    private fun selectedProvince(): Province? {
        if (selectedProvinceX !in 0 until cols || selectedProvinceY !in 0 until rows) return null
        return map[selectedProvinceY][selectedProvinceX]
    }

    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (screen == SCREEN_MAIN_MENU) {
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) {
                screen = SCREEN_COUNTRY_SELECT
                invalidate()
                return true
            }
            if (code == KeyEvent.KEYCODE_M) {
                menuMusicOn = music.toggle()
                return true
            }
            return super.onKeyDown(code, event)
        }

        if (screen == SCREEN_COUNTRY_SELECT) {
            when (code) {
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                    screen = SCREEN_MAIN_MENU
                    invalidate()
                    return true
                }
                KeyEvent.KEYCODE_1 -> startCountry(EuropeScenario.GERMANY)
                KeyEvent.KEYCODE_2 -> startCountry(EuropeScenario.FRANCE)
                KeyEvent.KEYCODE_3 -> startCountry(EuropeScenario.POLAND)
                KeyEvent.KEYCODE_4 -> startCountry(EuropeScenario.ITALY)
                KeyEvent.KEYCODE_5 -> startCountry(EuropeScenario.USSR)
                KeyEvent.KEYCODE_6 -> startCountry(EuropeScenario.UK)
            }
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX = max(0, cursorX - 1)

            KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX = min(cols - 1, cursorX + 1)

            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_DPAD_UP -> cursorY = max(0, cursorY - 1)

            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorY = min(rows - 1, cursorY + 1)

            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                selectedProvinceX = cursorX
                selectedProvinceY = cursorY
                if (nukeTargetMode) launchPlayerNuke(cursorX, cursorY)
                else handleProvinceTap(cursorX, cursorY)
            }

            KeyEvent.KEYCODE_1 -> playerTrain(WarRules.INFANTRY)
            KeyEvent.KEYCODE_2 -> playerTrain(WarRules.ARMOR)
            KeyEvent.KEYCODE_3 -> playerTrain(WarRules.AIR)
            KeyEvent.KEYCODE_F -> buildFort()
            KeyEvent.KEYCODE_C -> buildCity()
            KeyEvent.KEYCODE_P -> buildPort()
            KeyEvent.KEYCODE_N -> nuclearButton()
            KeyEvent.KEYCODE_M -> music.toggle()
            KeyEvent.KEYCODE_R -> reset()

            KeyEvent.KEYCODE_ESCAPE -> {
                clearSelection()
                nukeTargetMode = false
                flash("Выделение снято.")
            }

            KeyEvent.KEYCODE_BACK -> {
                speedIndex = 0
                screen = SCREEN_MAIN_MENU
                clearSelection()
                invalidate()
            }

            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_EQUALS -> speedIndex = min(speeds.lastIndex, speedIndex + 1)

            KeyEvent.KEYCODE_MINUS -> speedIndex = max(0, speedIndex - 1)

            else -> return super.onKeyDown(code, event)
        }
        return true
    }

    private fun findFriendlyPath(
        owner: Int,
        sx: Int,
        sy: Int,
        tx: Int,
        ty: Int
    ): List<Pair<Int, Int>>? {
        if (sx == tx && sy == ty) return emptyList()
        if (map[ty][tx].owner != owner || map[ty][tx].terrain == WarRules.WATER) return null

        val seen = Array(rows) { BooleanArray(cols) }
        val prevX = Array(rows) { IntArray(cols) { -1 } }
        val prevY = Array(rows) { IntArray(cols) { -1 } }
        val q: ArrayDeque<Pair<Int, Int>> = ArrayDeque()

        q.add(sx to sy)
        seen[sy][sx] = true

        while (q.isNotEmpty()) {
            val (x, y) = q.removeFirst()
            for ((nx, ny) in neighbors(x, y)) {
                if (seen[ny][nx]) continue
                val p = map[ny][nx]
                if (p.owner != owner || p.terrain == WarRules.WATER) continue

                seen[ny][nx] = true
                prevX[ny][nx] = x
                prevY[ny][nx] = y

                if (nx == tx && ny == ty) {
                    val path = mutableListOf<Pair<Int, Int>>()
                    var cx = tx
                    var cy = ty

                    while (!(cx == sx && cy == sy)) {
                        path += cx to cy
                        val px = prevX[cy][cx]
                        val py = prevY[cy][cx]
                        cx = px
                        cy = py
                    }

                    path.reverse()
                    return path
                }

                q.add(nx to ny)
            }
        }

        return null
    }

    private fun clearSelection() {
        for (d in divisions) d.selected = false
    }

    private fun selectedDivisions(): List<Division> = divisions.filter { it.owner == 0 && it.selected }

    private fun divisionById(id: Int): Division? = divisions.firstOrNull { it.id == id }

    private fun landDivisionsAt(x: Int, y: Int): List<Division> =
        divisions.filter { it.x == x && it.y == y && it.type != WarRules.AIR }

    private fun adjacent(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        abs(x1 - x2) + abs(y1 - y2) == 1

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> = buildList {
        if (x > 0) add(x - 1 to y)
        if (x < cols - 1) add(x + 1 to y)
        if (y > 0) add(x to y - 1)
        if (y < rows - 1) add(x to y + 1)
    }

    private fun isCoastal(x: Int, y: Int): Boolean {
        if (map[y][x].terrain == WarRules.WATER) return false
        return neighbors(x, y).any { map[it.second][it.first].terrain == WarRules.WATER }
    }

    private fun findNearestCoast(owner: Int, sx: Int, sy: Int): Pair<Int, Int>? {
        return ownedProvinces(owner)
            .filter { isCoastal(it.first, it.second) }
            .minByOrNull { abs(it.first - sx) + abs(it.second - sy) }
    }

    private fun provinceCount(owner: Int): Int {
        var count = 0
        for (row in map) for (p in row) if (p.owner == owner) count++
        return count
    }

    private fun countCities(owner: Int): Int {
        var count = 0
        for (row in map) for (p in row) if (p.owner == owner && p.city) count++
        return count
    }

    private fun countPorts(owner: Int): Int {
        var count = 0
        for (row in map) for (p in row) if (p.owner == owner && p.port) count++
        return count
    }

    private fun ownedProvinces(owner: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (map[y][x].owner == owner) result += x to y
            }
        }
        return result
    }

    private fun checkVictory() {
        if (provinceCount(0) == 0 || divisions.none { it.owner == 0 && it.type != WarRules.AIR }) {
            gameOver = "КАМПАНИЯ ПРОИГРАНА"
            speedIndex = 0
            return
        }

        val livingEnemies = (1 until nations).count { provinceCount(it) > 0 }
        if (livingEnemies == 0) {
            gameOver = "ПОБЕДА В КАМПАНИИ"
            speedIndex = 0
        }
    }

    private fun flash(message: String) {
        status = message
        statusTimer = 4f
    }
}
