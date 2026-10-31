package com.projectjuggler.plugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UtilsTest : StringSpec({

    val unknownError = ProjectJugglerBundle.message("error.unknown")

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
