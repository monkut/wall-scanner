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
