package io.github.monkut.wallscanner.core

/**
 * How a gym marks which holds belong to a route. Chosen at New scan, stored with the capture and used by
 * post-processing to decide how routes are detected (see ARCHITECTURE.md "Route definition types").
 */
enum class RouteDefinition(
    val id: String,
    val label: String,
    val description: String,
    /** Hold colour identifies the route, so colour grouping is a valid first pass. */
    val colorIsRoute: Boolean,
    /** Several problems share one colour on a wall, so colour groups must be split by position. */
    val splitByPosition: Boolean,
    /** Natural rock: no flat wall, so holds are measured against the local rock surface and lines are marked by hand. */
    val outdoor: Boolean = false,
) {
    MONO_COLOR("mono_color", "Hold colour", "One hold colour = one route; start tag at the base", true, false),
    COLOR_TAPED("color_taped", "Hold colour, taped start and finish", "One hold colour = one route; start and finish holds marked with tape", true, false),
    TAPE("tape", "Tape", "Tape beside each hold; tape colour = route, holds mixed", false, false),
    CIRCUIT("circuit", "Circuit", "Hold colour = grade band; several problems per colour", true, true),
    TAGGED("tagged", "Tagged", "Placard (number, grade) at the start; holds by colour", true, false),
    COMP("comp", "Competition", "Taped start, zone and top holds", false, false),
    FREE("free", "Free / spray wall", "No set routes; problems are defined by hand", false, false),
    BOARD("board", "System board", "LED board with a fixed layout", false, false),
    OUTDOOR("outdoor", "Outdoor rock", "Boulder or route on natural rock; no colour or tape, the line is marked by hand", false, false, outdoor = true);

    companion object {
        val DEFAULT = MONO_COLOR
        fun fromId(id: String?): RouteDefinition = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class FeetRule(val id: String, val label: String) {
    FOLLOW("follow", "Feet follow hands"),
    OPEN("open", "Open feet"),
    CHIPS("chips", "Foot chips only");

    companion object {
        fun fromId(id: String?): FeetRule = entries.firstOrNull { it.id == id } ?: FOLLOW
    }
}

/** Role of a hold within a route. */
enum class HoldRole(val id: String) {
    ANY("any"), START("start"), HAND("hand"), FOOT("foot"), ZONE("zone"), FINISH("finish");

    companion object {
        fun fromId(id: String?): HoldRole = entries.firstOrNull { it.id == id } ?: ANY
    }
}

class Rules(val feet: FeetRule = FeetRule.FOLLOW, val volumesOn: Boolean = true)

class Definition(val type: RouteDefinition = RouteDefinition.DEFAULT, val rules: Rules = Rules())
