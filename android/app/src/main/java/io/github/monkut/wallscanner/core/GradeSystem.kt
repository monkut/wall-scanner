package io.github.monkut.wallscanner.core

/** Grade scales a route can be graded in; the location sets the default, each route may override it. */
enum class GradeSystem(val id: String, val label: String, val values: List<String>) {
    YDS(
        "yds", "YDS",
        listOf("5.5", "5.6", "5.7", "5.8", "5.9") +
            (10..15).flatMap { n -> listOf("a", "b", "c", "d").map { "5.$n$it" } },
    ),
    /** Japanese gym scale: 10Q (easiest) … 1Q, then 1D (shodan) … 6D. */
    KYU_DAN(
        "kyu_dan", "Japanese (Q/D)",
        (10 downTo 1).map { "${it}Q" } + (1..6).map { "${it}D" },
    ),
    V_SCALE("v", "V-scale", listOf("VB") + (0..17).map { "V$it" }),
    FONT(
        "font", "Font",
        listOf("3", "4", "4+", "5", "5+") +
            (6..8).flatMap { n -> listOf("A", "A+", "B", "B+", "C", "C+").map { "$n$it" } } + listOf("9A"),
    );

    companion object {
        val DEFAULT = KYU_DAN
        fun fromId(id: String?): GradeSystem? = entries.firstOrNull { it.id == id }
    }
}
