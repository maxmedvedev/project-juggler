package com.projectjuggler.util

import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.exists

class FileLockUtilsTest : StringSpec({
    "withFileLock creates missing parent dirs and the file" {
        val file = createTempDir("test-lock").resolve("a/b/file.json")

        FileLockUtils.withFileLock(file) { }

        file.exists() shouldBe true
    }

    "withFileLock returns the block result" {
        val file = createTempDir("test-lock").resolve("file.json")

        FileLockUtils.withFileLock(file) { 42 } shouldBe 42
    }

    "withFileLock releases the lock afterwards" {
        val file = createTempDir("test-lock").resolve("file.json")

        FileLockUtils.withFileLock(file) { "first" }

        FileLockUtils.withFileLock(file) { "second" } shouldBe "second"
    }
})
