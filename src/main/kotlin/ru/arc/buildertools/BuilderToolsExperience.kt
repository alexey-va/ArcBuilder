package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.GameMode
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.ItemMeta
import ru.arc.core.LifecycleTaskScope
import kotlin.math.ceil

internal object BuilderGameModePolicy {
    fun allows(gameMode: GameMode): Boolean = gameMode == GameMode.SURVIVAL || gameMode == GameMode.CREATIVE

    fun usesInventory(gameMode: GameMode): Boolean = gameMode == GameMode.SURVIVAL
}

/**
 * The complete public command surface for builder tools.
 *
 * Keeping parsing, suggestions, and in-operation controls on this one contract
 * prevents undocumented compatibility shortcuts from bypassing the guided
 * selector workflow.
 */
internal enum class BuilderRootCommand(
    val literal: String,
    val safeDuringOperation: Boolean = false,
) {
    HELP("help"),
    WAND("wand"),
    CLEAR("clear"),
    FILL("fill"),
    COPY("copy"),
    BOOK("book"),
    PASTE("paste"),
    DECONSTRUCT("deconstruct"),
    CROWN("crown"),
    CONFIRM("confirm"),
    CANCEL("cancel", safeDuringOperation = true),
    UNDO("undo"),
    STATUS("status", safeDuringOperation = true),
    ;

    companion object {
        fun parse(raw: String?): BuilderRootCommand? =
            entries.firstOrNull { it.literal.equals(raw, ignoreCase = true) }

        fun suggestions(): List<String> = entries.map(BuilderRootCommand::literal)
    }
}

internal data class BuilderPendingPlan(
    val plan: BuilderPlan,
    val gameMode: GameMode,
)

internal object BuilderItemPresentation {
    fun apply(meta: ItemMeta, name: Component, lore: List<Component>) {
        meta.displayName(nonItalic(name))
        meta.lore(lore.map(::nonItalic))
        meta.addItemFlags(*ItemFlag.values())
    }

    fun nonItalic(component: Component): Component =
        component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
}

/** Keeps action-bar progress useful without sending a component every tick. */
internal object BuilderProgressCadence {
    private const val UPDATE_EVERY_BATCHES = 10

    fun shouldRender(batchNumber: Int, completed: Boolean): Boolean {
        require(batchNumber >= 1) { "Builder progress batch number must be positive" }
        return batchNumber == 1 || completed || batchNumber % UPDATE_EVERY_BATCHES == 0
    }
}

/** Starts one lifecycle-owned repeating preview task. */
internal class BuilderPreviewLoop(
    scope: LifecycleTaskScope,
    periodTicks: Long,
    render: () -> Unit,
) : AutoCloseable {
    private val task = run {
        require(periodTicks >= 1L) { "Builder preview period must be positive" }
        checkNotNull(scope.runTimer(0L, periodTicks, render)) { "Builder preview task was not scheduled" }
    }

    override fun close() = task.cancel()
}

internal data class BuilderPreviewPoint(val x: Double, val y: Double, val z: Double)

internal object BuilderSelectionPreviewGeometry {
    private data class Edge(val from: BuilderPreviewPoint, val to: BuilderPreviewPoint)

    fun visibleOutline(
        selection: BuilderSelection,
        viewerX: Double,
        viewerY: Double,
        viewerZ: Double,
        radius: Double,
        spacing: Double,
        maximumPoints: Int,
    ): List<BuilderPreviewPoint> {
        require(radius > 0.0 && spacing > 0.0 && maximumPoints > 0)
        val minX = selection.minX.toDouble()
        val maxX = selection.maxX + 1.0
        val minY = selection.minY.toDouble()
        val maxY = selection.maxY + 1.0
        val minZ = selection.minZ.toDouble()
        val maxZ = selection.maxZ + 1.0
        val corners = listOf(
            BuilderPreviewPoint(minX, minY, minZ),
            BuilderPreviewPoint(maxX, minY, minZ),
            BuilderPreviewPoint(minX, maxY, minZ),
            BuilderPreviewPoint(maxX, maxY, minZ),
            BuilderPreviewPoint(minX, minY, maxZ),
            BuilderPreviewPoint(maxX, minY, maxZ),
            BuilderPreviewPoint(minX, maxY, maxZ),
            BuilderPreviewPoint(maxX, maxY, maxZ),
        )
        val edges = listOf(
            0 to 1, 2 to 3, 4 to 5, 6 to 7,
            0 to 2, 1 to 3, 4 to 6, 5 to 7,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
        ).map { (from, to) -> Edge(corners[from], corners[to]) }
        val radiusSquared = radius * radius
        val visible = LinkedHashSet<BuilderPreviewPoint>()
        edges.forEach { edge ->
            val length = maxOf(
                kotlin.math.abs(edge.to.x - edge.from.x),
                kotlin.math.abs(edge.to.y - edge.from.y),
                kotlin.math.abs(edge.to.z - edge.from.z),
            )
            val steps = ceil(length / spacing).toInt().coerceAtLeast(1)
            for (index in 0..steps) {
                val progress = index.toDouble() / steps
                val point = BuilderPreviewPoint(
                    x = edge.from.x + (edge.to.x - edge.from.x) * progress,
                    y = edge.from.y + (edge.to.y - edge.from.y) * progress,
                    z = edge.from.z + (edge.to.z - edge.from.z) * progress,
                )
                val dx = point.x - viewerX
                val dy = point.y - viewerY
                val dz = point.z - viewerZ
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) visible += point
                if (visible.size >= maximumPoints) return visible.toList()
            }
        }
        return visible.toList()
    }
}
