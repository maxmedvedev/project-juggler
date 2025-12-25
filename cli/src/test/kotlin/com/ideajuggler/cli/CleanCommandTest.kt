package com.ideajuggler.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CleanCommandTest : StringSpec({

    "should fail when project not found with tilde path" {
        val command = CleanCommand()
        val result = command.test("~/non-existent-project-xyz123 --force")

        result.statusCode shouldBe 1
        result.output shouldContain "Project not found"
    }

    "should fail when project not found with absolute path" {
        val command = CleanCommand()
        val result = command.test("/tmp/non-existent-project-xyz123-abc --force")

        result.statusCode shouldBe 1
        result.output shouldContain "Project not found"
    }

    "should fail when project not found with ID" {
        val command = CleanCommand()
        val result = command.test("invalid-project-id-xyz --force")

        result.statusCode shouldBe 1
        result.output shouldContain "Project not found"
    }
})
