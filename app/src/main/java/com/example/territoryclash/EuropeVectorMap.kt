package com.example.territoryclash

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class MapPoint(val x: Float, val y: Float)

data class VectorProvince(
    val id: Int,
    val countryId: Int,
    val polygon: List<MapPoint>,
    val center: MapPoint,
    val terrain: Int,
    val name: String,
    val neighbors: MutableSet<Int> = mutableSetOf(),
    var owner: Int = countryId,
    var fort: Int = 0,
    var industry: Int = 1,
    var infrastructure: Int = 2,
    var city: Boolean = false,
    var port: Boolean = false,
    var airfield: Boolean = false,
    var selected: Boolean = false
)

data class CountryVectorShape(
    val id: Int,
    val outline: List<MapPoint>,
    val provinceCount: Int
)

object EuropeVectorMap {

    fun build(): MutableList<VectorProvince> {
        val provinces = mutableListOf<VectorProvince>()
        var nextId = 0

        for (shape in shapes()) {
            val generated = generateCountry(shape, nextId)
            provinces += generated
            nextId += generated.size
        }

        connectInternalBorders(provinces)
        connectCountryBorders(provinces)
        decorate(provinces)
        return provinces
    }

    private fun p(x: Float, y: Float) = MapPoint(x, y)

    private fun shapes(): List<CountryVectorShape> = listOf(
        CountryVectorShape(EuropeScenario.PORTUGAL, listOf(p(.045f,.58f),p(.075f,.56f),p(.086f,.79f),p(.052f,.82f)), 5),
        CountryVectorShape(EuropeScenario.SPAIN, listOf(p(.078f,.55f),p(.195f,.53f),p(.214f,.76f),p(.175f,.83f),p(.088f,.80f)), 15),
        CountryVectorShape(EuropeScenario.FRANCE, listOf(p(.185f,.39f),p(.335f,.38f),p(.365f,.58f),p(.302f,.68f),p(.205f,.62f)), 18),
        CountryVectorShape(EuropeScenario.BELGIUM, listOf(p(.315f,.35f),p(.353f,.35f),p(.365f,.405f),p(.326f,.415f)), 4),
        CountryVectorShape(EuropeScenario.NETHERLANDS, listOf(p(.326f,.30f),p(.369f,.29f),p(.378f,.35f),p(.340f,.365f)), 5),
        CountryVectorShape(EuropeScenario.LUXEMBOURG, listOf(p(.354f,.405f),p(.369f,.402f),p(.371f,.427f),p(.356f,.430f)), 2),
        CountryVectorShape(EuropeScenario.GERMANY, listOf(p(.366f,.29f),p(.480f,.30f),p(.505f,.47f),p(.455f,.55f),p(.365f,.50f)), 20),
        CountryVectorShape(EuropeScenario.SWITZERLAND, listOf(p(.326f,.57f),p(.392f,.56f),p(.404f,.61f),p(.342f,.625f)), 6),
        CountryVectorShape(EuropeScenario.AUSTRIA, listOf(p(.405f,.55f),p(.493f,.54f),p(.514f,.60f),p(.425f,.62f)), 8),
        CountryVectorShape(EuropeScenario.CZECHOSLOVAKIA, listOf(p(.468f,.45f),p(.555f,.44f),p(.576f,.50f),p(.492f,.53f)), 9),
        CountryVectorShape(EuropeScenario.POLAND, listOf(p(.500f,.31f),p(.625f,.32f),p(.646f,.48f),p(.555f,.52f),p(.492f,.46f)), 18),
        CountryVectorShape(EuropeScenario.LITHUANIA, listOf(p(.592f,.255f),p(.643f,.255f),p(.650f,.31f),p(.603f,.32f)), 5),
        CountryVectorShape(EuropeScenario.LATVIA, listOf(p(.603f,.205f),p(.656f,.205f),p(.662f,.257f),p(.612f,.263f)), 5),
        CountryVectorShape(EuropeScenario.ESTONIA, listOf(p(.612f,.158f),p(.661f,.155f),p(.669f,.208f),p(.620f,.211f)), 4),
        CountryVectorShape(EuropeScenario.DENMARK, listOf(p(.402f,.235f),p(.438f,.228f),p(.451f,.282f),p(.414f,.292f)), 4),
        CountryVectorShape(EuropeScenario.NORWAY, listOf(p(.386f,.055f),p(.435f,.045f),p(.470f,.225f),p(.430f,.245f),p(.398f,.165f)), 10),
        CountryVectorShape(EuropeScenario.SWEDEN, listOf(p(.455f,.05f),p(.520f,.058f),p(.536f,.245f),p(.472f,.255f)), 12),
        CountryVectorShape(EuropeScenario.FINLAND, listOf(p(.525f,.07f),p(.602f,.068f),p(.633f,.225f),p(.548f,.248f)), 12),
        CountryVectorShape(EuropeScenario.UK, listOf(p(.135f,.19f),p(.192f,.17f),p(.232f,.40f),p(.170f,.48f),p(.128f,.35f)), 16),
        CountryVectorShape(EuropeScenario.IRELAND, listOf(p(.082f,.29f),p(.127f,.275f),p(.137f,.39f),p(.090f,.41f)), 6),
        CountryVectorShape(EuropeScenario.ITALY, listOf(p(.392f,.62f),p(.454f,.61f),p(.503f,.79f),p(.477f,.86f),p(.448f,.75f),p(.405f,.69f)), 15),
        CountryVectorShape(EuropeScenario.HUNGARY, listOf(p(.492f,.55f),p(.565f,.55f),p(.578f,.62f),p(.505f,.63f)), 7),
        CountryVectorShape(EuropeScenario.ROMANIA, listOf(p(.570f,.54f),p(.655f,.545f),p(.680f,.65f),p(.594f,.675f),p(.558f,.615f)), 10),
        CountryVectorShape(EuropeScenario.YUGOSLAVIA, listOf(p(.485f,.625f),p(.575f,.625f),p(.592f,.72f),p(.508f,.745f),p(.472f,.68f)), 11),
        CountryVectorShape(EuropeScenario.BULGARIA, listOf(p(.586f,.67f),p(.660f,.67f),p(.673f,.735f),p(.600f,.745f)), 7),
        CountryVectorShape(EuropeScenario.ALBANIA, listOf(p(.492f,.735f),p(.515f,.73f),p(.522f,.79f),p(.500f,.80f)), 3),
        CountryVectorShape(EuropeScenario.GREECE, listOf(p(.515f,.75f),p(.600f,.75f),p(.617f,.84f),p(.548f,.89f),p(.512f,.825f)), 9),
        CountryVectorShape(EuropeScenario.TURKEY, listOf(p(.645f,.74f),p(.865f,.73f),p(.885f,.82f),p(.674f,.84f)), 18),
        CountryVectorShape(EuropeScenario.USSR, listOf(p(.660f,.12f),p(.985f,.10f),p(.985f,.70f),p(.830f,.72f),p(.685f,.63f),p(.635f,.32f)), 48)
    )

    private fun generateCountry(shape: CountryVectorShape, idOffset: Int): List<VectorProvince> {
        val rng = Random(193600 + shape.id)
        val seeds = mutableListOf<MapPoint>()
        val bounds = bounds(shape.outline)

        var attempts = 0
        while (seeds.size < shape.provinceCount && attempts < shape.provinceCount * 500) {
            attempts++
            val x = rng.nextFloat() * (bounds[2] - bounds[0]) + bounds[0]
            val y = rng.nextFloat() * (bounds[3] - bounds[1]) + bounds[1]
            val candidate = MapPoint(x, y)
            if (!pointInPolygon(candidate, shape.outline)) continue

            val minDistance = seeds.minOfOrNull { distanceSq(it, candidate) } ?: 99f
            if (minDistance < 0.00055f && attempts < shape.provinceCount * 350) continue
            seeds += candidate
        }

        if (seeds.isEmpty()) {
            seeds += polygonCenter(shape.outline)
        }

        val result = mutableListOf<VectorProvince>()

        for ((index, seed) in seeds.withIndex()) {
            var cell = shape.outline.toList()

            for ((j, other) in seeds.withIndex()) {
                if (j == index || cell.size < 3) continue

                val a = 2f * (other.x - seed.x)
                val b = 2f * (other.y - seed.y)
                val c = other.x * other.x + other.y * other.y -
                    seed.x * seed.x - seed.y * seed.y

                cell = clipHalfPlane(cell, a, b, c)
            }

            if (cell.size < 3) continue

            val center = polygonCenter(cell)
            val terrain = terrainFor(center, shape.id, index)

            result += VectorProvince(
                id = idOffset + result.size,
                countryId = shape.id,
                polygon = cell,
                center = center,
                terrain = terrain,
                name = provinceName(shape.id, result.size),
                owner = shape.id,
                industry = if (index == 0) 3 else if (index % 5 == 0) 2 else 1,
                infrastructure = when (terrain) {
                    WarRules.HILLS -> 1
                    WarRules.FOREST -> 2
                    else -> 3
                }
            )
        }

        return result
    }

    private fun connectInternalBorders(provinces: MutableList<VectorProvince>) {
        val byCountry = provinces.groupBy { it.countryId }

        for ((_, countryProvinces) in byCountry) {
            for (i in countryProvinces.indices) {
                for (j in i + 1 until countryProvinces.size) {
                    val a = countryProvinces[i]
                    val b = countryProvinces[j]
                    if (shareBoundary(a.polygon, b.polygon)) {
                        a.neighbors += b.id
                        b.neighbors += a.id
                    }
                }
            }

            // Guarantee graph connectivity even if floating point clipping misses an edge.
            for (province in countryProvinces) {
                if (province.neighbors.isNotEmpty()) continue
                val nearest = countryProvinces
                    .filter { it.id != province.id }
                    .minByOrNull { distanceSq(it.center, province.center) }
                if (nearest != null) {
                    province.neighbors += nearest.id
                    nearest.neighbors += province.id
                }
            }
        }
    }

    private fun connectCountryBorders(provinces: MutableList<VectorProvince>) {
        val neighborPairs = listOf(
            EuropeScenario.PORTUGAL to EuropeScenario.SPAIN,
            EuropeScenario.SPAIN to EuropeScenario.FRANCE,
            EuropeScenario.FRANCE to EuropeScenario.BELGIUM,
            EuropeScenario.FRANCE to EuropeScenario.LUXEMBOURG,
            EuropeScenario.FRANCE to EuropeScenario.GERMANY,
            EuropeScenario.FRANCE to EuropeScenario.SWITZERLAND,
            EuropeScenario.BELGIUM to EuropeScenario.NETHERLANDS,
            EuropeScenario.BELGIUM to EuropeScenario.GERMANY,
            EuropeScenario.NETHERLANDS to EuropeScenario.GERMANY,
            EuropeScenario.LUXEMBOURG to EuropeScenario.GERMANY,
            EuropeScenario.GERMANY to EuropeScenario.DENMARK,
            EuropeScenario.GERMANY to EuropeScenario.POLAND,
            EuropeScenario.GERMANY to EuropeScenario.CZECHOSLOVAKIA,
            EuropeScenario.GERMANY to EuropeScenario.AUSTRIA,
            EuropeScenario.SWITZERLAND to EuropeScenario.AUSTRIA,
            EuropeScenario.SWITZERLAND to EuropeScenario.ITALY,
            EuropeScenario.AUSTRIA to EuropeScenario.ITALY,
            EuropeScenario.AUSTRIA to EuropeScenario.CZECHOSLOVAKIA,
            EuropeScenario.AUSTRIA to EuropeScenario.HUNGARY,
            EuropeScenario.CZECHOSLOVAKIA to EuropeScenario.POLAND,
            EuropeScenario.CZECHOSLOVAKIA to EuropeScenario.HUNGARY,
            EuropeScenario.POLAND to EuropeScenario.LITHUANIA,
            EuropeScenario.POLAND to EuropeScenario.USSR,
            EuropeScenario.LITHUANIA to EuropeScenario.LATVIA,
            EuropeScenario.LITHUANIA to EuropeScenario.USSR,
            EuropeScenario.LATVIA to EuropeScenario.ESTONIA,
            EuropeScenario.LATVIA to EuropeScenario.USSR,
            EuropeScenario.ESTONIA to EuropeScenario.USSR,
            EuropeScenario.FINLAND to EuropeScenario.USSR,
            EuropeScenario.NORWAY to EuropeScenario.SWEDEN,
            EuropeScenario.SWEDEN to EuropeScenario.FINLAND,
            EuropeScenario.HUNGARY to EuropeScenario.ROMANIA,
            EuropeScenario.HUNGARY to EuropeScenario.YUGOSLAVIA,
            EuropeScenario.ROMANIA to EuropeScenario.USSR,
            EuropeScenario.ROMANIA to EuropeScenario.YUGOSLAVIA,
            EuropeScenario.ROMANIA to EuropeScenario.BULGARIA,
            EuropeScenario.YUGOSLAVIA to EuropeScenario.ITALY,
            EuropeScenario.YUGOSLAVIA to EuropeScenario.ALBANIA,
            EuropeScenario.YUGOSLAVIA to EuropeScenario.BULGARIA,
            EuropeScenario.ALBANIA to EuropeScenario.GREECE,
            EuropeScenario.BULGARIA to EuropeScenario.GREECE,
            EuropeScenario.BULGARIA to EuropeScenario.TURKEY,
            EuropeScenario.GREECE to EuropeScenario.TURKEY,
            EuropeScenario.TURKEY to EuropeScenario.USSR
        )

        val byCountry = provinces.groupBy { it.countryId }

        for ((aCountry, bCountry) in neighborPairs) {
            val aList = byCountry[aCountry].orEmpty()
            val bList = byCountry[bCountry].orEmpty()
            if (aList.isEmpty() || bList.isEmpty()) continue

            val pairs = mutableListOf<Triple<Float, VectorProvince, VectorProvince>>()
            for (a in aList) {
                for (b in bList) {
                    pairs += Triple(distanceSq(a.center, b.center), a, b)
                }
            }

            val usedA = mutableSetOf<Int>()
            val usedB = mutableSetOf<Int>()

            for ((_, a, b) in pairs.sortedBy { it.first }.take(12)) {
                if (usedA.size >= 3 && usedB.size >= 3) break
                if (a.id in usedA && b.id in usedB) continue
                a.neighbors += b.id
                b.neighbors += a.id
                usedA += a.id
                usedB += b.id
            }
        }
    }

    private fun decorate(provinces: MutableList<VectorProvince>) {
        val byCountry = provinces.groupBy { it.countryId }

        for ((country, list) in byCountry) {
            if (list.isEmpty()) continue
            val center = list.minByOrNull { distanceSq(it.center, countryAnchor(country)) } ?: list.first()
            center.city = true
            center.airfield = true
            center.industry = max(center.industry, 4)
            center.infrastructure = max(center.infrastructure, 4)

            val coastalCandidate = list.minByOrNull { province ->
                val edgeBias = min(
                    min(province.center.x, 1f - province.center.x),
                    min(province.center.y, 1f - province.center.y)
                )
                edgeBias + abs(province.center.x - countryAnchor(country).x) * .15f
            }
            if (coastalCandidate != null &&
                country !in setOf(
                    EuropeScenario.SWITZERLAND,
                    EuropeScenario.AUSTRIA,
                    EuropeScenario.CZECHOSLOVAKIA,
                    EuropeScenario.HUNGARY,
                    EuropeScenario.LUXEMBOURG
                )
            ) {
                coastalCandidate.port = true
            }
        }
    }

    private fun countryAnchor(country: Int): MapPoint = when (country) {
        EuropeScenario.GERMANY -> p(.425f,.42f)
        EuropeScenario.FRANCE -> p(.27f,.50f)
        EuropeScenario.POLAND -> p(.56f,.41f)
        EuropeScenario.ITALY -> p(.44f,.69f)
        EuropeScenario.USSR -> p(.79f,.39f)
        EuropeScenario.UK -> p(.18f,.33f)
        EuropeScenario.SPAIN -> p(.14f,.67f)
        EuropeScenario.PORTUGAL -> p(.064f,.69f)
        EuropeScenario.BELGIUM -> p(.34f,.38f)
        EuropeScenario.NETHERLANDS -> p(.35f,.325f)
        EuropeScenario.LUXEMBOURG -> p(.363f,.416f)
        EuropeScenario.SWITZERLAND -> p(.36f,.59f)
        EuropeScenario.AUSTRIA -> p(.46f,.585f)
        EuropeScenario.CZECHOSLOVAKIA -> p(.52f,.485f)
        EuropeScenario.HUNGARY -> p(.535f,.59f)
        EuropeScenario.ROMANIA -> p(.62f,.61f)
        EuropeScenario.YUGOSLAVIA -> p(.535f,.68f)
        EuropeScenario.GREECE -> p(.56f,.82f)
        EuropeScenario.BULGARIA -> p(.625f,.71f)
        EuropeScenario.ALBANIA -> p(.505f,.765f)
        EuropeScenario.DENMARK -> p(.425f,.26f)
        EuropeScenario.NORWAY -> p(.425f,.13f)
        EuropeScenario.SWEDEN -> p(.49f,.15f)
        EuropeScenario.FINLAND -> p(.575f,.16f)
        EuropeScenario.ESTONIA -> p(.64f,.18f)
        EuropeScenario.LATVIA -> p(.635f,.235f)
        EuropeScenario.LITHUANIA -> p(.62f,.285f)
        EuropeScenario.IRELAND -> p(.108f,.345f)
        EuropeScenario.TURKEY -> p(.76f,.785f)
        else -> p(.5f,.5f)
    }

    private fun terrainFor(center: MapPoint, country: Int, index: Int): Int {
        if (country in setOf(
                EuropeScenario.SWITZERLAND,
                EuropeScenario.AUSTRIA,
                EuropeScenario.YUGOSLAVIA,
                EuropeScenario.GREECE,
                EuropeScenario.ALBANIA,
                EuropeScenario.TURKEY,
                EuropeScenario.NORWAY
            )
        ) {
            return if (index % 4 == 0) WarRules.FOREST else WarRules.HILLS
        }

        if (country in setOf(
                EuropeScenario.FINLAND,
                EuropeScenario.SWEDEN,
                EuropeScenario.ESTONIA,
                EuropeScenario.LATVIA,
                EuropeScenario.LITHUANIA
            )
        ) {
            return WarRules.FOREST
        }

        val value = ((center.x * 997f + center.y * 619f + index * 11f).toInt() and 7)
        return when (value) {
            0 -> WarRules.FOREST
            1 -> WarRules.HILLS
            else -> WarRules.PLAINS
        }
    }

    private fun provinceName(country: Int, index: Int): String =
        "${EuropeScenario.canonicalTag(country)}-${index + 1}"

    private fun bounds(poly: List<MapPoint>): FloatArray {
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f

        for (p in poly) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }

        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun distanceSq(a: MapPoint, b: MapPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun pointInPolygon(point: MapPoint, polygon: List<MapPoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex

        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersects =
                ((pi.y > point.y) != (pj.y > point.y)) &&
                    (point.x <
                        (pj.x - pi.x) * (point.y - pi.y) /
                        ((pj.y - pi.y).let { if (abs(it) < 0.000001f) 0.000001f else it }) +
                        pi.x
                    )
            if (intersects) inside = !inside
            j = i
        }

        return inside
    }

    private fun polygonCenter(poly: List<MapPoint>): MapPoint {
        if (poly.isEmpty()) return MapPoint(.5f,.5f)
        var sx = 0f
        var sy = 0f
        for (point in poly) {
            sx += point.x
            sy += point.y
        }
        return MapPoint(sx / poly.size, sy / poly.size)
    }

    private fun clipHalfPlane(
        input: List<MapPoint>,
        a: Float,
        b: Float,
        c: Float
    ): List<MapPoint> {
        if (input.isEmpty()) return emptyList()

        val output = mutableListOf<MapPoint>()

        fun inside(point: MapPoint): Boolean =
            a * point.x + b * point.y <= c + 0.000001f

        fun intersection(s: MapPoint, e: MapPoint): MapPoint {
            val dx = e.x - s.x
            val dy = e.y - s.y
            val denominator = a * dx + b * dy

            if (abs(denominator) < 0.000001f) {
                return s
            }

            val t = ((c - a * s.x - b * s.y) / denominator).coerceIn(0f, 1f)
            return MapPoint(s.x + dx * t, s.y + dy * t)
        }

        var previous = input.last()
        var previousInside = inside(previous)

        for (current in input) {
            val currentInside = inside(current)

            when {
                currentInside && previousInside -> output += current
                currentInside && !previousInside -> {
                    output += intersection(previous, current)
                    output += current
                }
                !currentInside && previousInside -> {
                    output += intersection(previous, current)
                }
            }

            previous = current
            previousInside = currentInside
        }

        return output
    }

    private fun shareBoundary(a: List<MapPoint>, b: List<MapPoint>): Boolean {
        var matches = 0
        for (pa in a) {
            for (pb in b) {
                if (distanceSq(pa, pb) < 0.0000025f) {
                    matches++
                    if (matches >= 2) return true
                }
            }
        }
        return false
    }
}
