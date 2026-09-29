package com.example.territoryclash

object EuropeScenario {
    const val COLS = 42
    const val ROWS = 24

    const val GERMANY = 0
    const val FRANCE = 1
    const val POLAND = 2
    const val ITALY = 3
    const val USSR = 4
    const val UK = 5

    data class Setup(
        val starts: List<Pair<Int, Int>>,
        val names: Array<String>,
        val tags: Array<String>
    )

    private val canonicalNames = arrayOf(
        "Германия", "Франция", "Польша", "Италия", "СССР", "Великобритания"
    )

    private val canonicalTags = arrayOf(
        "GER", "FRA", "POL", "ITA", "USSR", "UK"
    )

    fun build(
        map: Array<Array<Province>>,
        selectedCountry: Int
    ): Setup {
        val canonicalToInternal = IntArray(6)
        canonicalToInternal[selectedCountry] = 0

        var next = 1
        for (canonical in 0 until 6) {
            if (canonical == selectedCountry) continue
            canonicalToInternal[canonical] = next++
        }

        val names = Array(6) { "" }
        val tags = Array(6) { "" }
        for (canonical in 0 until 6) {
            val internal = canonicalToInternal[canonical]
            names[internal] = canonicalNames[canonical]
            tags[internal] = canonicalTags[canonical]
        }

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
            }
        }

        fun land(x: Int, y: Int, terrain: Int = WarRules.PLAINS) {
            if (x !in 0 until COLS || y !in 0 until ROWS) return
            val p = map[y][x]
            p.terrain = terrain
            p.owner = WarRules.NEUTRAL
            p.garrison = 5f + ((x * 13 + y * 7) % 7)
        }

        // Iberia
        for (y in 16..20) {
            for (x in 7..13) {
                if ((x == 7 && y <= 17) || (x == 13 && y >= 20)) continue
                land(x, y, if (y <= 17) WarRules.HILLS else WarRules.PLAINS)
            }
        }

        // France / Low Countries / western central Europe
        for (y in 10..16) {
            for (x in 11..18) {
                if ((x == 11 && y <= 11) || (x == 18 && y >= 16)) continue
                land(x, y, if (x <= 12 && y >= 13) WarRules.HILLS else WarRules.PLAINS)
            }
        }

        // Germany / central Europe
        for (y in 7..15) {
            for (x in 16..23) {
                if ((x == 16 && y == 7) || (x == 23 && y <= 8)) continue
                val terrain = when {
                    y >= 14 -> WarRules.HILLS
                    x <= 17 && y >= 11 -> WarRules.FOREST
                    else -> WarRules.PLAINS
                }
                land(x, y, terrain)
            }
        }

        // Poland / Baltics
        for (y in 7..13) {
            for (x in 22..28) {
                if (x == 28 && y <= 8) continue
                land(x, y, if (y <= 8) WarRules.FOREST else WarRules.PLAINS)
            }
        }
        for (y in 5..7) for (x in 24..28) land(x, y, WarRules.FOREST)

        // Italy
        for (y in 15..20) {
            val minX = when (y) {
                15 -> 18
                16 -> 18
                17 -> 19
                18 -> 20
                19 -> 20
                else -> 21
            }
            val maxX = when (y) {
                15 -> 22
                16 -> 22
                17 -> 22
                18 -> 22
                19 -> 22
                else -> 22
            }
            for (x in minX..maxX) land(x, y, WarRules.HILLS)
        }
        land(23, 20, WarRules.HILLS)
        land(24, 21, WarRules.HILLS)

        // Balkans / Greece / Romania
        for (y in 14..19) {
            for (x in 22..29) {
                if (y >= 18 && x <= 23) continue
                land(x, y, if ((x + y) % 3 == 0) WarRules.HILLS else WarRules.PLAINS)
            }
        }
        land(27, 20, WarRules.HILLS)
        land(28, 21, WarRules.HILLS)

        // Scandinavia
        for (y in 1..7) {
            for (x in 17..23) {
                val narrow = when {
                    y <= 2 -> x in 19..21
                    y <= 4 -> x in 18..22
                    else -> x in 17..23
                }
                if (narrow) land(x, y, WarRules.FOREST)
            }
        }
        for (y in 2..6) for (x in 24..26) {
            if (!(y == 2 && x == 26)) land(x, y, WarRules.FOREST)
        }

        // British Isles
        for (y in 5..10) {
            for (x in 6..10) {
                if ((x == 6 && y <= 6) || (x == 10 && y >= 9)) continue
                land(x, y, if (y <= 6) WarRules.HILLS else WarRules.PLAINS)
            }
        }
        land(5, 6, WarRules.HILLS)
        land(6, 4, WarRules.HILLS)
        land(7, 4, WarRules.HILLS)

        // Eastern Europe / USSR
        for (y in 5..16) {
            for (x in 27..40) {
                if (y == 5 && x >= 37) continue
                if (y >= 15 && x >= 38) continue
                val terrain = when {
                    y <= 7 -> WarRules.FOREST
                    x >= 34 && y <= 11 -> WarRules.FOREST
                    else -> WarRules.PLAINS
                }
                land(x, y, terrain)
            }
        }

        // Turkey / Caucasus
        for (y in 17..20) {
            for (x in 29..39) {
                if (x >= 38 && y >= 20) continue
                land(x, y, WarRules.HILLS)
            }
        }

        fun assignRect(
            canonical: Int,
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int
        ) {
            val owner = canonicalToInternal[canonical]
            for (y in y1..y2) {
                for (x in x1..x2) {
                    val p = map[y][x]
                    if (p.terrain != WarRules.WATER) {
                        p.owner = owner
                        p.garrison = 5f
                    }
                }
            }
        }

        assignRect(FRANCE, 11, 10, 16, 16)
        assignRect(GERMANY, 17, 8, 22, 14)
        assignRect(POLAND, 23, 8, 27, 13)
        assignRect(ITALY, 18, 15, 24, 21)
        assignRect(USSR, 29, 5, 40, 16)
        assignRect(UK, 5, 4, 10, 10)

        // Restore some border minors to neutral to make the front less blocky.
        val neutralCells = listOf(
            16 to 9, 16 to 10, 17 to 7,
            22 to 14, 23 to 14, 24 to 14,
            25 to 15, 26 to 15, 27 to 15,
            22 to 18, 23 to 18, 24 to 19,
            28 to 8, 28 to 9, 28 to 10
        )
        for ((x, y) in neutralCells) {
            if (x in 0 until COLS && y in 0 until ROWS && map[y][x].terrain != WarRules.WATER) {
                map[y][x].owner = WarRules.NEUTRAL
                map[y][x].garrison = 7f
            }
        }

        data class Capital(val canonical: Int, val x: Int, val y: Int)
        val capitals = listOf(
            Capital(GERMANY, 19, 10),
            Capital(FRANCE, 14, 12),
            Capital(POLAND, 25, 10),
            Capital(ITALY, 21, 17),
            Capital(USSR, 34, 9),
            Capital(UK, 8, 8)
        )

        val starts = MutableList(6) { 1 to 1 }
        for (capital in capitals) {
            val owner = canonicalToInternal[capital.canonical]
            val p = map[capital.y][capital.x]
            p.terrain = WarRules.PLAINS
            p.owner = owner
            p.city = true
            p.garrison = 16f
            starts[owner] = capital.x to capital.y
        }

        val ports = listOf(
            FRANCE to (12 to 13),
            GERMANY to (18 to 8),
            POLAND to (24 to 8),
            ITALY to (21 to 18),
            USSR to (30 to 7),
            UK to (7 to 8)
        )
        for ((canonical, pos) in ports) {
            val owner = canonicalToInternal[canonical]
            val p = map[pos.second][pos.first]
            if (p.terrain != WarRules.WATER) {
                p.owner = owner
                p.port = true
            }
        }

        // A few industrial cities.
        val extraCities = listOf(
            GERMANY to (21 to 11),
            FRANCE to (13 to 14),
            POLAND to (26 to 11),
            ITALY to (20 to 16),
            USSR to (37 to 10),
            UK to (8 to 6)
        )
        for ((canonical, pos) in extraCities) {
            val owner = canonicalToInternal[canonical]
            val p = map[pos.second][pos.first]
            if (p.terrain != WarRules.WATER) {
                p.owner = owner
                p.city = true
            }
        }

        return Setup(starts, names, tags)
    }
}
