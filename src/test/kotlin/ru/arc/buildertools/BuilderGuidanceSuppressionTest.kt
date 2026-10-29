package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.plugin.Plugin
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

class BuilderGuidanceSuppressionTest : StringSpec({
    "active surfaces renew separately, migrate providers and release their own claims" {
        val plugin = mockk<Plugin>()
        val first = mockk<QuestGuidanceHudService>(relaxed = true)
        val second = mockk<QuestGuidanceHudService>(relaxed = true)
        var provider: QuestGuidanceHudService? = first
        val player = UUID.randomUUID()
        val book = BuilderGuidanceSuppression(plugin, "book-preview") { provider }
        val selection = BuilderGuidanceSuppression(plugin, "selection-panel") { provider }

        book.sync(setOf(player))
        book.sync(setOf(player))
        selection.sync(setOf(player))
        verify(exactly = 2) { first.setSuppressed(plugin, player, "book-preview", true) }
        book.clear(player)
        verify(exactly = 1) { first.setSuppressed(plugin, player, "book-preview", false) }
        verify(exactly = 0) { first.setSuppressed(plugin, player, "selection-panel", false) }

        provider = second
        selection.sync(setOf(player))
        verify(exactly = 1) { first.setSuppressed(plugin, player, "selection-panel", false) }
        verify(exactly = 1) { second.setSuppressed(plugin, player, "selection-panel", true) }
        selection.close()
        selection.sync(setOf(player))
        verify(exactly = 1) { second.setSuppressed(plugin, player, "selection-panel", false) }
        verify(exactly = 1) { second.setSuppressed(plugin, player, "selection-panel", true) }
        book.close()
    }
})
