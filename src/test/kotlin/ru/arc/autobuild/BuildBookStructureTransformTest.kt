package ru.arc.autobuild

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.api.ArcSchematicLibrary
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.api.ArcSchematicTransform
import java.util.concurrent.CompletableFuture

class BuildBookStructureTransformTest : StringSpec({
    "book rotation and mirror use the shared asynchronous transform result" {
        val source = mockk<ArcSchematicSnapshot>()
        val transformed = mockk<ArcSchematicSnapshot>()
        val pending = CompletableFuture<ArcSchematicSnapshot>()
        val library = mockk<ArcSchematicLibrary>()
        every { library.transform(source, ArcSchematicTransform(3, true)) } returns pending
        val building = Building("civs/house", source, library)
        val result = building.transform(-90, true)
        result.isDone shouldBe false
        pending.complete(transformed)
        result.join() shouldBe transformed
        verify(exactly = 1) { library.transform(source, ArcSchematicTransform(3, true)) }
    }
})
