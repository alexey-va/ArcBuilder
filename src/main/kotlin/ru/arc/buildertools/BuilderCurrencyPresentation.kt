package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

internal object BuilderCurrencyPresentation {
    const val COIN_GLYPH: String = "💰"

    fun amountWithCoin(amount: Component): Component = amount
        .append(Component.space())
        .append(Component.text(COIN_GLYPH, NamedTextColor.WHITE))
}
