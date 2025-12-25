package com.ideajuggler.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createTempFile

class OpenCommandTest : StringSpec({

    "should fail with non-existent path using tilde" {
        val command = OpenCommand()
        val result = command.test("~/non-existent-directory-xyz123")

        result.statusCode shouldBe 1
        result.output shouldContain "Path does not exist"
        result.output shouldContain "~/non-existent-directory-xyz123"
    }

    "should fail with non-existent absolute path" {
        val command = OpenCommand()
        val result = command.test("/tmp/non-existent-directory-xyz123-abc")

        result.statusCode shouldBe 1
        result.output shouldContain "Path does not exist"
    }

    "should fail when path is a file not a directory" {
        val tempFile = createTempFile("test-file", ".txt")

        try {
            val command = OpenCommand()
            val result = command.test(tempFile.toString())

            result.statusCode shouldBe 1
            result.output shouldContain "Path is not a directory"
        } finally {
            tempFile.toFile().delete()
        }
    }

    "should fail when tilde path points to a file" {
        val tempFile = createTempFile("test-file", ".txt")

        try {
            val command = OpenCommand()
            val homeDir = Path.of(System.getProperty("user.home"))
            val relativePath = homeDir.relativize(tempFile)
            val result = command.test("~/$relativePath")

            result.statusCode shouldBe 1
            result.output shouldContain "Path is not a directory"
        } finally {
            tempFile.toFile().delete()
        }
    }
})
