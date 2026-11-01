package com.projectjuggler.util

import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

    "withFileLock tolerates a pre-created file and keeps its content" {
        val file = createTempDir("test-lock").resolve("file.json")
        file.writeText("existing")

        FileLockUtils.withFileLock(file) { file.readText() } shouldBe "existing"
    }

    "threads of one JVM wait for each other instead of failing" {
        val file = createTempDir("test-lock").resolve("file.json")
        val threads = 8
        val start = CountDownLatch(1)
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                executor.submit<Unit> {
                    start.await()
                    // An unnormalized path to the same file shares the lock
                    FileLockUtils.withFileLock(file.parent.resolve(".").resolve(file.fileName)) {
                        maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        Thread.sleep(20)
                        inside.decrementAndGet()
                    }
                }
            }
            start.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        maxInside.get() shouldBe 1
    }
})
