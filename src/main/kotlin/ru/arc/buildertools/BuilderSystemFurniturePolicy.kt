package ru.arc.buildertools

/** Empty vanilla furniture and included floral decoration in reviewed system schematics. */
internal object BuilderSystemFurniturePolicy {
    private val SINGLE_BLOCK_FURNITURE = setOf("CHEST", "BARREL", "FURNACE", "BLAST_FURNACE", "SMOKER", "FLOWER_POT")

    fun isSupported(material: String): Boolean =
        material in SINGLE_BLOCK_FURNITURE || material.endsWith("_BED") || material.startsWith("POTTED_")
}
