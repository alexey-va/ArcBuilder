package ru.arc.buildertools

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import ru.arc.config.Config
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class BuilderPlaqueAppearance(
    val background: Color,
    val glowEnabled: Boolean = false,
    val glowColor: Color = Color.fromRGB(0x66D9FF),
    val glowMaterial: Material = Material.LIGHT_BLUE_STAINED_GLASS,
    val glowThickness: Float = .025f,
    val trail: BuilderPlaqueTrail = BuilderPlaqueTrail(),
) {
    companion object {
        const val PATH = "construction.site.panel"
        /** Only these exact visual leaves may bypass a domain reload barrier. */
        val RELOAD_PATHS = setOf(
            "background-color", "background-opacity", "glow.enabled", "glow.color", "glow.material", "glow.thickness",
            "border-trail.enabled", "border-trail.particle", "border-trail.color", "border-trail.size",
            "border-trail.speed", "border-trail.tail-length", "border-trail.spacing", "border-trail.padding",
            "border-trail.update-interval-ticks", "border-trail.view-range", "border-trail.max-viewers",
        ).mapTo(hashSetOf()) { "$PATH.$it" }

        fun read(config: Config): BuilderPlaqueAppearance {
            fun number(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double =
                config.double("$PATH.$key", default).also {
                    require(it.isFinite() && it in range) { "Builder plaque $key must be in $range" }
                }
            fun rgb(key: String, default: String): Color {
                val value = config.string("$PATH.$key", default)
                require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "Builder plaque $key must be #RRGGBB" }
                return Color.fromRGB(value.substring(1).toInt(16))
            }
            val background = config.string("$PATH.background-color", "#E6101418")
            require(Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(background)) {
                "Builder construction site panel background color must be #RRGGBB or #AARRGGBB"
            }
            val argb = background.substring(1).toLong(16).toInt().let {
                if (background.length == 7) it or (255 shl 24) else it
            }
            val alpha = ((argb ushr 24) * number("background-opacity", 1.0, 0.0..1.0)).roundToInt()
            val material = Material.matchMaterial(config.string("$PATH.glow.material", "LIGHT_BLUE_STAINED_GLASS"))
            // Paper's isBlock/isAir resolve native registries. Staged parsing must stay registry-free.
            require(material != null && material !in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)) {
                "Builder plaque glow.material must name a non-air block"
            }
            val particle = runCatching {
                Particle.valueOf(config.string("$PATH.border-trail.particle", "DUST").uppercase(Locale.ROOT))
            }.getOrNull()
            require(particle in BuilderPlaqueTrail.PARTICLES) {
                "Builder plaque border-trail.particle must be one of ${BuilderPlaqueTrail.PARTICLES.joinToString()}"
            }
            val interval = config.integer("$PATH.border-trail.update-interval-ticks", 5)
            require(interval in 5..40 && interval % 5 == 0) { "Builder plaque trail interval must be a multiple of 5 in 5..40 ticks" }
            val viewers = config.integer("$PATH.border-trail.max-viewers", 16)
            require(viewers in 1..32) { "Builder plaque trail max-viewers must be in 1..32" }
            return BuilderPlaqueAppearance(
                Color.fromARGB(alpha, argb shr 16 and 255, argb shr 8 and 255, argb and 255),
                config.boolean("$PATH.glow.enabled", false), rgb("glow.color", "#66D9FF"), material,
                number("glow.thickness", .025, .01..0.1).toFloat(),
                BuilderPlaqueTrail(
                    config.boolean("$PATH.border-trail.enabled", true), checkNotNull(particle),
                    rgb("border-trail.color", "#66D9FF"), number("border-trail.size", .65, .1..2.0).toFloat(),
                    number("border-trail.speed", 2.0, .1..8.0), number("border-trail.tail-length", 2.4, .1..8.0),
                    number("border-trail.spacing", .12, .05..1.0), number("border-trail.padding", .08, .02..0.5),
                    interval, number("border-trail.view-range", 24.0, 2.0..64.0), viewers,
                ),
            )
        }
    }
}

internal data class BuilderPlaqueTrail(
    val enabled: Boolean = true,
    val particle: Particle = Particle.DUST,
    val color: Color = Color.fromRGB(0x66D9FF),
    val size: Float = .65f,
    val speed: Double = 2.0,
    val tailLength: Double = 2.4,
    val spacing: Double = .12,
    val padding: Double = .08,
    val intervalTicks: Int = 5,
    val viewRange: Double = 24.0,
    val maxViewers: Int = 16,
) {
    companion object {
        const val MAX_SAMPLES = 32
        val PARTICLES = setOf(Particle.DUST, Particle.END_ROD, Particle.ELECTRIC_SPARK, Particle.FLAME, Particle.SOUL_FIRE_FLAME)
    }
}

internal data class BuilderPlaqueTrailPoint(val x: Double, val y: Double, val strength: Float)

/** A distance-sampled clockwise comet; tick time is Minecraft simulation time, not wall time. */
internal fun builderPlaqueTrail(width: Double, height: Double, tick: Long, settings: BuilderPlaqueTrail): List<BuilderPlaqueTrailPoint> {
    if (!settings.enabled) return emptyList()
    val w = width + settings.padding * 2
    val h = height + settings.padding * 2
    val perimeter = 2 * (w + h)
    val length = settings.tailLength.coerceAtMost(perimeter * .75)
    val count = (ceil(length / settings.spacing).toInt() + 1).coerceIn(2, BuilderPlaqueTrail.MAX_SAMPLES)
    val head = tick.toDouble() * settings.speed / 20.0
    return List(count) { index ->
        val fraction = index.toDouble() / (count - 1)
        val distance = ((head - length * fraction) % perimeter + perimeter) % perimeter
        val (x, y) = when {
            distance < w -> -w / 2 + distance to height + settings.padding
            distance < w + h -> w / 2 to height + settings.padding - (distance - w)
            distance < w * 2 + h -> w / 2 - (distance - w - h) to -settings.padding
            else -> -w / 2 to -settings.padding + distance - w * 2 - h
        }
        BuilderPlaqueTrailPoint(x, y, (1.0 - fraction * .8).toFloat())
    }
}
