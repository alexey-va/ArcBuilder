package ru.arc.buildertools

/**
 * One canonical permission contract for every builder-tools entry point.
 */
internal enum class BuilderFeature(
    val canonicalPermission: String,
) {
    FILL("arc.builder.tools.fill"),
    COPY("arc.builder.tools.copy"),
    PASTE("arc.builder.tools.paste"),
    DECONSTRUCT("arc.builder.tools.deconstruct"),
    CROWN("arc.builder.tools.crown"),
}

internal object BuilderPermissionPolicy {
    private const val umbrellaPermission = "arc.builder.tools.use"
    private val bookEntryPermissions = setOf(
        "arc.build.book.create",
        "arc.build.book.sell",
        "arc.build.book.use",
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
            hasPermission("arc.builder.tools.selection.size.$size")
        } ?: 20
        return minOf(tier, absoluteMaximum)
    }

    fun hourlyChanges(hasPermission: (String) -> Boolean, baseLimit: Int): Int =
        hourlyTiers.firstOrNull { limit ->
            hasPermission("arc.builder.tools.hourly.$limit")
        } ?: baseLimit
}
