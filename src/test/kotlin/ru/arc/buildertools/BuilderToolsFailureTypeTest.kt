package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLSyntaxErrorException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class BuilderToolsFailureTypeTest : StringSpec({
    "async wrapper types do not hide the actionable failure class" {
        val failure = CompletionException(ExecutionException(SQLSyntaxErrorException("sensitive database detail")))

        BuilderToolsFailureType.of(failure) shouldBe "SQLSyntaxErrorException"
    }

    "missing results stay distinguishable without logging a message" {
        BuilderToolsFailureType.of(null) shouldBe "missing_result"
    }
})
