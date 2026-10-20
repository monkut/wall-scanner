package io.github.monkut.wallscanner.core

class Route(val id: Int, var name: String, val r: Int, val g: Int, val b: Int, val holdIds: MutableList<Int>) {
    var grade: String = ""
}

/**
 * Groups holds into candidate routes by color: circular hue clusters for saturated holds, value bands otherwise.
 * With [splitByPosition] (circuit walls) each colour group is further split into spatially connected problems.
 */
object RouteGrouper {
    fun group(holds: List<Hold>, splitByPosition: Boolean = false): List<Route> {
        val chromatic = holds.filter { it.sat >= Tuning.MIN_ROUTE_SATURATION && it.value >= Tuning.MIN_ROUTE_VALUE }
        val achromatic = holds.filter { it !in chromatic }

        val groups = ArrayList<List<Hold>>()
        groups.addAll(hueClusters(chromatic))
        val black = achromatic.filter { it.value < 0.25f }
        val white = achromatic.filter { it.value >= 0.65f }
        val gray = achromatic.filter { it !in black && it !in white }
        if (black.isNotEmpty()) groups.add(black)
        if (white.isNotEmpty()) groups.add(white)
        if (gray.isNotEmpty()) groups.add(gray)

        val routes = ArrayList<Route>()
        for (colorGroup in groups) {
            val name = when {
                colorGroup === black -> "Black"
                colorGroup === white -> "White"
                colorGroup === gray -> "Gray"
                else -> ColorUtil.hueName(meanHue(colorGroup))
            }
            val problems = if (splitByPosition) connectedProblems(colorGroup) else listOf(colorGroup)
            for (members in problems) {
                if (members.size < Tuning.MIN_ROUTE_HOLDS) continue
                val id = routes.size + 1
                val route = Route(
                    id, name,
                    members.sumOf { it.r } / members.size,
                    members.sumOf { it.g } / members.size,
                    members.sumOf { it.b } / members.size,
                    members.map { it.id }.toMutableList(),
                )
                members.forEach { it.routeId = id; it.routeIds.add(id) }
                routes.add(route)
            }
        }
        disambiguateNames(routes)
        return routes
    }

    /** Splits holds into groups whose members chain together within [Tuning.MAX_ROUTE_HOLD_GAP] in the wall plane. */
    private fun connectedProblems(holds: List<Hold>): List<List<Hold>> {
        val parent = IntArray(holds.size) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val maxGap2 = Tuning.MAX_ROUTE_HOLD_GAP * Tuning.MAX_ROUTE_HOLD_GAP
        for (i in holds.indices) for (j in i + 1 until holds.size) {
            val du = holds[i].u - holds[j].u; val dv = holds[i].v - holds[j].v
            if (du * du + dv * dv <= maxGap2) parent[find(i)] = find(j)
        }
        return holds.indices.groupBy { find(it) }.values
            .map { idx -> idx.map { holds[it] } }
            .sortedBy { members -> members.minOf { it.u } }
    }

    /** Sorts by hue, starts after the largest circular gap and splits where consecutive hues differ by more than [Tuning.HUE_GAP_DEG]. */
    private fun hueClusters(holds: List<Hold>): List<List<Hold>> {
        if (holds.isEmpty()) return emptyList()
        val sorted = holds.sortedBy { it.hue }
        val n = sorted.size
        var start = 0
        var largestGap = -1f
        for (i in 0 until n) {
            val gap = circularGap(sorted[i].hue, sorted[(i + 1) % n].hue)
            if (gap > largestGap) { largestGap = gap; start = (i + 1) % n }
        }
        val clusters = ArrayList<MutableList<Hold>>()
        var current = mutableListOf(sorted[start])
        clusters.add(current)
        for (k in 1 until n) {
            val prev = sorted[(start + k - 1) % n]
            val hold = sorted[(start + k) % n]
            if (circularGap(prev.hue, hold.hue) > Tuning.HUE_GAP_DEG) {
                current = mutableListOf()
                clusters.add(current)
            }
            current.add(hold)
        }
        return clusters
    }

    private fun circularGap(from: Float, to: Float): Float = ((to - from) % 360f + 360f) % 360f

    private fun meanHue(holds: List<Hold>): Float {
        var sx = 0.0; var sy = 0.0
        for (h in holds) {
            val rad = Math.toRadians(h.hue.toDouble())
            sx += Math.cos(rad); sy += Math.sin(rad)
        }
        val deg = Math.toDegrees(Math.atan2(sy, sx)).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }

    private fun disambiguateNames(routes: List<Route>) {
        val seen = HashMap<String, Int>()
        for (route in routes) {
            val count = (seen[route.name] ?: 0) + 1
            seen[route.name] = count
            if (count > 1) route.name = "${route.name} $count"
        }
    }
}
