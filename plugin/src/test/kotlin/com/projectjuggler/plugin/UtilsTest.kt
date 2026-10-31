package com.projectjuggler.plugin

import com.intellij.openapi.progress.ProcessCanceledException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import kotlin.coroutines.cancellation.CancellationException
import io.kotest.matchers.shouldBe

class UtilsTest : StringSpec({

    val unknownError = ProjectJugglerBundle.message("error.unknown")

    "rethrowIfControlFlow rethrows a control-flow exception" {
        val pce = ProcessCanceledException()
        shouldThrow<ProcessCanceledException> { pce.rethrowIfControlFlow() } shouldBe pce
    }

    "rethrowIfControlFlow rethrows a cancellation" {
        val cancellation = CancellationException()
        shouldThrow<CancellationException> { cancellation.rethrowIfControlFlow() } shouldBe cancellation
    }

    "rethrowIfControlFlow returns for an ordinary exception" {
        shouldNotThrowAny { IllegalStateException().rethrowIfControlFlow() }
    }

    "userMessage returns the exception message" {
        IllegalStateException("boom").userMessage() shouldBe "boom"
    }

    "userMessage falls back to unknown error without a message" {
        IllegalStateException().userMessage() shouldBe unknownError
    }

    "userMessage falls back to unknown error for a blank message" {
        IllegalStateException("  ").userMessage() shouldBe unknownError
    }
})
