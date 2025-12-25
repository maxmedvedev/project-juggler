package com.ideajuggler.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class HashUtilsTest : StringSpec({

    "should calculate consistent hash for same string" {
        val input = "test string"
        val hash1 = HashUtils.calculateStringHash(input)
        val hash2 = HashUtils.calculateStringHash(input)

        hash1 shouldBe hash2
    }

    "should calculate different hashes for different strings" {
        val hash1 = HashUtils.calculateStringHash("string1")
        val hash2 = HashUtils.calculateStringHash("string2")

        hash1 shouldNotBe hash2
    }

    "should generate 64-character SHA-256 hash for strings" {
        val hash = HashUtils.calculateStringHash("test")
        hash shouldHaveLength 64
    }

    "should handle empty strings" {
        val hash = HashUtils.calculateStringHash("")
        hash shouldHaveLength 64
    }

    "should handle unicode characters" {
        val hash = HashUtils.calculateStringHash("你好世界🌍")
        hash shouldHaveLength 64
    }

    "should calculate consistent hash for same file content" {
        val tempFile = createTempFile("test", ".txt")
        try {
            tempFile.writeText("test content")

            val hash1 = HashUtils.calculateFileHash(tempFile)
            val hash2 = HashUtils.calculateFileHash(tempFile)

            hash1 shouldBe hash2
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    "should calculate different hashes for different file contents" {
        val tempFile1 = createTempFile("test1", ".txt")
        val tempFile2 = createTempFile("test2", ".txt")

        try {
            tempFile1.writeText("content1")
            tempFile2.writeText("content2")

            val hash1 = HashUtils.calculateFileHash(tempFile1)
            val hash2 = HashUtils.calculateFileHash(tempFile2)

            hash1 shouldNotBe hash2
        } finally {
            Files.deleteIfExists(tempFile1)
            Files.deleteIfExists(tempFile2)
        }
    }

    "should generate 64-character SHA-256 hash for files" {
        val tempFile = createTempFile("test", ".txt")
        try {
            tempFile.writeText("test content")
            val hash = HashUtils.calculateFileHash(tempFile)
            hash shouldHaveLength 64
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    "should handle empty files" {
        val tempFile = createTempFile("test", ".txt")
        try {
            val hash = HashUtils.calculateFileHash(tempFile)
            hash shouldHaveLength 64
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    "should detect file content changes" {
        val tempFile = createTempFile("test", ".txt")
        try {
            tempFile.writeText("original content")
            val hash1 = HashUtils.calculateFileHash(tempFile)

            tempFile.writeText("modified content")
            val hash2 = HashUtils.calculateFileHash(tempFile)

            hash1 shouldNotBe hash2
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
})
