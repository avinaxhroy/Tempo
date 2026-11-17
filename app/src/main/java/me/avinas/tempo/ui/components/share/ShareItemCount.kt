package me.avinas.tempo.ui.components.share

/**
 * How many entries a share card prints.
 *
 * The statistics sheet offered 3/5/10 and the spotlight story's closing slide now
 * offers the same three, so the set lives once. Two enums with the same members
 * would be two places to add "top 25" to, and the day they disagree is the day
 * the app prints five artists on one surface and ten on another for the same
 * period.
 */
enum class ShareItemCount(
    val count: Int,
) {
    TOP_3(3),
    TOP_5(5),
    TOP_10(10),
}
