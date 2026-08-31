package ru.arc.buildertools

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.block.data.BlockData

internal data class BuilderConstructionFeedbackSettings(
    val enabled: Boolean,
    val intervalBlocks: Int,
    val soundsEnabled: Boolean,
    val soundVolume: Float,
    val soundPitch: Float,
    val particlesEnabled: Boolean,
    val particleCount: Int,
    val particleSpread: Double,
)

internal object BuilderConstructionFeedback {
    fun play(
        world: World,
        blockLocation: Location,
        before: BlockData,
        after: BlockData,
        cursor: Int,
        settings: BuilderConstructionFeedbackSettings,
    ) {
        if (!settings.enabled || cursor % settings.intervalBlocks != 0) return
        val center = blockLocation.clone().add(0.5, 0.5, 0.5)
        if (settings.soundsEnabled) {
            val sound = if (after.material.isAir) before.soundGroup.breakSound else after.soundGroup.placeSound
            world.playSound(center, sound, SoundCategory.BLOCKS, settings.soundVolume, settings.soundPitch)
        }
        if (settings.particlesEnabled && settings.particleCount > 0) {
            val particleData = after.takeUnless { it.material.isAir } ?: before
            world.spawnParticle(
                Particle.BLOCK,
                center,
                settings.particleCount,
                settings.particleSpread,
                settings.particleSpread,
                settings.particleSpread,
                0.03,
                particleData,
            )
        }
    }
}
