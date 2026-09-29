package com.example.territoryclash

data class StrategicDivision(
    val id: Int,
    val owner: Int,
    val type: Int,
    var provinceId: Int,
    var strength: Float = 100f,
    var org: Float = 100f,
    var entrenchment: Float = 0f,
    var selected: Boolean = false,
    var moveTimer: Float = 0f,
    val order: MutableList<Int> = mutableListOf(),
    var attackTarget: Int = -1,
    var airMission: Int = -1
)

data class StrategicBattle(
    val attackerOwner: Int,
    val targetProvinceId: Int,
    val attackers: MutableList<Int> = mutableListOf(),
    var progress: Float = 0f,
    var age: Float = 0f
)
