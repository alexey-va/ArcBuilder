package ru.arc.util

import net.kyori.adventure.text.Component

object TextUtil {
    @JvmStatic fun strip(component: Component?): Component? = TextUtils.strip(component)
    @JvmStatic fun toLegacy(miniMessage: String, vararg replacements: String): String =
        TextUtils.toLegacy(miniMessage, *replacements)
}
