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
 * hold are its route's foot holds (see [anchorToStarts]); a pale route also takes the leftover holds of about its
 * hue (see [joinPaleRoutes]). On a wall at least [Tuning.DOWN_CLIMB_MIN_WALL_HEIGHT] high the grey hand-sized jugs
 * of the upper wall are the down-climb holds ([RouteKind.DOWN_CLIMB], see [isDownClimbHold]), not a route; the dark
 * grey holds that are not are Black.
 * With [splitByPosition] (circuit walls) each colour group is further split into spatially connected problems.
 * Holds the colour of the [wall] (depth streaks at mats and edges, or wall seen through a sparse hold) stay
 * unassigned instead of bridging unrelated hues into one route.
 */
object RouteGrouper {
    private const val BLACK_BELOW = 0.25f
    private const val WHITE_FROM = 0.65f
    const val DOWN_CLIMB_NAME = "Down climb"

    /** [wallHeight] is the scanned wall's height in metres and [wallBottom] its bottom edge's v (the extent), when known. */
    fun group(holds: List<Hold>, splitByPosition: Boolean = false, wall: WallColor? = null, wallHeight: Float? = null, wallBottom: Float? = null): List<Route> {
        val candidates = holds.filter { wall?.matches(it.hsv) != true }
        val chromatic = candidates.filter { it.sat >= Tuning.MIN_ROUTE_SATURATION && it.value >= Tuning.MIN_ROUTE_VALUE }
        val achromatic = candidates.filter { it !in chromatic }

        val clusters = hueClusters(chromatic).map { it.toMutableList() }
        val attached = attachPale(clusters, achromatic, wall)
        val loose = achromatic.filter { it !in attached }
        val pale = paleRoutes(loose, wall)
        val joined = joinPaleRoutes(clusters, pale, loose.filter { h -> pale.none { h in it } }, wall)
        val leftover = loose.filter { h -> h !in joined && pale.none { h in it } }
        val anchored = anchorToStarts(clusters + pale, leftover)
        val rest = leftover.filter { it !in anchored }
        val black = rest.filter { it.value < BLACK_BELOW }.toMutableList()
        val white = rest.filter { it.value >= WHITE_FROM }
        val greys = rest.filter { it.value >= BLACK_BELOW && it.value < WHITE_FROM }
        val tall = wallHeight != null && wallHeight >= Tuning.DOWN_CLIMB_MIN_WALL_HEIGHT
        // On a tall wall the grey jugs are the down-climb set and the grey holds that are not jugs are black when dark
        // (the climber's labels on the Nov 5 walls); on a shorter wall the grey holds, dark ones included, are a Gray
        // route (the Oct 23 references label 0.27–0.33 holds Gray), so the dark cut applies to tall walls only.
        val (downClimb, others) = if (tall) greys.partition { isDownClimbHold(it, wallHeight!!, wallBottom) } else emptyList<Hold>() to greys
        val (dark, gray) = if (tall) others.partition { it.value < Tuning.DOWN_CLIMB_DARK_VALUE } else emptyList<Hold>() to others
        black += dark

        class Group(val holds: List<Hold>, val name: String, val kind: RouteKind = RouteKind.ROUTE)
        val groups = ArrayList<Group>()
        clusters.forEach { groups.add(Group(it, ColorUtil.hueName(meanHue(it.filter { h -> h in chromatic })))) }
        pale.forEach { groups.add(Group(it, ColorUtil.hueName(meanHue(it)))) }
        if (black.isNotEmpty()) groups.add(Group(black, "Black"))
        if (white.isNotEmpty()) groups.add(Group(white, "White"))
        if (gray.isNotEmpty()) groups.add(Group(gray, "Gray"))
        if (downClimb.isNotEmpty()) groups.add(Group(downClimb, DOWN_CLIMB_NAME, RouteKind.DOWN_CLIMB))

        val routes = ArrayList<Route>()
        for (group in groups) {
            // The down-climb holds are one group however far apart they stand; circuit problems split by position.
            val problems = if (splitByPosition && group.kind == RouteKind.ROUTE) connectedProblems(group.holds) else listOf(group.holds)
            for (members in problems) {
                // A route needs MIN_ROUTE_HOLDS; the down-climb group is a bucket and keeps even a lone hold
                if (members.size < Tuning.MIN_ROUTE_HOLDS && group.kind == RouteKind.ROUTE) continue
                val id = routes.size + 1
                val route = Route(
                    id, group.name,
                    members.sumOf { it.r } / members.size,
                    members.sumOf { it.g } / members.size,
                    members.sumOf { it.b } / members.size,
                    members.map { it.id }.toMutableList(),
                )
                route.kind = group.kind
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
        val targets = hueRanges(clusters.filter { it.size >= Tuning.MIN_ROUTE_HOLDS })
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
     * A grey hold of a tall wall is a down-climb hold when it is a hand-sized jug on the upper wall: not a START
     * hold, at least [Tuning.DOWN_CLIMB_MIN_VALUE] light, no side over [Tuning.DOWN_CLIMB_MAX_SIDE] or under
     * [Tuning.DOWN_CLIMB_MIN_SIDE], and, when [wallBottom] is known, in the upper [Tuning.DOWN_CLIMB_TOP_SHARE] of
     * the wall (the climber's rules, #27).
     */
    private fun isDownClimbHold(hold: Hold, wallHeight: Float, wallBottom: Float?): Boolean {
        if (hold.role == HoldRole.START || hold.value < Tuning.DOWN_CLIMB_MIN_VALUE) return false
        if (maxOf(hold.width, hold.tall) > Tuning.DOWN_CLIMB_MAX_SIDE || minOf(hold.width, hold.tall) < Tuning.DOWN_CLIMB_MIN_SIDE) return false
        return wallBottom == null || hold.v >= wallBottom + wallHeight * (1f - Tuning.DOWN_CLIMB_TOP_SHARE)
    }

    /**
     * A route made of pale holds ([paleRoutes]) is itself dusty, so its stragglers are too: the leftover [holds]
     * of about its hue (within [Tuning.PALE_ROUTE_JOIN_TOL] of its range, and nearer to it than to any saturated
     * cluster) from [Tuning.PALE_ROUTE_JOIN_SAT] saturation, not black, not white, not the wall's hue, join it;
     * returns the holds added. No chroma floor: the 1F purples the climber labelled sit at 0.04–0.08, under
     * both floors above, and the pale route itself vouches for the hue. Saturated routes keep the stricter
     * [attachPale]: beside them a hold this pale is a grey hold whose hue is noise (the Oct 23 references).
     */
    private fun joinPaleRoutes(clusters: List<MutableList<Hold>>, pale: List<MutableList<Hold>>, holds: List<Hold>, wall: WallColor?): Set<Hold> {
        val joined = HashSet<Hold>()
        if (pale.isEmpty()) return joined
        val targets = hueRanges(pale + clusters) // ranges before any hold is added
        val wallHue = wall?.takeIf { it.sat >= Tuning.WALL_GREY_SAT }?.hue
        for (hold in holds) {
            if (hold.sat < Tuning.PALE_ROUTE_JOIN_SAT || hold.value < BLACK_BELOW || hold.value >= WHITE_FROM) continue
            if (wallHue != null && ColorUtil.hueDistance(hold.hue, wallHue) <= Tuning.PALE_WALL_HUE_TOL) continue
            val (route, distance) = targets.map { (r, lo, hi) -> r to distanceToRange(hold.hue, lo, hi) }.minByOrNull { it.second }!!
            if (route in pale && distance <= Tuning.PALE_ROUTE_JOIN_TOL) { route.add(hold); joined.add(hold) }
        }
        return joined
    }

    /** Each group with its circular hue range: [hueClusters] returns members in ascending circular order from the first. */
    private fun hueRanges(groups: List<MutableList<Hold>>): List<Triple<MutableList<Hold>, Float, Float>> =
        groups.map { Triple(it, it.first().hue, it.last().hue) }

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
