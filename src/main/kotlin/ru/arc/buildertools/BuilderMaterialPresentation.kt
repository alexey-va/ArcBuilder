package ru.arc.buildertools

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Locale

/** Keeps internal Bukkit material identifiers away from player-facing surfaces. */
internal object BuilderMaterialPresentation {
    fun label(player: Player, material: Material): Component = label(
        material = material,
        localeTag = player.locale().toLanguageTag(),
        russianTranslation = { null },
    )

    internal fun label(
        material: Material,
        localeTag: String,
        russianTranslation: (Material) -> String?,
    ): Component {
        val language = Locale.forLanguageTag(localeTag.replace('_', '-')).language
        if (language != "ru") return Component.translatable(material.translationKey())
        val translated = russianTranslation(material)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: readableName(material)
        return Component.text(translated)
    }

    internal fun readableName(material: Material): String = material.name
        .lowercase(Locale.ROOT)
        .split('_')
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
}
