package ru.arc.buildertools

/** Detached calculation. Native capture has already resolved safety, exact items and block pairs. */
internal object BuilderBookPlanCalculation {
    fun assemble(
        cells: List<BuilderBookCapturedCell>,
        bookCost: BuilderItemAmount,
        playerSuppliedTypes: Set<String>?,
        template: BuilderPlan,
        playerName: String,
        title: String,
        anchor: BuilderBlockPos,
        maximumChanges: Int,
    ): BuilderBookPlannedProject {
        val rejected = BuilderBookMultiBlockPolicy.rejectedCaptured(cells)
        val skipped = cells.count { it.skippedUnsafe || it.position in rejected && it.placement != null }
        val placements = BuilderBookMultiBlockPolicy.primaryFirstCaptured(cells.filterNot { it.position in rejected })
            .asSequence().mapNotNull(BuilderBookCapturedCell::placement).take(maximumChanges + 1).toList()
        if (placements.isEmpty()) throw BuilderUserFailure("errors.nothing-to-change")
        if (placements.size > maximumChanges) throw BuilderUserFailure("errors.selection-too-large")
        val construction = BuilderBookConstructionCosts.assemble(bookCost, playerSuppliedTypes, placements)
        val now = System.currentTimeMillis()
        val plan = template.copy(
            createdAtMillis = now,
            expiresAtMillis = now + (template.expiresAtMillis - template.createdAtMillis),
            changes = construction.steps.map(BuilderConstructionStep::change),
            costs = construction.costs,
            rewards = construction.rewards,
            skippedUnsafeBlocks = skipped,
        ).validated(maximumChanges)
        return BuilderBookPlannedProject(
            plan,
            BuilderConstructionProjectRecord(
                projectId = plan.id,
                playerId = plan.playerId,
                playerName = playerName,
                projectTitle = title,
                siteAnchor = anchor,
                plan = plan,
                steps = construction.steps,
                bookCost = construction.bookCost,
                state = BuilderConstructionProjectState.PREPARED,
                cursor = 0,
                createdAtMillis = plan.createdAtMillis,
                updatedAtMillis = plan.createdAtMillis,
            ).validated(maximumChanges),
        )
    }
}
