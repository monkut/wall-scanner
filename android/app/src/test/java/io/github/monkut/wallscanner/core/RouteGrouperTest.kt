package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hold colours (hue, saturation, value) taken from the noborock captures of 2026-10-23 and 2026-10-29. */
class RouteGrouperTest {
    private fun hold(id: Int, hue: Float, sat: Float, value: Float): Hold {
        fun channel(n: Int): Int {
            val k = (n + hue / 60f) % 6f
            return Math.round(255f * (value - value * sat * maxOf(0f, minOf(k, 4f - k, 1f))))
        }
        return Hold(id, 0f, 0f, 0f, id * 0.1f, 0f, 0.1f, 0.1f, 0.03f, channel(5), channel(3), channel(1), 100)
    }

    private fun routeOf(routes: List<Route>, id: Int): String = routes.firstOrNull { id in it.holdIds }?.name ?: "-"

    @Test
    fun paleGreensJoinTheGreenRoute() {
        val holds = listOf(
            hold(1, 87f, 0.38f, 0.45f), hold(2, 93f, 0.38f, 0.49f), hold(3, 118f, 0.41f, 0.56f), // saturated greens
            hold(4, 123f, 0.27f, 0.62f), hold(5, 104f, 0.29f, 0.44f), // washed-out greens, formerly Gray
        )
        val routes = RouteGrouper.group(holds)
        assertEquals(listOf("Green"), routes.map { it.name })
        assertEquals(listOf(1, 2, 3, 4, 5), routes[0].holdIds.sorted())
    }

    @Test
    fun paleHoldsNeverStartARoute() {
        val holds = listOf(hold(1, 87f, 0.38f, 0.45f), hold(2, 87f, 0.43f, 0.55f), hold(3, 274f, 0.24f, 0.52f), hold(4, 286f, 0.24f, 0.50f))
        val routes = RouteGrouper.group(holds)
        assertEquals("pale purples with no saturated purple stay grey", "Gray", routeOf(routes, 3))
        assertEquals("Gray", routeOf(routes, 4))
    }

    @Test
    fun darkWhiteAndDistantHoldsStayOut() {
        val holds = listOf(
            hold(1, 188f, 0.72f, 0.71f), hold(2, 191f, 0.61f, 0.75f), // cyan route
            hold(3, 205f, 0.22f, 0.22f), // black hold: hue is noise at this chroma
            hold(4, 190f, 0.27f, 0.70f), // white-band hold of a pale route
            hold(5, 230f, 0.25f, 0.55f), // pale hold 39° beyond the cyan range
        )
        val routes = RouteGrouper.group(holds)
        assertEquals(listOf(1, 2), routes.first { it.name == "Cyan" }.holdIds.sorted())
        assertEquals("-", routeOf(routes, 3)) // a lone black hold is no route
        assertEquals("-", routeOf(routes, 4)) // nor a lone white one
        assertEquals("-", routeOf(routes, 5))
    }

    @Test
    fun paleHoldsNearTheWallHueStayOut() {
        val holds = listOf(hold(1, 18f, 0.80f, 0.79f), hold(2, 20f, 0.75f, 0.63f), hold(3, 33f, 0.23f, 0.50f))
        val plywood = WallColor(146, 130, 112, hue = 35f, sat = 0.30f, satTol = 0.05f, valLo = 0.9f, valHi = 0.95f)
        assertEquals("Orange", routeOf(RouteGrouper.group(holds), 3))
        assertEquals("-", routeOf(RouteGrouper.group(holds, wall = plywood), 3))
    }
}

/** The pale-route and start-anchor rules (#27), with colours from noborock 1F, 2026-11-05. */
class RouteGrouperPaleTest {
    private fun hold(id: Int, hue: Float, sat: Float, value: Float, u: Float = id * 0.1f, v: Float = 0f): Hold {
        fun channel(n: Int): Int {
            val k = (n + hue / 60f) % 6f
            return Math.round(255f * (value - value * sat * maxOf(0f, minOf(k, 4f - k, 1f))))
        }
        return Hold(id, 0f, 0f, 0f, u, v, 0.1f, 0.1f, 0.03f, channel(5), channel(3), channel(1), 100)
    }

    private fun routeOf(routes: List<Route>, id: Int): String = routes.firstOrNull { id in it.holdIds }?.name ?: "-"

    @Test
    fun dustyPurplesWithNoSaturatedPurpleAreARoute() {
        // The twelve purple holds of noborock 1F as the phone measured them (id, hue, sat, value), no saturated purple on the wall
        val purples = listOf(
            Triple(5, 249f, 0.30f to 0.34f), Triple(14, 256f, 0.26f to 0.33f), Triple(24, 245f, 0.26f to 0.35f), Triple(29, 242f, 0.26f to 0.45f),
            Triple(31, 261f, 0.22f to 0.30f), Triple(42, 267f, 0.13f to 0.33f), Triple(43, 244f, 0.27f to 0.44f), Triple(45, 256f, 0.21f to 0.41f),
            Triple(55, 261f, 0.23f to 0.34f), Triple(56, 260f, 0.26f to 0.41f), Triple(59, 252f, 0.21f to 0.46f), Triple(61, 283f, 0.27f to 0.47f),
        ).map { (id, h, sv) -> hold(id, h, sv.first, sv.second) }
        val greys = listOf(hold(65, 213f, 0.07f, 0.50f), hold(71, 209f, 0.16f, 0.52f), hold(72, 202f, 0.15f, 0.51f), hold(76, 206f, 0.11f, 0.52f)) // down-climb holds
        val cyan = listOf(hold(30, 184f, 0.64f, 0.54f), hold(31, 187f, 0.61f, 0.64f)).map { hold(it.id + 70, it.hue, it.sat, it.value) }
        val routes = RouteGrouper.group(purples + greys + cyan)
        val purple = routes.first { it.name == "Purple" }
        // Under the chroma floor: #31 #42 #55; #61 at 283° splits off by the hue-span rule. The rest is the route.
        assertEquals(listOf(5, 14, 24, 29, 43, 45, 56, 59), purple.holdIds.sorted())
        for (g in greys) assertEquals("Gray", routeOf(routes, g.id))
        assertEquals("Cyan", routeOf(routes, 100))
    }

    @Test
    fun aFewPaleHoldsAreNotARoute() {
        // Four grey holds of the Oct 23 2:04 PM wall that read purplish under the gym light (reference route Gray)
        val greys = listOf(hold(3, 231f, 0.26f, 0.47f), hold(4, 243f, 0.21f, 0.39f), hold(5, 246f, 0.22f, 0.38f), hold(6, 258f, 0.28f, 0.28f))
        val routes = RouteGrouper.group(listOf(hold(1, 87f, 0.38f, 0.45f), hold(2, 87f, 0.43f, 0.55f)) + greys)
        for (g in greys) assertEquals("Gray", routeOf(routes, g.id))
    }

    @Test
    fun paleHoldsUnderAStartHoldAreItsFootHolds() {
        val start = hold(1, 347f, 0.55f, 0.64f, u = 1.0f, v = 0.0f).also { it.role = HoldRole.START }
        val red = hold(2, 350f, 0.57f, 0.62f, u = 1.3f, v = 1.2f)
        val footBelow = hold(3, 336f, 0.18f, 0.59f, u = 0.8f, v = -0.5f) // pale pink, 0.2 m to the side, below
        val footFar = hold(4, 336f, 0.18f, 0.59f, u = 2.2f, v = -0.5f) // 1.2 m to the side
        val above = hold(5, 336f, 0.18f, 0.59f, u = 1.0f, v = 0.4f)
        val routes = RouteGrouper.group(listOf(start, red, footBelow, footFar, above))
        assertEquals("Red", routeOf(routes, 3))
        assertEquals("Gray", routeOf(routes, 4)) // too far to the side: left with the greys
        assertEquals("Gray", routeOf(routes, 5)) // above the start hold
        start.role = HoldRole.ANY
        assertEquals("-", routeOf(RouteGrouper.group(listOf(start, red, footBelow)), 3))
    }
}
