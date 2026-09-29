package com.example.territoryclash

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object EuropeScenario {
    const val COLS = 64
    const val ROWS = 36

    const val GERMANY = 0
    const val FRANCE = 1
    const val POLAND = 2
    const val ITALY = 3
    const val USSR = 4
    const val UK = 5
    const val SPAIN = 6
    const val PORTUGAL = 7
    const val BELGIUM = 8
    const val NETHERLANDS = 9
    const val LUXEMBOURG = 10
    const val SWITZERLAND = 11
    const val AUSTRIA = 12
    const val CZECHOSLOVAKIA = 13
    const val HUNGARY = 14
    const val ROMANIA = 15
    const val YUGOSLAVIA = 16
    const val GREECE = 17
    const val BULGARIA = 18
    const val ALBANIA = 19
    const val DENMARK = 20
    const val NORWAY = 21
    const val SWEDEN = 22
    const val FINLAND = 23
    const val ESTONIA = 24
    const val LATVIA = 25
    const val LITHUANIA = 26
    const val IRELAND = 27
    const val TURKEY = 28

    const val COUNTRY_COUNT = 29

    val selectableCountries = intArrayOf(
        GERMANY, FRANCE, POLAND, ITALY, USSR, UK,
        SPAIN, CZECHOSLOVAKIA, ROMANIA, FINLAND,
        SWEDEN, YUGOSLAVIA
    )

    data class Setup(
        val starts: List<Pair<Int, Int>>,
        val names: Array<String>,
        val tags: Array<String>,
        val colors: IntArray
    )

    private val canonicalNames = arrayOf(
        "Германия", "Франция", "Польша", "Италия", "СССР", "Великобритания",
        "Испания", "Португалия", "Бельгия", "Нидерланды", "Люксембург",
        "Швейцария", "Австрия", "Чехословакия", "Венгрия", "Румыния",
        "Югославия", "Греция", "Болгария", "Албания", "Дания",
        "Норвегия", "Швеция", "Финляндия", "Эстония", "Латвия",
        "Литва", "Ирландия", "Турция"
    )

    private val canonicalTags = arrayOf(
        "GER", "FRA", "POL", "ITA", "USSR", "UK",
        "SPA", "POR", "BEL", "HOL", "LUX",
        "SWI", "AUS", "CZE", "HUN", "ROM",
        "YUG", "GRE", "BUL", "ALB", "DEN",
        "NOR", "SWE", "FIN", "EST", "LAT",
        "LIT", "IRE", "TUR"
    )

    private val canonicalColors = intArrayOf(
        Color.rgb(103, 105, 108),
        Color.rgb(72, 108, 165),
        Color.rgb(181, 139, 84),
        Color.rgb(86, 145, 86),
        Color.rgb(169, 75, 71),
        Color.rgb(169, 132, 78),
        Color.rgb(196, 152, 85),
        Color.rgb(105, 155, 106),
        Color.rgb(126, 104, 163),
        Color.rgb(224, 143, 84),
        Color.rgb(147, 111, 95),
        Color.rgb(164, 88, 93),
        Color.rgb(158, 136, 96),
        Color.rgb(87, 149, 152),
        Color.rgb(151, 102, 84),
        Color.rgb(195, 130, 75),
        Color.rgb(96, 125, 166),
        Color.rgb(95, 147, 179),
        Color.rgb(171, 93, 98),
        Color.rgb(132, 110, 91),
        Color.rgb(170, 84, 83),
        Color.rgb(95, 131, 166),
        Color.rgb(80, 139, 152),
        Color.rgb(179, 156, 83),
        Color.rgb(110, 143, 123),
        Color.rgb(136, 103, 153),
        Color.rgb(176, 139, 79),
        Color.rgb(89, 135, 95),
        Color.rgb(161, 113, 76)
    )

    fun canonicalName(id: Int): String = canonicalNames[id.coerceIn(0, COUNTRY_COUNT - 1)]
    fun canonicalTag(id: Int): String = canonicalTags[id.coerceIn(0, COUNTRY_COUNT - 1)]

    fun build(
        map: Array<Array<Province>>,
        selectedCountry: Int
    ): Setup {
        val chosen = selectedCountry.coerceIn(0, COUNTRY_COUNT - 1)
        val canonicalToInternal = IntArray(COUNTRY_COUNT)
        canonicalToInternal[chosen] = 0

        var next = 1
        for (canonical in 0 until COUNTRY_COUNT) {
            if (canonical == chosen) continue
            canonicalToInternal[canonical] = next++
        }

        val names = Array(COUNTRY_COUNT) { "" }
        val tags = Array(COUNTRY_COUNT) { "" }
        val colors = IntArray(COUNTRY_COUNT)

        for (canonical in 0 until COUNTRY_COUNT) {
            val internal = canonicalToInternal[canonical]
            names[internal] = canonicalNames[canonical]
            tags[internal] = canonicalTags[canonical]
            colors[internal] = canonicalColors[canonical]
        }

        clearMap(map)

        fun claim(
            canonical: Int,
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            terrain: Int = WarRules.PLAINS,
            cut: ((Int, Int) -> Boolean)? = null
        ) {
            val owner = canonicalToInternal[canonical]
            for (y in y1..y2) {
                for (x in x1..x2) {
                    if (x !in 1 until COLS - 1 || y !in 1 until ROWS - 1) continue
                    if (cut != null && cut(x, y)) continue
                    val p = map[y][x]
                    p.terrain = terrainFor(x, y, terrain)
                    p.owner = owner
                    p.garrison = 4f + ((x * 11 + y * 7 + canonical * 3) % 6)
                    p.infrastructure = when (p.terrain) {
                        WarRules.HILLS -> 1
                        WarRules.FOREST -> 2
                        else -> 3
                    }
                }
            }
        }

        // Atlantic edge / Iberia
        claim(PORTUGAL, 3, 22, 6, 30, WarRules.PLAINS) { x, y ->
            (x == 3 && y < 24) || (x == 6 && y > 28)
        }
        claim(SPAIN, 7, 21, 16, 31, WarRules.PLAINS) { x, y ->
            (x <= 8 && y <= 22) ||
            (x >= 15 && y >= 30) ||
            (x == 16 && y <= 23)
        }

        // France / Benelux
        claim(FRANCE, 15, 14, 24, 22, WarRules.PLAINS) { x, y ->
            (x <= 16 && y <= 14) ||
            (x >= 23 && y <= 15) ||
            (x == 24 && y >= 21)
        }
        claim(BELGIUM, 22, 12, 25, 14)
        claim(NETHERLANDS, 23, 9, 27, 12, WarRules.PLAINS) { x, y ->
            (x == 23 && y == 9) || (x == 27 && y >= 11)
        }
        claim(LUXEMBOURG, 25, 14, 25, 15)

        // British Isles + Ireland
        claim(IRELAND, 8, 10, 11, 15, WarRules.PLAINS) { x, y ->
            (x == 8 && y <= 11) || (x == 11 && y >= 14)
        }
        claim(UK, 12, 7, 17, 16, WarRules.PLAINS) { x, y ->
            (x == 12 && y <= 8) ||
            (x == 17 && y >= 14) ||
            (x >= 16 && y <= 9)
        }
        claim(UK, 13, 4, 15, 7, WarRules.HILLS)

        // Central Europe
        claim(GERMANY, 26, 10, 34, 20, WarRules.PLAINS) { x, y ->
            (x == 26 && y <= 11) ||
            (x >= 33 && y >= 19) ||
            (x == 34 && y <= 12)
        }
        claim(SWITZERLAND, 23, 20, 27, 23, WarRules.HILLS)
        claim(AUSTRIA, 28, 21, 34, 24, WarRules.HILLS)
        claim(CZECHOSLOVAKIA, 33, 18, 39, 21, WarRules.HILLS) { x, y ->
            x == 39 && y == 21
        }
        claim(HUNGARY, 35, 22, 40, 25, WarRules.PLAINS)

        // Poland + Baltics
        claim(POLAND, 36, 11, 44, 19, WarRules.PLAINS) { x, y ->
            (x == 44 && y <= 12) || (x == 36 && y >= 18)
        }
        claim(LITHUANIA, 40, 8, 44, 11, WarRules.FOREST)
        claim(LATVIA, 41, 5, 45, 8, WarRules.FOREST)
        claim(ESTONIA, 42, 3, 46, 5, WarRules.FOREST)

        // Scandinavia
        claim(DENMARK, 27, 7, 30, 9, WarRules.PLAINS) { x, y ->
            x == 30 && y == 7
        }
        claim(NORWAY, 28, 1, 33, 8, WarRules.HILLS) { x, y ->
            (y <= 2 && x !in 30..31) ||
            (x == 33 && y >= 6)
        }
        claim(SWEDEN, 34, 1, 39, 9, WarRules.FOREST) { x, y ->
            y == 1 && x !in 35..37
        }
        claim(FINLAND, 40, 1, 47, 10, WarRules.FOREST) { x, y ->
            (y <= 2 && x >= 46) ||
            (x == 47 && y >= 8)
        }

        // Italy
        claim(ITALY, 28, 24, 34, 29, WarRules.HILLS) { x, y ->
            (x <= 29 && y >= 28) ||
            (x >= 34 && y <= 25)
        }
        claim(ITALY, 31, 29, 34, 33, WarRules.HILLS) { x, y ->
            (x == 31 && y >= 32) || (x == 34 && y == 33)
        }
        claim(ITALY, 35, 33, 36, 34, WarRules.HILLS)

        // Balkans
        claim(YUGOSLAVIA, 34, 26, 40, 30, WarRules.HILLS) { x, y ->
            x == 40 && y >= 29
        }
        claim(ROMANIA, 41, 22, 47, 28, WarRules.PLAINS) { x, y ->
            (x == 47 && y <= 23) || (x == 41 && y >= 27)
        }
        claim(BULGARIA, 41, 29, 46, 32, WarRules.HILLS)
        claim(ALBANIA, 37, 31, 38, 33, WarRules.HILLS)
        claim(GREECE, 38, 33, 44, 35, WarRules.HILLS) { x, y ->
            x == 44 && y == 33
        }

        // USSR / Turkey
        claim(USSR, 46, 5, 62, 27, WarRules.PLAINS) { x, y ->
            (x == 46 && y <= 6) ||
            (x >= 60 && y >= 25) ||
            (x == 62 && y <= 8)
        }
        claim(TURKEY, 46, 30, 62, 34, WarRules.HILLS) { x, y ->
            (x <= 47 && y >= 33) || (x >= 61 && y == 34)
        }

        // Terrain passes after ownership.
        addTerrain(map)
        addCapitalsAndPorts(map, canonicalToInternal)

        assignProvinceRegions(map, COUNTRY_COUNT)

        val starts = MutableList(COUNTRY_COUNT) { 1 to 1 }
        val canonicalStarts = arrayOf(
            30 to 15, // GER
            20 to 18, // FRA
            40 to 15, // POL
            31 to 27, // ITA
            53 to 15, // USSR
            14 to 12, // UK
            12 to 26, // SPA
            5 to 26,  // POR
            23 to 13, // BEL
            25 to 10, // HOL
            25 to 15, // LUX
            25 to 22, // SWI
            31 to 23, // AUS
            36 to 20, // CZE
            37 to 24, // HUN
            44 to 25, // ROM
            37 to 28, // YUG
            41 to 34, // GRE
            44 to 30, // BUL
            37 to 32, // ALB
            28 to 8,  // DEN
            30 to 5,  // NOR
            36 to 5,  // SWE
            43 to 6,  // FIN
            44 to 4,  // EST
            43 to 7,  // LAT
            42 to 10, // LIT
            9 to 13,  // IRE
            53 to 32  // TUR
        )

        for (canonical in 0 until COUNTRY_COUNT) {
            val internal = canonicalToInternal[canonical]
            val pos = canonicalStarts[canonical]
            starts[internal] = nearestOwnedCell(map, internal, pos.first, pos.second)
        }

        return Setup(starts, names, tags, colors)
    }

    private fun clearMap(map: Array<Array<Province>>) {
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                val p = map[y][x]
                p.terrain = WarRules.WATER
                p.owner = WarRules.WATER_OWNER
                p.garrison = 0f
                p.fort = 0
                p.city = false
                p.port = false
                p.silo = false
                p.regionId = -1
                p.industry = 0
                p.infrastructure = 1
            }
        }
    }

    private fun terrainFor(x: Int, y: Int, fallback: Int): Int {
        if ((x * 17 + y * 29) % 19 == 0) return WarRules.FOREST
        if ((x * 11 + y * 7) % 23 == 0) return WarRules.HILLS
        return fallback
    }

    private fun addTerrain(map: Array<Array<Province>>) {
        fun hillRect(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1..y2) for (x in x1..x2) {
                if (x in 0 until COLS && y in 0 until ROWS && map[y][x].terrain != WarRules.WATER) {
                    map[y][x].terrain = WarRules.HILLS
                    map[y][x].infrastructure = 1
                }
            }
        }

        fun forestRect(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1..y2) for (x in x1..x2) {
                if (x in 0 until COLS && y in 0 until ROWS && map[y][x].terrain != WarRules.WATER) {
                    map[y][x].terrain = WarRules.FOREST
                    map[y][x].infrastructure = 2
                }
            }
        }

        hillRect(23, 20, 34, 24) // Alps
        hillRect(33, 26, 44, 35) // Balkans / Greece
        hillRect(4, 25, 16, 31)  // Iberian ranges
        hillRect(46, 30, 62, 34) // Anatolia
        forestRect(39, 1, 47, 11)
        forestRect(45, 6, 58, 15)
        forestRect(35, 9, 43, 18)
    }

    private fun addCapitalsAndPorts(
        map: Array<Array<Province>>,
        canonicalToInternal: IntArray
    ) {
        data class Place(val country: Int, val x: Int, val y: Int)

        val capitals = listOf(
            Place(GERMANY, 30, 15), Place(FRANCE, 20, 18),
            Place(POLAND, 40, 15), Place(ITALY, 31, 27),
            Place(USSR, 53, 15), Place(UK, 14, 12),
            Place(SPAIN, 12, 26), Place(PORTUGAL, 5, 26),
            Place(BELGIUM, 23, 13), Place(NETHERLANDS, 25, 10),
            Place(LUXEMBOURG, 25, 15), Place(SWITZERLAND, 25, 22),
            Place(AUSTRIA, 31, 23), Place(CZECHOSLOVAKIA, 36, 20),
            Place(HUNGARY, 37, 24), Place(ROMANIA, 44, 25),
            Place(YUGOSLAVIA, 37, 28), Place(GREECE, 41, 34),
            Place(BULGARIA, 44, 30), Place(ALBANIA, 37, 32),
            Place(DENMARK, 28, 8), Place(NORWAY, 30, 5),
            Place(SWEDEN, 36, 5), Place(FINLAND, 43, 6),
            Place(ESTONIA, 44, 4), Place(LATVIA, 43, 7),
            Place(LITHUANIA, 42, 10), Place(IRELAND, 9, 13),
            Place(TURKEY, 53, 32)
        )

        for (place in capitals) {
            val owner = canonicalToInternal[place.country]
            val pos = nearestOwnedCell(map, owner, place.x, place.y)
            val p = map[pos.second][pos.first]
            p.city = true
            p.industry = max(p.industry, 3)
            p.infrastructure = max(p.infrastructure, 4)
            p.garrison = 15f
        }

        val ports = listOf(
            Place(FRANCE, 16, 16), Place(GERMANY, 27, 11),
            Place(POLAND, 39, 11), Place(ITALY, 32, 29),
            Place(USSR, 48, 8), Place(UK, 13, 13),
            Place(SPAIN, 9, 24), Place(PORTUGAL, 4, 27),
            Place(NETHERLANDS, 24, 10), Place(NORWAY, 29, 6),
            Place(SWEDEN, 35, 6), Place(FINLAND, 42, 7),
            Place(GREECE, 40, 34), Place(TURKEY, 51, 32),
            Place(ROMANIA, 46, 27), Place(DENMARK, 28, 8)
        )

        for (place in ports) {
            val owner = canonicalToInternal[place.country]
            val pos = nearestOwnedCell(map, owner, place.x, place.y)
            map[pos.second][pos.first].port = true
            map[pos.second][pos.first].infrastructure = max(3, map[pos.second][pos.first].infrastructure)
        }
    }

    private fun nearestOwnedCell(
        map: Array<Array<Province>>,
        owner: Int,
        targetX: Int,
        targetY: Int
    ): Pair<Int, Int> {
        var best = 1 to 1
        var bestDistance = Int.MAX_VALUE
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                if (map[y][x].owner != owner) continue
                val d = abs(x - targetX) + abs(y - targetY)
                if (d < bestDistance) {
                    bestDistance = d
                    best = x to y
                }
            }
        }
        return best
    }

    private fun assignProvinceRegions(
        map: Array<Array<Province>>,
        countryCount: Int
    ) {
        var nextRegion = 1
        val rng = Random(1936)

        for (owner in 0 until countryCount) {
            val cells = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until ROWS) {
                for (x in 0 until COLS) {
                    if (map[y][x].owner == owner) cells += x to y
                }
            }
            if (cells.isEmpty()) continue

            val targetSeeds = max(1, cells.size / 9)
            val shuffled = cells.shuffled(rng)
            val seeds = mutableListOf<Triple<Int, Int, Int>>()

            for (i in 0 until min(targetSeeds, shuffled.size)) {
                val (sx, sy) = shuffled[i]
                seeds += Triple(sx, sy, nextRegion++)
            }

            for ((x, y) in cells) {
                var bestRegion = seeds.first().third
                var bestScore = Int.MAX_VALUE

                for ((sx, sy, region) in seeds) {
                    val jitter = ((x * 31 + y * 17 + region * 13) and 3)
                    val score = abs(x - sx) * 3 + abs(y - sy) * 3 + jitter
                    if (score < bestScore) {
                        bestScore = score
                        bestRegion = region
                    }
                }

                map[y][x].regionId = bestRegion
            }
        }
    }
}
