package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.SoundGroup
import org.bukkit.World
import org.bukkit.block.data.BlockData

class BuilderConstructionFeedbackTest : FunSpec({
    val settings = BuilderConstructionFeedbackSettings(
        enabled = true,
        intervalBlocks = 4,
        soundsEnabled = true,
        soundVolume = 0.55f,
        soundPitch = 1.0f,
        particlesEnabled = true,
        particleCount = 3,
        particleSpread = 0.2,
    )

    fun data(material: Material, place: Sound, breakSound: Sound): BlockData {
        val group = mockk<SoundGroup>()
        every { group.placeSound } returns place
        every { group.breakSound } returns breakSound
        return mockk<BlockData>().also { blockData ->
            every { blockData.material } returns material
            every { blockData.soundGroup } returns group
        }
    }

    test("feedback samples placement sound and block particles at the configured cadence") {
        val world = mockk<World>(relaxed = true)
        val before = data(Material.AIR, Sound.BLOCK_STONE_PLACE, Sound.BLOCK_STONE_BREAK)
        val after = data(Material.STONE, Sound.BLOCK_STONE_PLACE, Sound.BLOCK_STONE_BREAK)
        val location = Location(world, 3.0, 64.0, 5.0)

        BuilderConstructionFeedback.play(world, location, before, after, cursor = 1, settings)
        verify(exactly = 0) { world.playSound(any<Location>(), any<Sound>(), any<SoundCategory>(), any(), any()) }

        BuilderConstructionFeedback.play(world, location, before, after, cursor = 4, settings)
        verify(exactly = 1) {
            world.playSound(any<Location>(), Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.55f, 1.0f)
        }
        verify(exactly = 1) {
            world.spawnParticle(
                Particle.BLOCK,
                any<Location>(),
                3,
                0.2,
                0.2,
                0.2,
                0.03,
                after,
            )
        }
    }

    test("removal uses the old block break sound and can disable particles independently") {
        val world = mockk<World>(relaxed = true)
        val before = data(Material.ANDESITE, Sound.BLOCK_STONE_PLACE, Sound.BLOCK_STONE_BREAK)
        val after = data(Material.AIR, Sound.BLOCK_STONE_PLACE, Sound.BLOCK_STONE_BREAK)

        BuilderConstructionFeedback.play(
            world,
            Location(world, 0.0, 70.0, 0.0),
            before,
            after,
            cursor = 0,
            settings.copy(particlesEnabled = false),
        )

        verify(exactly = 1) {
            world.playSound(any<Location>(), Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.55f, 1.0f)
        }
        verify(exactly = 0) {
            world.spawnParticle(
                any<Particle>(),
                any<Location>(),
                any<Int>(),
                any<Double>(),
                any<Double>(),
                any<Double>(),
                any<Double>(),
                any<BlockData>(),
            )
        }
    }
})
