package io.github.monkut.wallscanner.core

class Route(val id: Int, var name: String, val r: Int, val g: Int, val b: Int, val holdIds: MutableList<Int>) {
    var grade: String = ""
    /** Scale [grade] is expressed in; null until a grade is chosen (the viewer defaults to the location's scale). */
    var gradeSystem: GradeSystem? = null
    /** Identification is done: the route is a record that sends can be logged against (sends.json). */
    var confirmed: Boolean = false
    var kind: RouteKind = RouteKind.ROUTE
}

/**
 * Groups holds into candidate routes by color: circular hue clusters for saturated holds, value bands otherwise.
 * Pale holds that still show a hue join the nearest hue cluster (see [attachPale]); enough of them of one hue
 * with no cluster to join are a route of their own (see [paleRoutes]); the pale holds under a tape-marked start
 * hold are its route's foot holds (see [anchorToStarts]). On a wall at least [Tuning.DOWN_CLIMB_MIN_WALL_HEIGHT]
 * high the grey group is the down-climb holds ([RouteKind.DOWN_CLIMB]), not a route.
 * With [splitByPosition] (circuit walls) each colour group is further split into spatially connected problems.
 * Holds the colour of the [wall] (depth streaks at mats and edges, or wall seen through a sparse hold) stay
 * unassigned instead of bridging unrelated hues into one route.
 */
object RouteGrouper {
    private const val BLACK_BELOW = 0.25f
    private const val WHITE_FROM = 0.65f
    const val DOWN_CLIMB_NAME = "Down climb"

    /** [wallHeight] is the scanned wall's height in metres (its extent), when known. */
    fun group(holds: List<Hold>, splitByPosition: Boolean = false, wall: WallColor? = null, wallHeight: Float? = null): List<Route> {
        val candidates = holds.filter { wall?.matches(it.hsv) != true }
        val chromatic = candidates.filter { it.sat >= Tuning.MIN_ROUTE_SATURATION && it.value >= Tuning.MIN_ROUTE_VALUE }
        val achromatic = candidates.filter { it !in chromatic }

        val groups = ArrayList<List<Hold>>()
        val clusters = hueClusters(chromatic).map { it.toMutableList() }
        groups.addAll(clusters)
        val attached = attachPale(clusters, achromatic, wall)
        val pale = paleRoutes(achromatic.filter { it !in attached }, wall)
        groups.addAll(pale)
        val anchored = anchorToStarts(clusters + pale, achromatic.filter { it !in attached && pale.none { p -> it in p } })
        val rest = achromatic.filter { it !in attached && it !in anchored && pale.none { p -> it in p } }
        val black = rest.filter { it.value < BLACK_BELOW }
        val white = rest.filter { it.value >= WHITE_FROM }
        val gray = rest.filter { it !in black && it !in white }
        if (black.isNotEmpty()) groups.add(black)
        if (white.isNotEmpty()) groups.add(white)
        if (gray.isNotEmpty()) groups.add(gray)

        val tall = wallHeight != null && wallHeight >= Tuning.DOWN_CLIMB_MIN_WALL_HEIGHT
        val routes = ArrayList<Route>()
        for (colorGroup in groups) {
            val kind = if (colorGroup === gray && tall) RouteKind.DOWN_CLIMB else RouteKind.ROUTE
            val name = when {
                colorGroup === black -> "Black"
                colorGroup === white -> "White"
                colorGroup === gray -> if (kind == RouteKind.DOWN_CLIMB) DOWN_CLIMB_NAME else "Gray"
                pale.any { it === colorGroup } -> ColorUtil.hueName(meanHue(colorGroup))
                else -> ColorUtil.hueName(meanHue(colorGroup.filter { it in chromatic }))
            }
            // The down-climb holds are one group however far apart they stand; circuit problems split by position.
            val problems = if (splitByPosition && kind == RouteKind.ROUTE) connectedProblems(colorGroup) else listOf(colorGroup)
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
                route.kind = kind
                members.forEach { it.routeId = id; it.routeIds.add(id) }
                routes.add(route)
            }
        }
        disambiguateNames(routes)
        return routes
    }

    /**
     * Adds pale holds (saturation under [Tuning.MIN_ROUTE_SATURATION]) to the hue cluster whose hue range is
     * nearest, within [Tuning.PALE_HUE_TOL]; returns the holds added. Warm gym light washes greens and purples
     * out to 0.2–0.3 saturation, which left them in Gray. Only clusters of at least [Tuning.MIN_ROUTE_HOLDS]
     * saturated holds take them, so a pale hold never starts a route (a lower route threshold only added noise
     * routes). A hold stays out below [Tuning.PALE_MIN_SAT] or [Tuning.PALE_MIN_CHROMA] (dark holds: hue is
     * noise), in the white band (pale routes are white), or within [Tuning.PALE_WALL_HUE_TOL] of a coloured
     * wall's hue.
     */
    private fun attachPale(clusters: List<MutableList<Hold>>, holds: List<Hold>, wall: WallColor?): Set<Hold> {
        // Ranges are taken before any hold is added; clusters are in ascending circular hue order.
        val targets = clusters.filter { it.size >= Tuning.MIN_ROUTE_HOLDS }.map { Triple(it, it.first().hue, it.last().hue) }
        val wallHue = wall?.takeIf { it.sat >= Tuning.WALL_GREY_SAT }?.hue
        val attached = HashSet<Hold>()
        for (hold in holds) {
            if (hold.sat < Tuning.PALE_MIN_SAT || hold.sat * hold.value < Tuning.PALE_MIN_CHROMA || hold.value >= WHITE_FROM) continue
            if (wallHue != null && ColorUtil.hueDistance(hold.hue, wallHue) <= Tuning.PALE_WALL_HUE_TOL) continue
            val (cluster, distance) = targets.map { (c, lo, hi) -> c to distanceToRange(hold.hue, lo, hi) }.minByOrNull { it.second } ?: continue
            if (distance > Tuning.PALE_HUE_TOL) continue
            cluster.add(hold)
            attached.add(hold)
        }
        return attached
    }

    /**
     * Routes made of pale holds alone: the [holds] left by [attachPale] that show a hue (saturation from
     * [Tuning.PALE_MIN_SAT], saturation × value from [Tuning.PALE_ROUTE_MIN_CHROMA], under the white band, not
     * the wall's hue) are clustered by hue like the saturated ones, and a cluster of [Tuning.PALE_ROUTE_MIN_HOLDS]
     * is a route. A dusty purple route under warm light reads at 0.11–0.30 saturation with no saturated purple
     * anywhere to join (noborock 1F, 2026-11-05, #27); fewer agreeing holds are grey holds whose hue is noise.
     */
    private fun paleRoutes(holds: List<Hold>, wall: WallColor?): List<MutableList<Hold>> {
        val wallHue = wall?.takeIf { it.sat >= Tuning.WALL_GREY_SAT }?.hue
        val hued = holds.filter { hold ->
            hold.sat >= Tuning.PALE_MIN_SAT && hold.sat * hold.value >= Tuning.PALE_ROUTE_MIN_CHROMA && hold.value < WHITE_FROM &&
                (wallHue == null || ColorUtil.hueDistance(hold.hue, wallHue) > Tuning.PALE_WALL_HUE_TOL)
        }
        return hueClusters(hued).filter { it.size >= Tuning.PALE_ROUTE_MIN_HOLDS }.map { it.toMutableList() }
    }

    /**
     * Below a start hold there are, in most cases, foot holds of the same colour for its route (the climber's
     * rule, #27). The pale [holds] under a tape-marked start hold of a hue [group] (lower on the wall, within
     * [Tuning.START_REACH] to either side, hue within [Tuning.PALE_HUE_TOL] of the start hold's, not black)
     * join that group whatever their saturation; returns the holds added.
     */
    private fun anchorToStarts(groups: List<MutableList<Hold>>, holds: List<Hold>): Set<Hold> {
        val anchored = HashSet<Hold>()
        for (group in groups) for (start in group.filter { it.role == HoldRole.START }) {
            for (hold in holds) {
                if (hold in anchored || hold.value < BLACK_BELOW) continue
                if (hold.v >= start.v || kotlin.math.abs(hold.u - start.u) > Tuning.START_REACH) continue
                if (ColorUtil.hueDistance(hold.hue, start.hue) > Tuning.PALE_HUE_TOL) continue
                group.add(hold); anchored.add(hold)
            }
        }
        return anchored
    }

    /** 0 inside the circular hue range [lo]..[hi], otherwise the distance to its nearer end. */
    private fun distanceToRange(hue: Float, lo: Float, hi: Float): Float =
        if (circularGap(lo, hue) <= circularGap(lo, hi)) 0f else minOf(circularGap(hue, lo), circularGap(hi, hue))

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

    /**
     * Sorts by hue, starts after the largest circular gap and splits where consecutive hues differ by more
     * than [Tuning.HUE_GAP_DEG]. A cluster that still spans more than [Tuning.MAX_ROUTE_HUE_SPAN] (hues chained
     * through intermediate holds, e.g. red to orange) splits at its largest internal gap until none does.
     */
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
        return clusters.flatMap { splitWideCluster(it) }
    }

    /** [cluster] is in ascending circular hue order from its first element. */
    private fun splitWideCluster(cluster: List<Hold>): List<List<Hold>> {
        if (cluster.size < 2 || circularGap(cluster.first().hue, cluster.last().hue) <= Tuning.MAX_ROUTE_HUE_SPAN) return listOf(cluster)
        var cut = 1
        var widest = -1f
        for (i in 1 until cluster.size) {
            val gap = circularGap(cluster[i - 1].hue, cluster[i].hue)
            if (gap > widest) { widest = gap; cut = i }
        }
        return splitWideCluster(cluster.subList(0, cut)) + splitWideCluster(cluster.subList(cut, cluster.size))
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
