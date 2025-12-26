package com.ideajuggler.cli

import com.ideajuggler.cli.framework.ExitException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createTempFile

class OpenCommandTest : StringSpec({

    "should fail with non-existent path using tilde" {
        val command = OpenCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("~/non-existent-directory-xyz123"))
        }

        exception.code shouldBe 1
    }

    "should fail with non-existent absolute path" {
        val command = OpenCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(listOf("/tmp/non-existent-directory-xyz123-abc"))
        }

        exception.code shouldBe 1
    }

    "should fail when path is a file not a directory" {
        val tempFile = createTempFile("test-file", ".txt")

        try {
            val command = OpenCommand()

            val exception = shouldThrow<ExitException> {
                command.execute(listOf(tempFile.toString()))
            }

            exception.code shouldBe 1
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

            val exception = shouldThrow<ExitException> {
                command.execute(listOf("~/$relativePath"))
            }

            exception.code shouldBe 1
        } finally {
            tempFile.toFile().delete()
        }
    }

    "should fail when no arguments provided" {
        val command = OpenCommand()

        val exception = shouldThrow<ExitException> {
            command.execute(emptyList())
        }

        exception.code shouldBe 1
    }
})
