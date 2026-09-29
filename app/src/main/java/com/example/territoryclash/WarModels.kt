package com.example.territoryclash

object WarRules {
    const val WATER = 0
    const val PLAINS = 1
    const val FOREST = 2
    const val HILLS = 3

    const val NEUTRAL = -1
    const val WATER_OWNER = -2

    const val INFANTRY = 0
    const val ARMOR = 1
    const val AIR = 2

    const val COST_FORT = 150
    const val COST_CITY = 220
    const val COST_PORT = 180
    const val COST_SILO = 360
    const val COST_NUKE = 520
}

data class Province(
    var terrain: Int = WarRules.PLAINS,
    var owner: Int = WarRules.NEUTRAL,
    var garrison: Float = 8f,
    var fort: Int = 0,
    var city: Boolean = false,
    var port: Boolean = false,
    var silo: Boolean = false
)

data class Division(
    val id: Int,
    val owner: Int,
    val type: Int,
    var x: Int,
    var y: Int,
    var strength: Float = 100f,
    var org: Float = 100f,
    var entrenchment: Float = 0f,
    var selected: Boolean = false,
    var moveTimer: Float = 0f,
    val order: MutableList<Pair<Int, Int>> = mutableListOf(),
    var attackX: Int = -1,
    var attackY: Int = -1,
    var missionX: Int = -1,
    var missionY: Int = -1
)

data class NationState(
    var manpower: Float = 90f,
    var equipment: Float = 1500f,
    var tanks: Float = 260f,
    var aircraft: Float = 170f,
    var fuel: Float = 1050f,
    var money: Float = 620f,
    var nukes: Int = 0
)

data class Battle(
    val attackerOwner: Int,
    val targetX: Int,
    val targetY: Int,
    val attackers: MutableList<Int> = mutableListOf(),
    var progress: Float = 0f,
    var age: Float = 0f
)
