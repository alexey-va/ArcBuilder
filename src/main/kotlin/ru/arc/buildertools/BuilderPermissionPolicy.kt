package ru.arc.buildertools

/**
 * One canonical permission contract for every builder-tools entry point.
 */
internal enum class BuilderFeature(
    val canonicalPermission: String,
) {
    FILL("arcbuild.fill"),
    COPY("arcbuild.copy"),
    PASTE("arcbuild.paste"),
    DECONSTRUCT("arcbuild.deconstruct"),
    CROWN("arcbuild.crown"),
}

internal object BuilderPermissionPolicy {
    private const val umbrellaPermission = "arcbuild.use"
    private val bookEntryPermissions = setOf(
        "arcbuild.book.create",
        "arcbuild.book.sell",
        "arcbuild.book.use",
    )
    private val sizeTiers = listOf(100, 80, 60, 40, 20)
    private val hourlyTiers = listOf(200_000, 150_000, 100_000, 50_000, 20_000)

    fun canUseAny(hasPermission: (String) -> Boolean): Boolean =
        hasPermission(umbrellaPermission) ||
            BuilderFeature.entries.any { feature -> canUse(feature, hasPermission) } ||
            bookEntryPermissions.any(hasPermission)

    fun canUse(feature: BuilderFeature, hasPermission: (String) -> Boolean): Boolean =
        hasPermission(umbrellaPermission) || hasPermission(feature.canonicalPermission)

    fun maximumAxis(hasPermission: (String) -> Boolean, absoluteMaximum: Int): Int {
        val tier = sizeTiers.firstOrNull { size ->
            hasPermission("arcbuild.selection.size.$size")
        } ?: 20
        return minOf(tier, absoluteMaximum)
    }

    fun hourlyChanges(hasPermission: (String) -> Boolean, baseLimit: Int): Int =
        hourlyTiers.firstOrNull { limit ->
            hasPermission("arcbuild.hourly.$limit")
        } ?: baseLimit
}
