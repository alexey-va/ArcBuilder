package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Locale

class BuilderLocalePolicyTest : FunSpec({
    afterEach {
        BuilderLocalePolicy.configure(defaultLocaleTag = "ru", followClientLocale = false)
    }

    test("fixed Russian locale overrides an English client for messages and material names") {
        val player = mockk<Player> { every { locale() } returns Locale.US }
        BuilderLocalePolicy.configure(defaultLocaleTag = "ru", followClientLocale = false)

        BuilderLocalePolicy.localeTag(player) shouldBe "ru"
        BuilderLocalePolicy.localeTag(null) shouldBe "ru"
        val label = BuilderMaterialPresentation.label(player, Material.OAK_PLANKS)
        PlainTextComponentSerializer.plainText().serialize(label) shouldBe
            checkNotNull(BuilderMaterialArguments.russianLabel(Material.OAK_PLANKS))
    }

    test("client locale mode keeps the configured default for non-player senders") {
        val player = mockk<Player> { every { locale() } returns Locale.US }
        BuilderLocalePolicy.configure(defaultLocaleTag = "ru", followClientLocale = true)

        BuilderLocalePolicy.localeTag(player) shouldBe "en-US"
        BuilderLocalePolicy.localeTag(null) shouldBe "ru"
        (BuilderMaterialPresentation.label(player, Material.OAK_PLANKS) as TranslatableComponent).key() shouldBe
            Material.OAK_PLANKS.translationKey()
    }

    test("the configured fixed locale can be changed independently of client locale") {
        val player = mockk<Player> { every { locale() } returns Locale.US }
        BuilderLocalePolicy.configure(defaultLocaleTag = "en-US", followClientLocale = false)

        BuilderLocalePolicy.localeTag(player) shouldBe "en-US"
        BuilderLocalePolicy.fixedLocaleTagOrNull() shouldBe "en-US"
        PlainTextComponentSerializer.plainText().serialize(
            BuilderMaterialPresentation.label(player, Material.OAK_PLANKS),
        ) shouldBe "Oak Planks"
    }
})
