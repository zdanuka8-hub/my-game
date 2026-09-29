package com.example.territoryclash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class VectorGameView(
    context: Context,
    private val music: BackgroundMusic
) : View(context) {

    companion object {
        private const val SCREEN_MENU = 0
        private const val SCREEN_COUNTRIES = 1
        private const val SCREEN_GAME = 2

        private const val TAB_ARMY = 0
        private const val TAB_CONSTRUCTION = 1
        private const val TAB_PRODUCTION = 2
        private const val TAB_DIPLOMACY = 3
        private const val TAB_EVENTS = 4
        private const val TAB_SETTINGS = 5
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = false
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = false
    }

    private var screen = SCREEN_MENU
    private var activeTab = TAB_ARMY
    private var playerCountry = EuropeScenario.GERMANY

    private var provinces = mutableListOf<VectorProvince>()
    private val divisions = mutableListOf<StrategicDivision>()
    private val battles = mutableListOf<StrategicBattle>()
    private val nation = Array(EuropeScenario.COUNTRY_COUNT) { NationState() }
    private val wars = Array(EuropeScenario.COUNTRY_COUNT) {
        BooleanArray(EuropeScenario.COUNTRY_COUNT)
    }

    private val settings = GameSettings()

    private var nextDivisionId = 1
    private var selectedProvinceId = -1
    private var hoveredProvinceId = -1

    private var lastFrame = System.nanoTime()
    private var economyClock = 0f
    private var aiClock = 0f
    private var dayClock = 0f
    private var day = 1
    private val speeds = floatArrayOf(0f, 1f, 2f, 4f)
    private var speedIndex = 1

    private var status = "Европа 1936"
    private var statusTimer = 0f
    private var gameOver: String? = null

    private val historicalEvents = mutableListOf<HistoricalEvent>()
    private var currentEvent: HistoricalEvent? = null

    private var riflePriority = 1f
    private var tankPriority = 0.45f
    private var airPriority = 0.35f

    private var supplyCache = Array(EuropeScenario.COUNTRY_COUNT) { FloatArray(1) { 0.35f } }
    private var supplyDirty = true

    private var mapScale = 1f
    private var mapPanX = 0f
    private var mapPanY = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var draggingMap = false
    private var downSecondary = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = screen == SCREEN_GAME

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = mapScale
                mapScale = (mapScale * detector.scaleFactor).coerceIn(0.90f, 3.6f)
                if (abs(mapScale - old) > 0.001f) {
                    clampPan()
                    invalidate()
                }
                return true
            }
        }
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        resetCampaign(playerCountry)
        screen = SCREEN_MENU
    }

    private fun resetCampaign(country: Int) {
        playerCountry = country.coerceIn(0, EuropeScenario.COUNTRY_COUNT - 1)
        provinces = EuropeVectorMap.build()
        divisions.clear()
        battles.clear()
        nextDivisionId = 1
        selectedProvinceId = -1
        hoveredProvinceId = -1
        gameOver = null
        activeTab = TAB_ARMY

        mapScale = 1f
        mapPanX = 0f
        mapPanY = 0f

        day = 1
        dayClock = 0f
        economyClock = 0f
        aiClock = 0f
        speedIndex = 1

        for (a in wars.indices) {
            for (b in wars[a].indices) wars[a][b] = false
        }

        for (owner in nation.indices) {
            val size = provinces.count { it.owner == owner }
            nation[owner] = NationState(
                manpower = 32f + size * 1.1f,
                equipment = 550f + size * 31f,
                tanks = 35f + size * 5.2f,
                aircraft = 25f + size * 3.7f,
                fuel = 260f + size * 14f,
                money = 300f + size * 17f,
                nukes = 0
            )
        }

        createStartingArmies()
        buildHistoricalEvents()

        riflePriority = 1f
        tankPriority = 0.45f
        airPriority = 0.35f

        supplyCache = Array(EuropeScenario.COUNTRY_COUNT) {
            FloatArray(provinces.size) { 0.35f }
        }
        supplyDirty = true
        rebuildSupply()

        status = "Кампания началась: ${EuropeScenario.canonicalName(playerCountry)}"
        statusTimer = 5f
        lastFrame = System.nanoTime()
        invalidate()
    }

    private fun buildHistoricalEvents() {
        historicalEvents.clear()

        historicalEvents += HistoricalEvent(
            67,
            "Ремилитаризация Рейнской области",
            "7 марта 1936 года германские войска вошли в Рейнскую демилитаризованную зону.",
            "Германия получает политический импульс."
        )
        historicalEvents += HistoricalEvent(
            199,
            "Гражданская война в Испании",
            "17 июля 1936 года военный мятеж в Испании перерос в гражданскую войну.",
            "Испания теряет часть людских ресурсов и снаряжения."
        )
        historicalEvents += HistoricalEvent(
            802,
            "Аншлюс Австрии",
            "В марте 1938 года Австрия была присоединена к Германии.",
            "Австрийские провинции переходят под немецкий контроль."
        )
        historicalEvents += HistoricalEvent(
            1003,
            "Мюнхенское соглашение",
            "29–30 сентября 1938 года было заключено Мюнхенское соглашение.",
            "Несколько приграничных чехословацких провинций переходят Германии."
        )
        historicalEvents += HistoricalEvent(
            1340,
            "Вторжение в Польшу",
            "1 сентября 1939 года Германия вторглась в Польшу.",
            "Германия и Польша вступают в войну."
        )
        historicalEvents += HistoricalEvent(
            1342,
            "Британия и Франция вступают в войну",
            "3 сентября 1939 года Великобритания и Франция объявили войну Германии.",
            "Начинается большая европейская война."
        )
    }

    private fun createStartingArmies() {
        for (owner in 0 until EuropeScenario.COUNTRY_COUNT) {
            val owned = provinces.filter { it.owner == owner }
            if (owned.isEmpty()) continue

            val capital = owned.firstOrNull { it.city } ?: owned.first()
            val size = owned.size
            val infantry = when {
                size >= 35 -> 9
                size >= 20 -> 7
                size >= 12 -> 5
                size >= 7 -> 4
                else -> 2
            }

            repeat(infantry) { index ->
                val province = owned[index % owned.size]
                addDivision(owner, WarRules.INFANTRY, province.id)
            }

            if (size >= 8) {
                addDivision(owner, WarRules.ARMOR, capital.id)
            }
            if (size >= 18) {
                val second = owned[min(owned.lastIndex, 2)]
                addDivision(owner, WarRules.ARMOR, second.id)
            }

            addDivision(owner, WarRules.AIR, capital.id)

            if (owner == playerCountry && size >= 12) {
                addDivision(owner, WarRules.INFANTRY, capital.id)
                addDivision(owner, WarRules.ARMOR, capital.id)
            }
        }
    }

    private fun addDivision(owner: Int, type: Int, provinceId: Int) {
        divisions += StrategicDivision(
            id = nextDivisionId++,
            owner = owner,
            type = type,
            provinceId = provinceId,
            strength = 100f,
            org = 100f
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val rawDt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrame = now

        when (screen) {
            SCREEN_MENU -> {
                drawMainMenu(canvas)
                postInvalidateOnAnimation()
                return
            }
            SCREEN_COUNTRIES -> {
                drawCountrySelect(canvas)
                postInvalidateOnAnimation()
                return
            }
        }

        if (statusTimer > 0f) statusTimer -= rawDt

        val dt = rawDt * speeds[speedIndex]
        if (dt > 0f && currentEvent == null && gameOver == null) {
            updateGame(dt)
        }

        drawGame(canvas)
        drawEventOverlay(canvas)
        postInvalidateOnAnimation()
    }

    private fun updateGame(dt: Float) {
        dayClock += dt
        if (dayClock >= 1.8f) {
            val passed = (dayClock / 1.8f).toInt()
            day += passed
            dayClock -= passed * 1.8f
            checkHistoricalEvents()
        }

        economyClock += dt
        if (economyClock >= 0.75f) {
            val step = economyClock
            economyClock = 0f
            updateEconomy(step)
            if (supplyDirty) rebuildSupply()
        }

        updateDivisions(dt)
        updateBattles(dt)

        aiClock += dt
        if (aiClock >= 0.85f) {
            aiClock = 0f
            for (owner in 0 until EuropeScenario.COUNTRY_COUNT) {
                if (owner == playerCountry) continue
                aiTurn(owner)
            }
        }

        checkVictory()
    }

    private fun updateEconomy(dt: Float) {
        for (owner in nation.indices) {
            val owned = provinces.filter { it.owner == owner }
            if (owned.isEmpty()) continue

            val industry = owned.sumOf { it.industry }
            val infra = owned.sumOf { it.infrastructure }
            val cities = owned.count { it.city }
            val ports = owned.count { it.port }
            val avgInfra = infra.toFloat() / max(1, owned.size)

            val state = nation[owner]
            state.money += dt * (1.5f + industry * 0.80f + cities * 2.1f)
            state.manpower = min(
                350f,
                state.manpower + dt * (0.012f + owned.size * 0.0025f + cities * 0.028f)
            )

            val playerRifle = if (owner == playerCountry) riflePriority else 1f
            val playerTank = if (owner == playerCountry) tankPriority else 0.42f
            val playerAir = if (owner == playerCountry) airPriority else 0.32f

            val infraFactor = 0.70f + avgInfra * 0.08f
            state.equipment += dt * (0.55f + industry * 0.42f * playerRifle) * infraFactor
            state.tanks += dt * (0.06f + industry * 0.045f * playerTank) * infraFactor
            state.aircraft += dt * (0.05f + industry * 0.038f * playerAir) * infraFactor
            state.fuel += dt * (0.4f + ports * 0.85f + avgInfra * 0.16f)
        }
    }

    private fun rebuildSupply() {
        for (owner in nation.indices) {
            val dist = IntArray(provinces.size) { Int.MAX_VALUE }
            val queue: ArrayDeque<Int> = ArrayDeque()

            for (p in provinces) {
                if (p.owner == owner && (p.city || p.port)) {
                    dist[p.id] = 0
                    queue.add(p.id)
                }
            }

            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val current = provinces[id]
                val nextDistance = dist[id] + 1

                for (neighborId in current.neighbors) {
                    val n = provinces[neighborId]
                    if (n.owner != owner) continue
                    if (nextDistance >= dist[neighborId]) continue
                    dist[neighborId] = nextDistance
                    queue.add(neighborId)
                }
            }

            for (p in provinces) {
                if (p.owner != owner) {
                    supplyCache[owner][p.id] = 0.25f
                    continue
                }

                val d = dist[p.id]
                val distanceFactor = when {
                    d <= 2 -> 1f
                    d <= 4 -> 0.90f
                    d <= 7 -> 0.77f
                    d <= 11 -> 0.61f
                    d <= 16 -> 0.46f
                    else -> 0.34f
                }

                val infraFactor = (0.55f + p.infrastructure * 0.09f).coerceIn(0.55f, 1f)
                supplyCache[owner][p.id] = (distanceFactor * infraFactor).coerceIn(0.28f, 1f)
            }
        }

        supplyDirty = false
    }

    private fun supply(owner: Int, provinceId: Int): Float {
        if (owner !in supplyCache.indices) return 0.30f
        if (provinceId !in supplyCache[owner].indices) return 0.30f
        return supplyCache[owner][provinceId]
    }

    private fun updateDivisions(dt: Float) {
        val fighting = mutableSetOf<Int>()
        for (battle in battles) fighting += battle.attackers
        for (battle in battles) {
            fighting += landUnitsAt(battle.targetProvinceId).map { it.id }
        }

        for (division in divisions.toList()) {
            if (division.strength <= 2f) {
                divisions.remove(division)
                continue
            }

            val supplied = supply(division.owner, division.provinceId)

            if (division.type == WarRules.AIR) {
                division.org = min(100f, division.org + dt * 2.8f * supplied)
                continue
            }

            if (division.id !in fighting) {
                division.org = min(100f, division.org + dt * 3.3f * supplied)
                division.entrenchment = min(1f, division.entrenchment + dt * 0.04f * supplied)
                reinforce(division, dt, supplied)
            } else {
                division.entrenchment = max(0f, division.entrenchment - dt * 0.12f)
            }

            if (division.order.isNotEmpty() && division.id !in fighting) {
                division.moveTimer -= dt
                if (division.moveTimer <= 0f) {
                    val nextId = division.order.removeAt(0)
                    val next = provinces[nextId]

                    if (next.owner == division.owner &&
                        nextId in provinces[division.provinceId].neighbors
                    ) {
                        division.provinceId = nextId
                        division.org = max(
                            8f,
                            division.org - if (division.type == WarRules.ARMOR) 1.7f else 1f
                        )
                        division.entrenchment = 0f
                    } else {
                        division.order.clear()
                    }

                    division.moveTimer =
                        if (division.type == WarRules.ARMOR) 0.22f else 0.34f

                    if (division.order.isEmpty() && division.attackTarget >= 0) {
                        if (division.attackTarget in provinces[division.provinceId].neighbors) {
                            joinBattle(
                                division.owner,
                                division.attackTarget,
                                division.id
                            )
                        }
                    }
                }
            }
        }
    }

    private fun reinforce(
        division: StrategicDivision,
        dt: Float,
        supply: Float
    ) {
        if (division.strength >= 100f || supply < 0.42f) return

        val state = nation[division.owner]

        when (division.type) {
            WarRules.INFANTRY -> {
                if (state.equipment < 0.2f || state.manpower < 0.01f) return
                val amount = min(100f - division.strength, dt * 0.7f * supply)
                division.strength += amount
                state.equipment = max(0f, state.equipment - amount * 0.11f)
                state.manpower = max(0f, state.manpower - amount * 0.004f)
            }

            WarRules.ARMOR -> {
                if (state.tanks < 0.04f || state.equipment < 0.08f) return
                val amount = min(100f - division.strength, dt * 0.48f * supply)
                division.strength += amount
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
                val division = divisionById(id)
                division == null ||
                    division.owner != battle.attackerOwner ||
                    division.type == WarRules.AIR ||
                    division.strength <= 4f ||
                    division.org <= 2f ||
                    battle.targetProvinceId !in provinces[division.provinceId].neighbors
            }

            if (battle.attackers.isEmpty()) {
                iterator.remove()
                continue
            }

            val target = provinces[battle.targetProvinceId]
            if (target.owner == battle.attackerOwner) {
                iterator.remove()
                continue
            }

            if (target.owner >= 0 && !atWar(battle.attackerOwner, target.owner)) {
                iterator.remove()
                continue
            }

            val defenders = landUnitsAt(target.id).filter { it.owner == target.owner }

            val attackPower = battle.attackers.sumOf { id ->
                divisionById(id)?.let {
                    combatPower(it, true, target.terrain).toDouble()
                } ?: 0.0
            }.toFloat() * airSupport(battle.attackerOwner, target.id)

            val defensePowerUnits = defenders.sumOf {
                combatPower(it, false, target.terrain).toDouble()
            }.toFloat()

            val provinceDefense = (
                6f +
                    target.fort * 9f +
                    target.infrastructure * 0.8f
                ) * terrainDefense(target.terrain)

            val defensePower = (
                defensePowerUnits + provinceDefense
                ) * if (target.owner >= 0) airSupport(target.owner, target.id) else 1f

            applyLosses(
                battle,
                defenders,
                attackPower,
                defensePower,
                dt
            )

            val ratio = attackPower / max(6f, defensePower)
            val push = when {
                ratio >= 2.2f -> 19f
                ratio >= 1.55f -> 13f
                ratio >= 1.15f -> 7f
                ratio >= 0.88f -> 1.5f
                else -> -5f
            }

            battle.progress = (battle.progress + dt * push).coerceIn(-40f, 100f)

            val broken = battle.attackers.all {
                val d = divisionById(it)
                d == null || d.org < 7f || d.strength < 12f
            }

            if (broken || battle.progress <= -40f) {
                for (id in battle.attackers) {
                    divisionById(id)?.let {
                        it.attackTarget = -1
                        it.org = max(6f, it.org)
                    }
                }
                iterator.remove()
                continue
            }

            if (battle.progress >= 100f ||
                (defenders.isEmpty() && defensePower < 8f && battle.age > 1f)
            ) {
                val oldOwner = target.owner
                target.owner = battle.attackerOwner
                target.fort = max(0, target.fort - 1)
                supplyDirty = true

                for (id in battle.attackers) {
                    divisionById(id)?.let {
                        it.provinceId = target.id
                        it.org = max(12f, it.org - 7f)
                        it.order.clear()
                        it.attackTarget = -1
                        it.entrenchment = 0f
                    }
                }

                if (oldOwner >= 0) {
                    nation[oldOwner].manpower =
                        max(0f, nation[oldOwner].manpower - 0.18f)
                }

                iterator.remove()
            }
        }
    }

    private fun applyLosses(
        battle: StrategicBattle,
        defenders: List<StrategicDivision>,
        attackPower: Float,
        defensePower: Float,
        dt: Float
    ) {
        val attackerCount = max(1, battle.attackers.size)
        val defenderCount = max(1, defenders.size)

        for (id in battle.attackers) {
            val d = divisionById(id) ?: continue
            val supplied = supply(d.owner, d.provinceId)
            d.org = max(
                0f,
                d.org - dt * (1.6f + defensePower / (attackerCount * 34f)) /
                    max(0.42f, supplied)
            )
            d.strength = max(
                0f,
                d.strength - dt * defensePower / (attackerCount * 270f)
            )

            if (d.type == WarRules.ARMOR) {
                nation[d.owner].fuel =
                    max(0f, nation[d.owner].fuel - dt * 1.2f)
            }
        }

        for (d in defenders) {
            val supplied = supply(d.owner, d.provinceId)
            val dugIn = 1f + d.entrenchment * 0.34f

            d.org = max(
                0f,
                d.org - dt * (1.7f + attackPower / (defenderCount * 31f)) /
                    (max(0.40f, supplied) * dugIn)
            )
            d.strength = max(
                0f,
                d.strength - dt * attackPower / (defenderCount * 245f * dugIn)
            )

            if (d.org <= 3f && d.strength > 9f) {
                retreat(d)
            }
        }

        divisions.removeAll { it.strength <= 2f }
    }

    private fun combatPower(
        division: StrategicDivision,
        attacking: Boolean,
        terrain: Int
    ): Float {
        val supplied = supply(division.owner, division.provinceId)
        val state = nation[division.owner]

        val base = when (division.type) {
            WarRules.INFANTRY -> if (attacking) 18f else 21f
            WarRules.ARMOR -> if (attacking) 34f else 25f
            else -> 0f
        }

        val terrainMod = when (division.type) {
            WarRules.INFANTRY -> when (terrain) {
                WarRules.FOREST -> if (attacking) 0.92f else 1.12f
                WarRules.HILLS -> if (attacking) 0.88f else 1.18f
                else -> 1f
            }

            WarRules.ARMOR -> when (terrain) {
                WarRules.FOREST -> 0.66f
                WarRules.HILLS -> 0.60f
                else -> 1.23f
            }

            else -> 1f
        }

        val fuelMod =
            if (division.type == WarRules.ARMOR && state.fuel < 80f) 0.55f else 1f

        val entrenchment =
            if (attacking) 1f else 1f + division.entrenchment * 0.30f

        return base *
            (division.strength / 100f).coerceAtLeast(0.10f) *
            (division.org / 100f).coerceAtLeast(0.10f) *
            supplied.coerceIn(0.30f, 1f) *
            terrainMod *
            fuelMod *
            entrenchment
    }

    private fun terrainDefense(terrain: Int): Float = when (terrain) {
        WarRules.FOREST -> 1.22f
        WarRules.HILLS -> 1.32f
        else -> 1f
    }

    private fun airSupport(owner: Int, provinceId: Int): Float {
        if (owner < 0) return 1f

        var support = 0f
        for (division in divisions) {
            if (division.owner != owner || division.type != WarRules.AIR) continue
            if (division.airMission < 0) continue

            val supports =
                division.airMission == provinceId ||
                    provinceId in provinces[division.airMission].neighbors

            if (!supports || nation[owner].fuel <= 0f) continue

            support +=
                (division.strength / 100f) *
                    (division.org / 100f) *
                    0.14f
            nation[owner].fuel = max(0f, nation[owner].fuel - 0.02f)
        }

        return 1f + min(0.32f, support)
    }

    private fun retreat(division: StrategicDivision) {
        val candidates = provinces[division.provinceId].neighbors
            .map { provinces[it] }
            .filter { it.owner == division.owner }

        val target = candidates.maxByOrNull {
            supply(division.owner, it.id)
        }

        if (target == null) {
            division.strength = 0f
            return
        }

        division.provinceId = target.id
        division.org = 11f
        division.entrenchment = 0f
    }

    private fun aiTurn(owner: Int) {
        val owned = provinces.filter { it.owner == owner }
        if (owned.isEmpty()) return

        aiProduction(owner)

        val activeEnemy = wars[owner].indices.firstOrNull { wars[owner][it] }
        if (activeEnemy == null) {
            // Peacetime: gradually move units toward a national border.
            if (Random.nextFloat() < 0.16f) {
                val idle = divisions.filter {
                    it.owner == owner &&
                        it.type != WarRules.AIR &&
                        it.order.isEmpty() &&
                        it.attackTarget < 0
                }.randomOrNull()

                if (idle != null) {
                    val border = owned.filter { p ->
                        p.neighbors.any { provinces[it].owner != owner }
                    }.minByOrNull {
                        graphDistance(owner, idle.provinceId, it.id)
                    }

                    if (border != null) {
                        findFriendlyPath(owner, idle.provinceId, border.id)?.let {
                            idle.order.clear()
                            idle.order.addAll(it)
                            idle.moveTimer = 0.25f
                        }
                    }
                }
            }
            return
        }

        val airWings = divisions.filter {
            it.owner == owner && it.type == WarRules.AIR
        }

        val battle = battles.firstOrNull {
            it.attackerOwner == owner ||
                provinces[it.targetProvinceId].owner == owner
        }

        if (battle != null) {
            airWings.forEach { it.airMission = battle.targetProvinceId }
        }

        val candidates = divisions.filter {
            it.owner == owner &&
                it.type != WarRules.AIR &&
                it.order.isEmpty() &&
                it.attackTarget < 0 &&
                it.org > 38f &&
                it.strength > 42f &&
                battles.none { b -> it.id in b.attackers }
        }

        val division = candidates.randomOrNull() ?: return
        val current = provinces[division.provinceId]

        val direct = current.neighbors
            .map { provinces[it] }
            .filter {
                it.owner >= 0 &&
                    it.owner != owner &&
                    atWar(owner, it.owner)
            }
            .minByOrNull { targetDefenseScore(it.id) }

        if (direct != null) {
            division.attackTarget = direct.id
            joinBattle(owner, direct.id, division.id)
            return
        }

        val border = owned
            .filter { p ->
                p.neighbors.any {
                    val n = provinces[it]
                    n.owner >= 0 &&
                        n.owner != owner &&
                        atWar(owner, n.owner)
                }
            }
            .minByOrNull {
                graphDistance(owner, division.provinceId, it.id)
            }

        if (border != null) {
            findFriendlyPath(owner, division.provinceId, border.id)?.let {
                division.order.clear()
                division.order.addAll(it)
                division.moveTimer = 0.25f
            }
        }
    }

    private fun aiProduction(owner: Int) {
        val state = nation[owner]
        val owned = provinces.filter { it.owner == owner }
        val capital = owned.firstOrNull { it.city } ?: owned.firstOrNull() ?: return

        val landCount = divisions.count {
            it.owner == owner && it.type != WarRules.AIR
        }

        val desired = max(2, owned.size / 3)

        if (landCount < desired &&
            state.manpower >= 5f &&
            state.equipment >= 105f &&
            Random.nextFloat() < 0.24f
        ) {
            trainDivision(owner, WarRules.INFANTRY, capital.id, false)
            return
        }

        if (owned.size >= 8 &&
            state.manpower >= 4f &&
            state.tanks >= 65f &&
            state.equipment >= 40f &&
            Random.nextFloat() < 0.10f
        ) {
            trainDivision(owner, WarRules.ARMOR, capital.id, false)
            return
        }

        if (divisions.count {
                it.owner == owner && it.type == WarRules.AIR
            } < max(1, owned.size / 12) &&
            state.aircraft >= 42f &&
            state.manpower >= 1f &&
            Random.nextFloat() < 0.08f
        ) {
            trainDivision(owner, WarRules.AIR, capital.id, false)
        }
    }

    private fun targetDefenseScore(provinceId: Int): Float {
        val target = provinces[provinceId]
        val unitStrength = landUnitsAt(provinceId)
            .sumOf { it.strength.toDouble() }
            .toFloat()

        return target.fort * 15f +
            target.infrastructure * 2f +
            unitStrength * 0.15f
    }

    private fun checkHistoricalEvents() {
        if (currentEvent != null) return

        val event = historicalEvents.firstOrNull {
            !it.shown && day >= it.day
        } ?: return

        event.shown = true
        currentEvent = event
        speedIndex = 0

        when (event.day) {
            67 -> {
                nation[EuropeScenario.GERMANY].money += 60f
            }

            199 -> {
                val state = nation[EuropeScenario.SPAIN]
                state.manpower = max(5f, state.manpower * 0.82f)
                state.equipment *= 0.78f
            }

            802 -> {
                transferCountry(
                    EuropeScenario.AUSTRIA,
                    EuropeScenario.GERMANY
                )
            }

            1003 -> {
                val germanBorders = provinces.filter {
                    it.owner == EuropeScenario.CZECHOSLOVAKIA &&
                        it.neighbors.any { n ->
                            provinces[n].owner == EuropeScenario.GERMANY
                        }
                }.take(3)

                germanBorders.forEach {
                    it.owner = EuropeScenario.GERMANY
                }
                supplyDirty = true
            }

            1340 -> {
                declareWar(
                    EuropeScenario.GERMANY,
                    EuropeScenario.POLAND
                )
            }

            1342 -> {
                declareWar(
                    EuropeScenario.GERMANY,
                    EuropeScenario.FRANCE
                )
                declareWar(
                    EuropeScenario.GERMANY,
                    EuropeScenario.UK
                )
            }
        }
    }

    private fun transferCountry(from: Int, to: Int) {
        provinces.filter { it.owner == from }.forEach {
            it.owner = to
        }

        divisions.filter { it.owner == from }.forEach {
            it.strength = 0f
        }
        divisions.removeAll { it.strength <= 0f }
        supplyDirty = true
    }

    private fun checkVictory() {
        if (provinces.none { it.owner == playerCountry } ||
            divisions.none {
                it.owner == playerCountry && it.type != WarRules.AIR
            }
        ) {
            gameOver = "КАМПАНИЯ ПРОИГРАНА"
            speedIndex = 0
            return
        }

        val enemies = wars[playerCountry].indices.filter {
            wars[playerCountry][it] &&
                provinces.any { p -> p.owner == it }
        }

        if (enemies.isNotEmpty() &&
            enemies.all { enemy ->
                provinces.none { it.owner == enemy }
            }
        ) {
            status = "Все текущие противники капитулировали."
            statusTimer = 6f
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(12, 19, 29))

        val top = topBarHeight()
        val bottom = height - bottomPanelHeight()

        drawTopBar(canvas)
        drawMap(canvas, top, bottom)
        drawCountryLabels(canvas, top, bottom)
        drawFrontLines(canvas, top, bottom)
        drawBattles(canvas, top, bottom)
        drawDivisions(canvas, top, bottom)
        drawBottomPanel(canvas, bottom)

        gameOver?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 4, 7, 10)
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)

            text.textAlign = Paint.Align.CENTER
            text.textSize = 38f * settings.uiScale
            text.isFakeBoldText = true
            text.color = Color.WHITE
            canvas.drawText(it, width / 2f, (top + bottom) / 2f, text)
            text.isFakeBoldText = false

            text.textSize = 15f * settings.uiScale
            canvas.drawText(
                "R — перезапустить кампанию",
                width / 2f,
                (top + bottom) / 2f + 32f,
                text
            )
        }
    }

    private fun drawTopBar(canvas: Canvas) {
        val h = topBarHeight()

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(24, 31, 37)
        canvas.drawRect(0f, 0f, width.toFloat(), h, paint)

        paint.color = Color.rgb(59, 68, 73)
        canvas.drawRect(0f, h - 2f, width.toFloat(), h, paint)

        val state = nation[playerCountry]

        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(236, 238, 236)
        text.textSize = 16f * settings.uiScale
        text.isFakeBoldText = true
        canvas.drawText(
            "${EuropeScenario.canonicalTag(playerCountry)}  ${EuropeScenario.canonicalName(playerCountry)}",
            12f,
            22f * settings.uiScale,
            text
        )
        text.isFakeBoldText = false

        text.textSize = 11f * settings.uiScale
        text.color = Color.rgb(183, 195, 202)
        canvas.drawText(
            "ЛС ${String.format("%.1f", state.manpower)}k   " +
                "винтовки ${state.equipment.toInt()}   " +
                "танки ${state.tanks.toInt()}   " +
                "самолёты ${state.aircraft.toInt()}   " +
                "топливо ${state.fuel.toInt()}   " +
                "$${state.money.toInt()}",
            12f,
            43f * settings.uiScale,
            text
        )

        val speedWidth = 45f
        val speedStart = width - speedWidth * 4f - 16f

        text.textAlign = Paint.Align.CENTER
        text.textSize = 12f * settings.uiScale
        text.color = Color.rgb(220, 225, 226)
        canvas.drawText(
            "День $day",
            speedStart - 55f,
            24f * settings.uiScale,
            text
        )

        val speedLabels = arrayOf("Ⅱ", "▶", "▶▶", "▶▶▶")
        for (i in speedLabels.indices) {
            val left = speedStart + i * speedWidth
            paint.color =
                if (speedIndex == i) Color.rgb(70, 86, 93)
                else Color.rgb(35, 44, 49)

            canvas.drawRoundRect(
                left,
                8f,
                left + speedWidth - 4f,
                min(h - 8f, 47f),
                4f,
                4f,
                paint
            )

            text.color =
                if (speedIndex == i) Color.WHITE
                else Color.rgb(163, 175, 181)

            canvas.drawText(
                speedLabels[i],
                left + (speedWidth - 4f) / 2f,
                31f,
                text
            )
        }
    }

    private fun drawMap(
        canvas: Canvas,
        top: Float,
        bottom: Float
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(25, 49, 75)
        canvas.drawRect(0f, top, width.toFloat(), bottom, paint)

        for (province in provinces) {
            val path = provincePath(province, top, bottom)

            paint.style = Paint.Style.FILL
            paint.color = provinceColor(province)
            canvas.drawPath(path, paint)

            if (settings.showTerrainTexture) {
                drawTerrainMark(canvas, province, top, bottom)
            }
        }

        if (settings.showProvinceBorders) {
            for (province in provinces) {
                val path = provincePath(province, top, bottom)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (province.id == selectedProvinceId) 2.2f else 0.75f
                paint.color =
                    if (province.id == selectedProvinceId)
                        Color.rgb(245, 222, 118)
                    else
                        Color.argb(125, 18, 22, 24)

                canvas.drawPath(path, paint)
            }
        }
    }

    private fun provinceColor(province: VectorProvince): Int {
        val base = if (province.owner >= 0) {
            EuropeScenario.canonicalColor(province.owner)
        } else {
            Color.rgb(106, 104, 92)
        }

        val factor = when (province.terrain) {
            WarRules.FOREST -> 0.91f
            WarRules.HILLS -> 0.95f
            else -> 1f
        }

        return Color.rgb(
            (Color.red(base) * factor).toInt().coerceIn(0, 255),
            (Color.green(base) * factor).toInt().coerceIn(0, 255),
            (Color.blue(base) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun drawTerrainMark(
        canvas: Canvas,
        province: VectorProvince,
        top: Float,
        bottom: Float
    ) {
        val c = toScreen(province.center, top, bottom)
        val radius = 3.5f * mapScale.coerceAtMost(1.5f)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        paint.color = Color.argb(70, 20, 28, 24)

        when (province.terrain) {
            WarRules.FOREST -> {
                canvas.drawLine(
                    c.first - radius,
                    c.second + radius,
                    c.first,
                    c.second - radius,
                    paint
                )
                canvas.drawLine(
                    c.first,
                    c.second - radius,
                    c.first + radius,
                    c.second + radius,
                    paint
                )
            }

            WarRules.HILLS -> {
                val p = Path()
                p.moveTo(c.first - radius, c.second + radius / 2f)
                p.quadTo(
                    c.first,
                    c.second - radius,
                    c.first + radius,
                    c.second + radius / 2f
                )
                canvas.drawPath(p, paint)
            }
        }
    }

    private fun drawCountryLabels(
        canvas: Canvas,
        top: Float,
        bottom: Float
    ) {
        if (!settings.showCountryLabels || mapScale > 2.5f) return

        for (owner in 0 until EuropeScenario.COUNTRY_COUNT) {
            val owned = provinces.filter { it.owner == owner }
            if (owned.isEmpty()) continue

            val center = MapPoint(
                owned.map { it.center.x }.average().toFloat(),
                owned.map { it.center.y }.average().toFloat()
            )
            val screen = toScreen(center, top, bottom)

            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            text.textSize = (
                if (owned.size >= 20) 18f else if (owned.size >= 8) 13f else 10f
                ) * settings.uiScale * min(1.25f, mapScale)

            text.color = Color.argb(125, 245, 244, 232)
            canvas.drawText(
                EuropeScenario.canonicalTag(owner),
                screen.first,
                screen.second,
                text
            )
            text.isFakeBoldText = false
        }
    }

    private fun drawFrontLines(
        canvas: Canvas,
        top: Float,
        bottom: Float
    ) {
        paint.style = Paint.Style.STROKE

        for (province in provinces) {
            for (neighborId in province.neighbors) {
                if (neighborId <= province.id) continue

                val neighbor = provinces[neighborId]
                if (province.owner < 0 ||
                    neighbor.owner < 0 ||
                    province.owner == neighbor.owner
                ) continue

                val a = toScreen(province.center, top, bottom)
                val b = toScreen(neighbor.center, top, bottom)

                val activeWar = atWar(province.owner, neighbor.owner)

                paint.strokeWidth = if (activeWar) 3.0f else 1.4f
                paint.color =
                    if (activeWar)
                        Color.argb(210, 223, 163, 91)
                    else
                        Color.argb(135, 30, 34, 36)

                canvas.drawLine(
                    (a.first + b.first) / 2f - (b.second - a.second) * 0.08f,
                    (a.second + b.second) / 2f + (b.first - a.first) * 0.08f,
                    (a.first + b.first) / 2f + (b.second - a.second) * 0.08f,
                    (a.second + b.second) / 2f - (b.first - a.first) * 0.08f,
                    paint
                )
            }
        }
    }

    private fun drawBattles(
        canvas: Canvas,
        top: Float,
        bottom: Float
    ) {
        for (battle in battles) {
            val province = provinces[battle.targetProvinceId]
            val c = toScreen(province.center, top, bottom)

            val width = 40f * settings.uiScale
            val height = 8f * settings.uiScale

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(225, 26, 28, 28)
            canvas.drawRoundRect(
                c.first - width / 2f,
                c.second + 16f,
                c.first + width / 2f,
                c.second + 16f + height,
                2f,
                2f,
                paint
            )

            val normalized =
                ((battle.progress + 40f) / 140f).coerceIn(0f, 1f)

            paint.color = Color.rgb(211, 124, 69)
            canvas.drawRoundRect(
                c.first - width / 2f + 1f,
                c.second + 17f,
                c.first - width / 2f + 1f +
                    (width - 2f) * normalized,
                c.second + 15f + height,
                2f,
                2f,
                paint
            )

            text.textAlign = Paint.Align.CENTER
            text.textSize = 13f * settings.uiScale
            text.color = Color.rgb(255, 235, 205)
            canvas.drawText("⚔", c.first, c.second - 13f, text)
        }
    }

    private fun drawDivisions(
        canvas: Canvas,
        top: Float,
        bottom: Float
    ) {
        val groups = divisions.groupBy { it.provinceId }

        for ((provinceId, stack) in groups) {
            if (stack.isEmpty()) continue

            val center = toScreen(
                provinces[provinceId].center,
                top,
                bottom
            )

            val visible = min(3, stack.size)
            for (i in 0 until visible) {
                drawCounter(
                    canvas,
                    stack[i],
                    center.first + i * 5f,
                    center.second + i * 4f
                )
            }

            if (stack.size > visible) {
                text.textAlign = Paint.Align.RIGHT
                text.textSize = 9f * settings.uiScale
                text.color = Color.WHITE
                canvas.drawText(
                    "+${stack.size - visible}",
                    center.first + 29f,
                    center.second + 28f,
                    text
                )
            }
        }
    }

    private fun drawCounter(
        canvas: Canvas,
        division: StrategicDivision,
        cx: Float,
        cy: Float
    ) {
        val compact = settings.compactCounters
        val w = (if (compact) 37f else 48f) * settings.uiScale
        val h = (if (compact) 21f else 28f) * settings.uiScale
        val left = cx - w / 2f
        val top = cy - h / 2f

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(31, 34, 34)
        canvas.drawRoundRect(
            left,
            top,
            left + w,
            top + h,
            3f,
            3f,
            paint
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (division.selected) 2.4f else 1f
        paint.color =
            if (division.selected)
                Color.rgb(250, 220, 93)
            else
                Color.rgb(217, 217, 203)

        canvas.drawRoundRect(
            left,
            top,
            left + w,
            top + h,
            3f,
            3f,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.color = when (division.type) {
            WarRules.INFANTRY -> Color.rgb(81, 155, 82)
            WarRules.ARMOR -> Color.rgb(196, 126, 57)
            WarRules.AIR -> Color.rgb(71, 156, 200)
            else -> Color.GRAY
        }

        canvas.drawRect(
            left,
            top,
            left + w,
            top + 3f * settings.uiScale,
            paint
        )

        if (settings.showUnitLabels) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = (if (compact) 8.5f else 10.5f) * settings.uiScale
            text.isFakeBoldText = true
            text.color = Color.WHITE
            canvas.drawText(
                unitLabel(division.type),
                cx,
                top + h * 0.54f,
                text
            )
            text.isFakeBoldText = false
        }

        val barLeft = left + 4f
        val barWidth = w - 8f
        val barY = top + h - 7f

        paint.color = Color.rgb(70, 73, 72)
        canvas.drawRect(
            barLeft,
            barY,
            barLeft + barWidth,
            barY + 2f,
            paint
        )
        paint.color = Color.rgb(104, 190, 99)
        canvas.drawRect(
            barLeft,
            barY,
            barLeft + barWidth * (division.strength / 100f),
            barY + 2f,
            paint
        )

        paint.color = Color.rgb(65, 70, 73)
        canvas.drawRect(
            barLeft,
            barY + 3f,
            barLeft + barWidth,
            barY + 5f,
            paint
        )
        paint.color = Color.rgb(74, 146, 211)
        canvas.drawRect(
            barLeft,
            barY + 3f,
            barLeft + barWidth * (division.org / 100f),
            barY + 5f,
            paint
        )
    }

    private fun drawBottomPanel(
        canvas: Canvas,
        top: Float
    ) {
        val panelHeight = height - top

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(23, 29, 33)
        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
            paint
        )

        paint.color = Color.rgb(63, 72, 77)
        canvas.drawRect(0f, top, width.toFloat(), top + 2f, paint)

        val tabs = arrayOf(
            "АРМИЯ",
            "СТРОИТЕЛЬСТВО",
            "ПРОИЗВОДСТВО",
            "ДИПЛОМАТИЯ",
            "СОБЫТИЯ",
            "НАСТРОЙКИ"
        )

        val tabWidth = width / tabs.size.toFloat()
        val tabHeight = 32f

        for (i in tabs.indices) {
            paint.color =
                if (activeTab == i)
                    Color.rgb(63, 77, 83)
                else
                    Color.rgb(34, 42, 46)

            canvas.drawRect(
                i * tabWidth,
                top + 2f,
                (i + 1) * tabWidth - 2f,
                top + tabHeight,
                paint
            )

            text.textAlign = Paint.Align.CENTER
            text.textSize = 10.5f * settings.uiScale
            text.color =
                if (activeTab == i)
                    Color.WHITE
                else
                    Color.rgb(159, 171, 178)

            canvas.drawText(
                tabs[i],
                i * tabWidth + tabWidth / 2f,
                top + 22f,
                text
            )
        }

        val contentTop = top + 38f

        when (activeTab) {
            TAB_ARMY -> drawArmyPanel(canvas, contentTop)
            TAB_CONSTRUCTION -> drawConstructionPanel(canvas, contentTop)
            TAB_PRODUCTION -> drawProductionPanel(canvas, contentTop)
            TAB_DIPLOMACY -> drawDiplomacyPanel(canvas, contentTop)
            TAB_EVENTS -> drawEventsPanel(canvas, contentTop)
            TAB_SETTINGS -> drawSettingsPanel(canvas, contentTop)
        }

        text.textAlign = Paint.Align.LEFT
        text.textSize = 10.5f * settings.uiScale
        text.color =
            if (statusTimer > 0f)
                Color.rgb(211, 220, 224)
            else
                Color.rgb(137, 151, 157)

        canvas.drawText(
            if (statusTimer > 0f)
                status
            else
                "ЛКМ: выбрать / приказ • ПКМ: снять выбор • drag: карта • колесо / pinch: масштаб",
            10f,
            height - 8f,
            text
        )
    }

    private fun drawArmyPanel(
        canvas: Canvas,
        top: Float
    ) {
        val selected = selectedDivisions()

        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * settings.uiScale
        text.color = Color.rgb(222, 228, 230)

        if (selected.isEmpty()) {
            canvas.drawText(
                "Штаб: выбери дивизию на карте. ЛКМ по своей провинции — движение, по вражеской — наступление.",
                10f,
                top + 21f,
                text
            )
        } else {
            val inf = selected.count { it.type == WarRules.INFANTRY }
            val arm = selected.count { it.type == WarRules.ARMOR }
            val air = selected.count { it.type == WarRules.AIR }
            val strength = selected.map { it.strength }.average().toInt()
            val org = selected.map { it.org }.average().toInt()

            canvas.drawText(
                "Выбрано ${selected.size}   INF $inf   ARM $arm   AIR $air   сила $strength%   организация $org%",
                10f,
                top + 21f,
                text
            )
        }

        drawButtons(
            canvas,
            top + 34f,
            arrayOf(
                "СФОРМИРОВАТЬ INF",
                "СФОРМИРОВАТЬ ARM",
                "СФОРМИРОВАТЬ AIR",
                "СНЯТЬ ВЫБОР"
            )
        )
    }

    private fun drawConstructionPanel(
        canvas: Canvas,
        top: Float
    ) {
        val p = selectedProvince()

        text.textAlign = Paint.Align.LEFT
        text.textSize = 11.5f * settings.uiScale
        text.color = Color.rgb(180, 193, 199)

        if (p == null) {
            canvas.drawText(
                "Выбери провинцию своей страны.",
                10f,
                top + 21f,
                text
            )
        } else {
            canvas.drawText(
                "${p.name}   ${ownerName(p.owner)}   " +
                    "инфраструктура ${p.infrastructure}/5   " +
                    "промышленность ${p.industry}/5   " +
                    "форт ${p.fort}/5",
                10f,
                top + 21f,
                text
            )
        }

        drawButtons(
            canvas,
            top + 34f,
            arrayOf(
                "ФОРТ",
                "ИНФРА +",
                "ЗАВОД +",
                "АЭРОДРОМ",
                "ПОРТ",
                "ГОРОД"
            )
        )
    }

    private fun drawProductionPanel(
        canvas: Canvas,
        top: Float
    ) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11.5f * settings.uiScale
        text.color = Color.rgb(184, 197, 203)

        canvas.drawText(
            "Приоритеты производства: винтовки ${String.format("%.2f", riflePriority)}   " +
                "танки ${String.format("%.2f", tankPriority)}   " +
                "авиация ${String.format("%.2f", airPriority)}",
            10f,
            top + 21f,
            text
        )

        drawButtons(
            canvas,
            top + 34f,
            arrayOf(
                "ВИНТОВКИ +",
                "ТАНКИ +",
                "АВИАЦИЯ +",
                "СБРОСИТЬ",
                "ЯДЕРНАЯ ПРОГРАММА"
            )
        )
    }

    private fun drawDiplomacyPanel(
        canvas: Canvas,
        top: Float
    ) {
        val p = selectedProvince()
        val foreign = p?.owner?.takeIf {
            it >= 0 && it != playerCountry
        }

        text.textAlign = Paint.Align.LEFT
        text.textSize = 11.5f * settings.uiScale
        text.color = Color.rgb(184, 197, 203)

        val label = when {
            foreign == null -> "Выбери провинцию другой страны."
            atWar(playerCountry, foreign) ->
                "${EuropeScenario.canonicalName(foreign)} — состояние войны"
            else ->
                "${EuropeScenario.canonicalName(foreign)} — мир"
        }

        canvas.drawText(label, 10f, top + 21f, text)

        drawButtons(
            canvas,
            top + 34f,
            arrayOf(
                if (foreign != null && atWar(playerCountry, foreign))
                    "ПРЕДЛОЖИТЬ ПЕРЕМИРИЕ"
                else
                    "ОБЪЯВИТЬ ВОЙНУ",
                "ЦЕНТР КАРТЫ"
            )
        )
    }

    private fun drawEventsPanel(
        canvas: Canvas,
        top: Float
    ) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11.5f * settings.uiScale
        text.color = Color.rgb(220, 225, 226)
        canvas.drawText(
            "Историческая хроника",
            10f,
            top + 18f,
            text
        )

        text.textSize = 10f * settings.uiScale
        text.color = Color.rgb(156, 170, 177)

        val shown = historicalEvents.filter { it.shown }.takeLast(5)
        if (shown.isEmpty()) {
            canvas.drawText(
                "Пока событий нет.",
                10f,
                top + 40f,
                text
            )
        } else {
            shown.forEachIndexed { index, event ->
                canvas.drawText(
                    "День ${event.day}: ${event.title}",
                    10f,
                    top + 40f + index * 15f,
                    text
                )
            }
        }
    }

    private fun drawSettingsPanel(
        canvas: Canvas,
        top: Float
    ) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 10.8f * settings.uiScale
        text.color = Color.rgb(178, 191, 198)

        canvas.drawText(
            "UI ${String.format("%.1f", settings.uiScale)}   " +
                "границы ${onOff(settings.showProvinceBorders)}   " +
                "рельеф ${onOff(settings.showTerrainTexture)}   " +
                "подписи ${onOff(settings.showCountryLabels)}   " +
                "контры ${if (settings.compactCounters) "компакт" else "обычные"}",
            10f,
            top + 21f,
            text
        )

        drawButtons(
            canvas,
            top + 34f,
            arrayOf(
                "UI −",
                "UI +",
                "ГРАНИЦЫ",
                "РЕЛЬЕФ",
                "ПОДПИСИ",
                "КОНТРЫ",
                if (music.muted) "МУЗЫКА OFF" else "МУЗЫКА ON"
            )
        )
    }

    private fun drawButtons(
        canvas: Canvas,
        top: Float,
        labels: Array<String>
    ) {
        val gap = 5f
        val left = 10f
        val usable = width - 20f
        val buttonWidth =
            (usable - gap * (labels.size - 1)) / labels.size

        for (i in labels.indices) {
            val x1 = left + i * (buttonWidth + gap)
            val x2 = x1 + buttonWidth

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(42, 51, 56)
            canvas.drawRoundRect(
                x1,
                top,
                x2,
                top + 47f,
                5f,
                5f,
                paint
            )

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            paint.color = Color.rgb(78, 90, 95)
            canvas.drawRoundRect(
                x1,
                top,
                x2,
                top + 47f,
                5f,
                5f,
                paint
            )

            text.textAlign = Paint.Align.CENTER
            text.textSize = 9.5f * settings.uiScale
            text.color = Color.rgb(229, 232, 232)
            canvas.drawText(
                labels[i],
                (x1 + x2) / 2f,
                top + 28f,
                text
            )
        }
    }

    private fun drawMainMenu(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 23, 31))

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(22, 31, 41)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val cx = width / 2f
        val titleY = height * 0.26f

        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(238, 237, 228)
        text.textSize = 43f
        text.isFakeBoldText = true
        canvas.drawText("EUROPE 1936", cx, titleY, text)

        text.textSize = 15f
        text.isFakeBoldText = false
        text.color = Color.rgb(159, 175, 184)
        canvas.drawText(
            "оперативная grand strategy",
            cx,
            titleY + 31f,
            text
        )

        val w = min(430f, width * 0.50f)
        val left = cx - w / 2f

        drawMenuButton(
            canvas,
            left,
            titleY + 85f,
            w,
            "НОВАЯ КАМПАНИЯ",
            true
        )
        drawMenuButton(
            canvas,
            left,
            titleY + 151f,
            w,
            if (music.muted) "МУЗЫКА: ВЫКЛ" else "МУЗЫКА: ВКЛ",
            false
        )

        text.textSize = 11f
        text.color = Color.rgb(105, 121, 130)
        canvas.drawText(
            "Vector map engine • v0.7",
            cx,
            height - 24f,
            text
        )
    }

    private fun drawMenuButton(
        canvas: Canvas,
        left: Float,
        top: Float,
        widthPx: Float,
        label: String,
        primary: Boolean
    ) {
        paint.style = Paint.Style.FILL
        paint.color =
            if (primary)
                Color.rgb(76, 93, 100)
            else
                Color.rgb(36, 45, 51)

        canvas.drawRoundRect(
            left,
            top,
            left + widthPx,
            top + 54f,
            7f,
            7f,
            paint
        )

        text.textAlign = Paint.Align.CENTER
        text.textSize = 15f
        text.isFakeBoldText = primary
        text.color = Color.rgb(236, 238, 237)
        canvas.drawText(
            label,
            left + widthPx / 2f,
            top + 34f,
            text
        )
        text.isFakeBoldText = false
    }

    private fun drawCountrySelect(canvas: Canvas) {
        canvas.drawColor(Color.rgb(17, 23, 29))

        text.textAlign = Paint.Align.CENTER
        text.textSize = 26f
        text.isFakeBoldText = true
        text.color = Color.rgb(236, 237, 232)
        canvas.drawText(
            "ВЫБЕРИ СТРАНУ — ЕВРОПА 1936",
            width / 2f,
            38f,
            text
        )
        text.isFakeBoldText = false

        val columns = 6
        val rowsCount = 5
        val gap = 7f
        val side = 11f
        val top = 61f
        val bottom = height - 17f

        val cardWidth =
            (width - side * 2f - gap * (columns - 1)) / columns
        val cardHeight =
            (bottom - top - gap * (rowsCount - 1)) / rowsCount

        for (country in 0 until EuropeScenario.COUNTRY_COUNT) {
            val column = country % columns
            val row = country / columns
            val left = side + column * (cardWidth + gap)
            val y = top + row * (cardHeight + gap)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(29, 37, 42)
            canvas.drawRoundRect(
                left,
                y,
                left + cardWidth,
                y + cardHeight,
                6f,
                6f,
                paint
            )

            paint.color = EuropeScenario.canonicalColor(country)
            canvas.drawRoundRect(
                left + 5f,
                y + 5f,
                left + 39f,
                y + cardHeight - 5f,
                4f,
                4f,
                paint
            )

            text.textAlign = Paint.Align.CENTER
            text.textSize = 10f
            text.isFakeBoldText = true
            text.color = Color.WHITE
            canvas.drawText(
                EuropeScenario.canonicalTag(country),
                left + 22f,
                y + cardHeight * 0.60f,
                text
            )

            text.textAlign = Paint.Align.LEFT
            text.textSize = 10.5f
            text.isFakeBoldText = false
            text.color = Color.rgb(225, 230, 231)
            canvas.drawText(
                EuropeScenario.canonicalName(country),
                left + 44f,
                y + cardHeight * 0.47f,
                text
            )

            text.textSize = 8.8f
            text.color = Color.rgb(135, 151, 159)
            canvas.drawText(
                "начать кампанию",
                left + 44f,
                y + cardHeight * 0.72f,
                text
            )
        }
    }

    private fun drawEventOverlay(canvas: Canvas) {
        val event = currentEvent ?: return

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 5, 8, 11)
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            paint
        )

        val w = min(width * 0.72f, 720f)
        val h = 225f
        val left = width / 2f - w / 2f
        val top = height / 2f - h / 2f

        paint.color = Color.rgb(33, 41, 46)
        canvas.drawRoundRect(
            left,
            top,
            left + w,
            top + h,
            9f,
            9f,
            paint
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.rgb(102, 112, 116)
        canvas.drawRoundRect(
            left,
            top,
            left + w,
            top + h,
            9f,
            9f,
            paint
        )

        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(239, 236, 222)
        text.textSize = 20f * settings.uiScale
        text.isFakeBoldText = true
        canvas.drawText(
            event.title,
            width / 2f,
            top + 43f,
            text
        )
        text.isFakeBoldText = false

        text.textSize = 11.5f * settings.uiScale
        text.color = Color.rgb(190, 200, 204)
        canvas.drawText(
            event.body,
            width / 2f,
            top + 80f,
            text
        )
        canvas.drawText(
            event.effect,
            width / 2f,
            top + 108f,
            text
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(65, 80, 86)
        canvas.drawRoundRect(
            left + w * 0.31f,
            top + 157f,
            left + w * 0.69f,
            top + 201f,
            6f,
            6f,
            paint
        )

        text.textSize = 12.5f * settings.uiScale
        text.color = Color.WHITE
        canvas.drawText(
            "ПРОДОЛЖИТЬ",
            width / 2f,
            top + 184f,
            text
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        scaleDetector.onTouchEvent(event)

        if (screen == SCREEN_MENU) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                handleMainMenuTap(event.x, event.y)
            }
            return true
        }

        if (screen == SCREEN_COUNTRIES) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                handleCountryTap(event.x, event.y)
            }
            return true
        }

        if (currentEvent != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                currentEvent = null
                speedIndex = 1
                invalidate()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                draggingMap = false
                downSecondary =
                    (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                        event.actionButton == MotionEvent.BUTTON_SECONDARY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || scaleDetector.isInProgress) {
                    return true
                }

                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                if (!draggingMap) {
                    val totalDx = event.x - downX
                    val totalDy = event.y - downY
                    if (sqrt(totalDx * totalDx + totalDy * totalDy) > 9f) {
                        draggingMap = true
                    }
                }

                if (draggingMap && event.y in topBarHeight()..(height - bottomPanelHeight())) {
                    mapPanX += dx
                    mapPanY += dy
                    clampPan()
                }

                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!draggingMap) {
                    handleGameTap(
                        event.x,
                        event.y,
                        downSecondary
                    )
                }
                draggingMap = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                draggingMap = false
                return true
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    handleGameTap(event.x, event.y, true)
                    return true
                }
            }
        }

        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (screen == SCREEN_GAME &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val old = mapScale
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            mapScale = (
                mapScale * if (delta > 0f) 1.12f else 0.89f
                ).coerceIn(0.90f, 3.6f)

            if (abs(old - mapScale) > 0.001f) {
                clampPan()
                invalidate()
            }
            return true
        }

        return super.onGenericMotionEvent(event)
    }

    private fun handleGameTap(
        x: Float,
        y: Float,
        secondary: Boolean
    ) {
        val top = topBarHeight()
        val bottom = height - bottomPanelHeight()

        if (y < top) {
            handleTopBarTap(x)
            return
        }

        if (y > bottom) {
            handleBottomPanelTap(x, y, bottom)
            return
        }

        val provinceId = provinceAtScreen(x, y)
        if (provinceId < 0) return

        selectedProvinceId = provinceId

        if (secondary) {
            val selectedHere = divisions.filter {
                it.owner == playerCountry &&
                    it.provinceId == provinceId &&
                    it.selected
            }

            if (selectedHere.isNotEmpty()) {
                selectedHere.forEach { it.selected = false }
                flash("Снято с выбора: ${selectedHere.size}")
            } else {
                clearSelection()
                flash("Выделение снято")
            }
            return
        }

        handleProvinceLeftClick(provinceId)
    }

    private fun handleProvinceLeftClick(provinceId: Int) {
        val province = provinces[provinceId]
        val selected = selectedDivisions()

        if (selected.isEmpty()) {
            val ownStack = divisions.filter {
                it.owner == playerCountry &&
                    it.provinceId == provinceId
            }

            if (ownStack.isNotEmpty()) {
                clearSelection()
                ownStack.forEach { it.selected = true }
                flash("Выбрано дивизий: ${ownStack.size}")
            } else {
                flash("${province.name}: ${ownerName(province.owner)}")
            }
            return
        }

        val air = selected.filter { it.type == WarRules.AIR }
        val land = selected.filter { it.type != WarRules.AIR }

        if (air.isNotEmpty()) {
            air.forEach {
                it.airMission = provinceId
            }
            flash("Авиация получила район боевой работы")
        }

        if (land.isEmpty()) return

        if (province.owner == playerCountry) {
            issueMove(land, provinceId)
        } else {
            issueAttack(land, provinceId)
        }
    }

    private fun issueMove(
        units: List<StrategicDivision>,
        targetProvinceId: Int
    ) {
        var ordered = 0

        for (unit in units) {
            val path = findFriendlyPath(
                playerCountry,
                unit.provinceId,
                targetProvinceId
            ) ?: continue

            unit.order.clear()
            unit.order.addAll(path)
            unit.attackTarget = -1
            unit.moveTimer =
                if (unit.type == WarRules.ARMOR) 0.18f else 0.30f
            ordered++
        }

        flash(
            if (ordered > 0)
                "Приказ на перемещение: $ordered дивизий"
            else
                "Нет маршрута по контролируемой территории"
        )
    }

    private fun issueAttack(
        units: List<StrategicDivision>,
        targetProvinceId: Int
    ) {
        val target = provinces[targetProvinceId]

        if (target.owner >= 0 &&
            target.owner != playerCountry &&
            !atWar(playerCountry, target.owner)
        ) {
            declareWar(playerCountry, target.owner)
        }

        var ordered = 0

        for (unit in units) {
            unit.order.clear()
            unit.attackTarget = targetProvinceId

            if (targetProvinceId in provinces[unit.provinceId].neighbors) {
                joinBattle(
                    playerCountry,
                    targetProvinceId,
                    unit.id
                )
                ordered++
                continue
            }

            val staging = target.neighbors
                .map { provinces[it] }
                .filter { it.owner == playerCountry }
                .mapNotNull { p ->
                    findFriendlyPath(
                        playerCountry,
                        unit.provinceId,
                        p.id
                    )?.let { path -> p.id to path }
                }
                .minByOrNull { it.second.size }

            if (staging != null) {
                unit.order.addAll(staging.second)
                unit.moveTimer =
                    if (unit.type == WarRules.ARMOR) 0.18f else 0.30f
                ordered++
            } else {
                unit.attackTarget = -1
            }
        }

        flash(
            if (ordered > 0)
                "Наступление подготовлено: $ordered дивизий"
            else
                "Фронт не соприкасается с целью"
        )
    }

    private fun handleTopBarTap(x: Float) {
        val speedWidth = 45f
        val speedStart = width - speedWidth * 4f - 16f
        if (x < speedStart) return

        val index = ((x - speedStart) / speedWidth).toInt()
        if (index in speeds.indices) {
            speedIndex = index
            flash(if (index == 0) "Пауза" else "Скорость x${speeds[index].toInt()}")
        }
    }

    private fun handleBottomPanelTap(
        x: Float,
        y: Float,
        panelTop: Float
    ) {
        val tabs = 6
        val tabHeight = 32f

        if (y <= panelTop + tabHeight + 3f) {
            activeTab =
                (x / (width / tabs.toFloat())).toInt()
                    .coerceIn(0, tabs - 1)
            return
        }

        val buttonTop = panelTop + 72f
        if (y !in buttonTop..(buttonTop + 53f)) return

        val count = when (activeTab) {
            TAB_ARMY -> 4
            TAB_CONSTRUCTION -> 6
            TAB_PRODUCTION -> 5
            TAB_DIPLOMACY -> 2
            TAB_SETTINGS -> 7
            else -> 0
        }

        if (count == 0) return

        val gap = 5f
        val left = 10f
        val usable = width - 20f
        val buttonWidth =
            (usable - gap * (count - 1)) / count

        val index =
            ((x - left) / (buttonWidth + gap)).toInt()
                .coerceIn(0, count - 1)

        when (activeTab) {
            TAB_ARMY -> when (index) {
                0 -> playerTrain(WarRules.INFANTRY)
                1 -> playerTrain(WarRules.ARMOR)
                2 -> playerTrain(WarRules.AIR)
                3 -> {
                    clearSelection()
                    flash("Выделение снято")
                }
            }

            TAB_CONSTRUCTION -> when (index) {
                0 -> buildFort()
                1 -> upgradeInfrastructure()
                2 -> upgradeIndustry()
                3 -> buildAirfield()
                4 -> buildPort()
                5 -> buildCity()
            }

            TAB_PRODUCTION -> when (index) {
                0 -> {
                    riflePriority =
                        (riflePriority + 0.10f).coerceAtMost(2f)
                    flash("Приоритет винтовок: ${String.format("%.2f", riflePriority)}")
                }
                1 -> {
                    tankPriority =
                        (tankPriority + 0.10f).coerceAtMost(2f)
                    flash("Приоритет танков: ${String.format("%.2f", tankPriority)}")
                }
                2 -> {
                    airPriority =
                        (airPriority + 0.10f).coerceAtMost(2f)
                    flash("Приоритет авиации: ${String.format("%.2f", airPriority)}")
                }
                3 -> {
                    riflePriority = 1f
                    tankPriority = 0.45f
                    airPriority = 0.35f
                    flash("Приоритеты производства сброшены")
                }
                4 -> nuclearProgram()
            }

            TAB_DIPLOMACY -> when (index) {
                0 -> diplomacyAction()
                1 -> {
                    mapScale = 1f
                    mapPanX = 0f
                    mapPanY = 0f
                }
            }

            TAB_SETTINGS -> when (index) {
                0 -> settings.uiScale =
                    max(0.8f, settings.uiScale - 0.1f)
                1 -> settings.uiScale =
                    min(1.3f, settings.uiScale + 0.1f)
                2 -> settings.showProvinceBorders =
                    !settings.showProvinceBorders
                3 -> settings.showTerrainTexture =
                    !settings.showTerrainTexture
                4 -> settings.showCountryLabels =
                    !settings.showCountryLabels
                5 -> settings.compactCounters =
                    !settings.compactCounters
                6 -> music.toggle()
            }
        }
    }

    private fun playerTrain(type: Int) {
        val spawn = selectedProvince()
            ?.takeIf { it.owner == playerCountry && it.city }
            ?: provinces.firstOrNull {
                it.owner == playerCountry && it.city
            }
            ?: provinces.firstOrNull { it.owner == playerCountry }
            ?: return

        if (trainDivision(playerCountry, type, spawn.id, true)) {
            flash("Сформирована часть: ${unitLabel(type)}")
        }
    }

    private fun trainDivision(
        owner: Int,
        type: Int,
        provinceId: Int,
        showMessage: Boolean
    ): Boolean {
        val state = nation[owner]

        when (type) {
            WarRules.INFANTRY -> {
                if (state.manpower < 6f ||
                    state.equipment < 120f
                ) {
                    if (showMessage) {
                        flash("Нужно 6k ЛС и 120 винтовок")
                    }
                    return false
                }

                state.manpower -= 6f
                state.equipment -= 120f
            }

            WarRules.ARMOR -> {
                if (state.manpower < 4f ||
                    state.tanks < 70f ||
                    state.equipment < 45f
                ) {
                    if (showMessage) {
                        flash("Нужно 4k ЛС, 70 танков и 45 снаряжения")
                    }
                    return false
                }

                state.manpower -= 4f
                state.tanks -= 70f
                state.equipment -= 45f
            }

            WarRules.AIR -> {
                if (state.manpower < 1f ||
                    state.aircraft < 45f
                ) {
                    if (showMessage) {
                        flash("Нужно 1k ЛС и 45 самолётов")
                    }
                    return false
                }

                state.manpower -= 1f
                state.aircraft -= 45f
            }
        }

        addDivision(owner, type, provinceId)
        divisions.last().org = 70f
        divisions.last().strength = 90f
        return true
    }

    private fun buildFort() {
        val p = selectedOwnedProvince() ?: return
        if (p.fort >= 5) {
            flash("Форт уже максимального уровня")
            return
        }
        if (!spendMoney(150f)) return

        p.fort++
        flash("Форт: ${p.fort}/5")
    }

    private fun upgradeInfrastructure() {
        val p = selectedOwnedProvince() ?: return
        if (p.infrastructure >= 5) {
            flash("Инфраструктура уже максимальная")
            return
        }
        if (!spendMoney(120f)) return

        p.infrastructure++
        supplyDirty = true
        flash("Инфраструктура: ${p.infrastructure}/5")
    }

    private fun upgradeIndustry() {
        val p = selectedOwnedProvince() ?: return
        if (p.industry >= 5) {
            flash("Промышленность уже максимальная")
            return
        }
        if (!spendMoney(170f)) return

        p.industry++
        flash("Промышленность: ${p.industry}/5")
    }

    private fun buildAirfield() {
        val p = selectedOwnedProvince() ?: return
        if (p.airfield) {
            flash("Аэродром уже построен")
            return
        }
        if (!spendMoney(135f)) return

        p.airfield = true
        flash("Аэродром построен")
    }

    private fun buildPort() {
        val p = selectedOwnedProvince() ?: return
        if (p.port) {
            flash("Порт уже построен")
            return
        }
        if (!spendMoney(180f)) return

        p.port = true
        supplyDirty = true
        flash("Порт построен")
    }

    private fun buildCity() {
        val p = selectedOwnedProvince() ?: return
        if (p.city) {
            flash("Город уже есть")
            return
        }
        if (!spendMoney(230f)) return

        p.city = true
        p.industry = max(p.industry, 2)
        supplyDirty = true
        flash("Город построен")
    }

    private fun nuclearProgram() {
        val state = nation[playerCountry]

        if (state.nukes > 0) {
            val target = selectedProvince()
            if (target == null ||
                target.owner < 0 ||
                target.owner == playerCountry
            ) {
                flash("Выбери вражескую провинцию для ядерного удара")
                return
            }

            state.nukes--
            nuclearStrike(target.id)
            return
        }

        if (state.money < 520f) {
            flash("Для программы нужно $520")
            return
        }

        state.money -= 520f
        state.nukes++
        flash("Боеголовка готова. Выбери вражескую провинцию и нажми кнопку снова.")
    }

    private fun nuclearStrike(provinceId: Int) {
        val target = provinces[provinceId]

        val affected = mutableSetOf<Int>()
        affected += provinceId
        affected += target.neighbors

        for (id in affected) {
            val p = provinces[id]
            p.fort = max(0, p.fort - 3)
            p.industry = max(0, p.industry - 2)
            p.infrastructure = max(1, p.infrastructure - 2)

            divisions.filter { it.provinceId == id }.forEach {
                it.strength *= if (id == provinceId) 0.22f else 0.48f
                it.org *= 0.18f
            }
        }

        target.owner = -1
        divisions.removeAll { it.strength <= 2f }
        supplyDirty = true
        flash("Ядерный удар нанесён")
    }

    private fun diplomacyAction() {
        val target = selectedProvince()
            ?.owner
            ?.takeIf { it >= 0 && it != playerCountry }

        if (target == null) {
            flash("Выбери провинцию другой страны")
            return
        }

        if (atWar(playerCountry, target)) {
            wars[playerCountry][target] = false
            wars[target][playerCountry] = false
            battles.removeAll {
                it.attackerOwner == playerCountry &&
                    provinces[it.targetProvinceId].owner == target ||
                    it.attackerOwner == target &&
                    provinces[it.targetProvinceId].owner == playerCountry
            }
            flash("Перемирие с ${EuropeScenario.canonicalName(target)}")
        } else {
            declareWar(playerCountry, target)
            flash("Объявлена война: ${EuropeScenario.canonicalName(target)}")
        }
    }

    private fun spendMoney(amount: Float): Boolean {
        val state = nation[playerCountry]
        if (state.money < amount) {
            flash("Недостаточно денег: нужно $${amount.toInt()}")
            return false
        }
        state.money -= amount
        return true
    }

    private fun selectedOwnedProvince(): VectorProvince? {
        val p = selectedProvince()
        if (p == null || p.owner != playerCountry) {
            flash("Выбери свою провинцию")
            return null
        }
        return p
    }

    private fun selectedProvince(): VectorProvince? =
        provinces.getOrNull(selectedProvinceId)

    private fun joinBattle(
        owner: Int,
        targetProvinceId: Int,
        divisionId: Int
    ) {
        val target = provinces[targetProvinceId]

        if (target.owner == owner) return
        if (target.owner >= 0 &&
            !atWar(owner, target.owner)
        ) return

        var battle = battles.firstOrNull {
            it.targetProvinceId == targetProvinceId
        }

        if (battle != null &&
            battle.attackerOwner != owner
        ) {
            return
        }

        if (battle == null) {
            battle = StrategicBattle(
                attackerOwner = owner,
                targetProvinceId = targetProvinceId
            )
            battles += battle
        }

        if (divisionId !in battle.attackers) {
            battle.attackers += divisionId
        }
    }

    private fun findFriendlyPath(
        owner: Int,
        startId: Int,
        targetId: Int
    ): List<Int>? {
        if (startId == targetId) return emptyList()
        if (provinces[targetId].owner != owner) return null

        val previous = IntArray(provinces.size) { -1 }
        val seen = BooleanArray(provinces.size)
        val queue: ArrayDeque<Int> = ArrayDeque()

        seen[startId] = true
        queue.add(startId)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()

            for (neighborId in provinces[id].neighbors) {
                if (seen[neighborId]) continue
                if (provinces[neighborId].owner != owner) continue

                seen[neighborId] = true
                previous[neighborId] = id

                if (neighborId == targetId) {
                    val result = mutableListOf<Int>()
                    var current = targetId

                    while (current != startId) {
                        result += current
                        current = previous[current]
                    }

                    result.reverse()
                    return result
                }

                queue.add(neighborId)
            }
        }

        return null
    }

    private fun graphDistance(
        owner: Int,
        startId: Int,
        targetId: Int
    ): Int {
        if (startId == targetId) return 0

        val distance = IntArray(provinces.size) { -1 }
        val queue: ArrayDeque<Int> = ArrayDeque()
        distance[startId] = 0
        queue.add(startId)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val nextDistance = distance[id] + 1

            for (neighborId in provinces[id].neighbors) {
                if (distance[neighborId] >= 0) continue
                if (provinces[neighborId].owner != owner) continue

                distance[neighborId] = nextDistance
                if (neighborId == targetId) return nextDistance
                queue.add(neighborId)
            }
        }

        return 999
    }

    private fun landUnitsAt(provinceId: Int): List<StrategicDivision> =
        divisions.filter {
            it.provinceId == provinceId &&
                it.type != WarRules.AIR
        }

    private fun divisionById(id: Int): StrategicDivision? =
        divisions.firstOrNull { it.id == id }

    private fun selectedDivisions(): List<StrategicDivision> =
        divisions.filter {
            it.owner == playerCountry && it.selected
        }

    private fun clearSelection() {
        divisions.forEach { it.selected = false }
    }

    private fun atWar(a: Int, b: Int): Boolean {
        if (a !in wars.indices ||
            b !in wars.indices
        ) return false

        return wars[a][b]
    }

    private fun declareWar(a: Int, b: Int) {
        if (a !in wars.indices ||
            b !in wars.indices ||
            a == b
        ) return

        wars[a][b] = true
        wars[b][a] = true
    }

    private fun ownerName(owner: Int): String = when {
        owner < 0 -> "нейтральная территория"
        owner == playerCountry ->
            "Вы — ${EuropeScenario.canonicalName(owner)}"
        else ->
            EuropeScenario.canonicalName(owner)
    }

    private fun unitLabel(type: Int): String = when (type) {
        WarRules.INFANTRY -> "INF"
        WarRules.ARMOR -> "ARM"
        WarRules.AIR -> "AIR"
        else -> "?"
    }

    private fun flash(message: String) {
        status = message
        statusTimer = 4f
    }

    private fun onOff(value: Boolean): String =
        if (value) "вкл" else "выкл"

    private fun topBarHeight(): Float =
        58f * settings.uiScale.coerceIn(0.85f, 1.20f)

    private fun bottomPanelHeight(): Float =
        174f * settings.uiScale.coerceIn(0.85f, 1.18f)

    private fun provincePath(
        province: VectorProvince,
        top: Float,
        bottom: Float
    ): Path {
        val path = Path()

        province.polygon.forEachIndexed { index, point ->
            val screen = toScreen(point, top, bottom)

            if (index == 0) {
                path.moveTo(screen.first, screen.second)
            } else {
                path.lineTo(screen.first, screen.second)
            }
        }

        path.close()
        return path
    }

    private fun toScreen(
        point: MapPoint,
        top: Float,
        bottom: Float
    ): Pair<Float, Float> {
        val mapWidth = width.toFloat()
        val mapHeight = bottom - top

        val x =
            mapWidth / 2f +
                (point.x - 0.5f) * mapWidth * mapScale +
                mapPanX

        val y =
            top +
                mapHeight / 2f +
                (point.y - 0.5f) * mapHeight * mapScale +
                mapPanY

        return x to y
    }

    private fun fromScreen(
        x: Float,
        y: Float,
        top: Float,
        bottom: Float
    ): MapPoint {
        val mapWidth = width.toFloat()
        val mapHeight = bottom - top

        val nx =
            0.5f +
                (x - mapWidth / 2f - mapPanX) /
                (mapWidth * mapScale)

        val ny =
            0.5f +
                (y - top - mapHeight / 2f - mapPanY) /
                (mapHeight * mapScale)

        return MapPoint(nx, ny)
    }

    private fun provinceAtScreen(
        x: Float,
        y: Float
    ): Int {
        val top = topBarHeight()
        val bottom = height - bottomPanelHeight()
        val point = fromScreen(x, y, top, bottom)

        for (province in provinces.asReversed()) {
            if (pointInPolygon(point, province.polygon)) {
                return province.id
            }
        }

        return -1
    }

    private fun pointInPolygon(
        point: MapPoint,
        polygon: List<MapPoint>
    ): Boolean {
        var inside = false
        var j = polygon.lastIndex

        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]

            val denominator =
                if (abs(pj.y - pi.y) < 0.000001f)
                    0.000001f
                else
                    pj.y - pi.y

            val intersects =
                ((pi.y > point.y) != (pj.y > point.y)) &&
                    (
                        point.x <
                            (pj.x - pi.x) *
                            (point.y - pi.y) /
                            denominator +
                            pi.x
                        )

            if (intersects) inside = !inside
            j = i
        }

        return inside
    }

    private fun clampPan() {
        val top = topBarHeight()
        val bottom = height - bottomPanelHeight()
        val extraX = width * (mapScale - 1f) / 2f + width * 0.16f
        val extraY = (bottom - top) * (mapScale - 1f) / 2f + (bottom - top) * 0.16f

        mapPanX = mapPanX.coerceIn(-extraX, extraX)
        mapPanY = mapPanY.coerceIn(-extraY, extraY)
    }

    private fun handleMainMenuTap(x: Float, y: Float) {
        val cx = width / 2f
        val titleY = height * 0.26f
        val w = min(430f, width * 0.50f)
        val left = cx - w / 2f

        if (x in left..(left + w) &&
            y in (titleY + 85f)..(titleY + 139f)
        ) {
            screen = SCREEN_COUNTRIES
            invalidate()
            return
        }

        if (x in left..(left + w) &&
            y in (titleY + 151f)..(titleY + 205f)
        ) {
            music.toggle()
            invalidate()
        }
    }

    private fun handleCountryTap(x: Float, y: Float) {
        val columns = 6
        val rowsCount = 5
        val gap = 7f
        val side = 11f
        val top = 61f
        val bottom = height - 17f

        val cardWidth =
            (width - side * 2f - gap * (columns - 1)) / columns
        val cardHeight =
            (bottom - top - gap * (rowsCount - 1)) / rowsCount

        for (country in 0 until EuropeScenario.COUNTRY_COUNT) {
            val column = country % columns
            val row = country / columns
            val left = side + column * (cardWidth + gap)
            val cardTop = top + row * (cardHeight + gap)

            if (x in left..(left + cardWidth) &&
                y in cardTop..(cardTop + cardHeight)
            ) {
                resetCampaign(country)
                screen = SCREEN_GAME
                return
            }
        }
    }

    override fun onKeyDown(
        code: Int,
        event: KeyEvent
    ): Boolean {
        when (screen) {
            SCREEN_MENU -> {
                when (code) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE -> {
                        screen = SCREEN_COUNTRIES
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_M -> {
                        music.toggle()
                        return true
                    }
                }
                return super.onKeyDown(code, event)
            }

            SCREEN_COUNTRIES -> {
                if (code == KeyEvent.KEYCODE_ESCAPE ||
                    code == KeyEvent.KEYCODE_BACK
                ) {
                    screen = SCREEN_MENU
                    invalidate()
                    return true
                }
                return super.onKeyDown(code, event)
            }
        }

        when (code) {
            KeyEvent.KEYCODE_0 -> speedIndex = 0
            KeyEvent.KEYCODE_1 -> speedIndex = 1
            KeyEvent.KEYCODE_2 -> speedIndex = 2
            KeyEvent.KEYCODE_3 -> speedIndex = 3

            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_EQUALS -> {
                mapScale = (mapScale * 1.12f).coerceAtMost(3.6f)
                clampPan()
            }

            KeyEvent.KEYCODE_MINUS -> {
                mapScale = (mapScale * 0.89f).coerceAtLeast(0.90f)
                clampPan()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                mapPanX += 35f
                clampPan()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                mapPanX -= 35f
                clampPan()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                mapPanY += 35f
                clampPan()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mapPanY -= 35f
                clampPan()
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                clearSelection()
                flash("Выделение снято")
            }

            KeyEvent.KEYCODE_BACK -> {
                speedIndex = 0
                screen = SCREEN_MENU
                clearSelection()
            }

            KeyEvent.KEYCODE_M -> music.toggle()

            KeyEvent.KEYCODE_R -> {
                resetCampaign(playerCountry)
                screen = SCREEN_GAME
            }

            KeyEvent.KEYCODE_F -> buildFort()

            else -> return super.onKeyDown(code, event)
        }

        return true
    }
}
