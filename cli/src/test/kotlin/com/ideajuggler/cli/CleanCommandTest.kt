package com.ideajuggler.cli

import com.ideajuggler.cli.framework.ExitException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CleanCommandTest : StringSpec({

    "should fail when project not found with tilde path" {
        val command = CleanCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("~/non-existent-project-xyz123", "--force"))
        }

        exception.code shouldBe 1
    }

    "should fail when project not found with absolute path" {
        val command = CleanCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("/tmp/non-existent-project-xyz123-abc", "--force"))
        }

        exception.code shouldBe 1
    }

    "should fail when project not found with ID" {
        val command = CleanCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("invalid-project-id-xyz", "--force"))
        }

        exception.code shouldBe 1
    }

    "should fail when no arguments provided" {
        val command = CleanCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(emptyList())
        }

        exception.code shouldBe 1
    }

    "should parse force flag with short option" {
        val command = CleanCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("invalid-project-id-xyz", "-f"))
        }

        exception.code shouldBe 1
    }

    "should parse force flag with long option" {
        val command = CleanCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("invalid-project-id-xyz", "--force"))
        }

        exception.code shouldBe 1
    }
})
