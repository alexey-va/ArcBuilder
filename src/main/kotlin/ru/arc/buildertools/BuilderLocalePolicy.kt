package ru.arc.buildertools

import org.bukkit.entity.Player

/** One atomic locale choice shared by every player-facing builder surface. */
internal object BuilderLocalePolicy {
    private data class Settings(
        val defaultLocaleTag: String,
        val followClientLocale: Boolean,
    )

    @Volatile
    private var settings = Settings(defaultLocaleTag = "ru", followClientLocale = false)

    fun configure(defaultLocaleTag: String, followClientLocale: Boolean) {
        require(defaultLocaleTag.isNotBlank()) { "Builder default locale cannot be blank" }
        settings = Settings(defaultLocaleTag, followClientLocale)
    }

    fun localeTag(player: Player?): String {
        val current = settings
        val clientLocale = player?.takeIf { current.followClientLocale }?.locale()?.toLanguageTag()
        return current.resolve(clientLocale)
    }

    /** Null means material names should use the client's native translation component. */
    fun fixedLocaleTagOrNull(): String? = settings.takeUnless { it.followClientLocale }?.defaultLocaleTag

    private fun Settings.resolve(clientLocaleTag: String?): String =
        if (followClientLocale && clientLocaleTag != null) clientLocaleTag else defaultLocaleTag
}
