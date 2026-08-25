package com.projectjuggler.util

import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText

class PathUtilsTest : StringSpec({

    val rootDir = createTempDir("path-utils-root")

    "should expand tilde with forward slash" {
        val homeDir = Path(System.getProperty("user.home"))
        val result = PathUtils.expandTilde(Path("~/Documents"))

        result shouldBe homeDir.resolve("Documents")
    }

    "should expand standalone tilde" {
        val homeDir = Path(System.getProperty("user.home"))
        val result = PathUtils.expandTilde(Path("~"))

        result shouldBe homeDir
    }

    "should not modify absolute paths" {
        val absolutePath = Path("/usr/local/bin")
        val result = PathUtils.expandTilde(absolutePath)

        result shouldBe absolutePath
    }

    "should not modify relative paths without tilde" {
        val relativePath = Path("Documents/projects")
        val result = PathUtils.expandTilde(relativePath)

        result shouldBe relativePath
    }

    "should handle tilde in middle of path (not expanded)" {
        val pathWithTilde = Path("/some/path/~/file")
        val result = PathUtils.expandTilde(pathWithTilde)

        // Only leading tilde should be expanded
        result shouldBe pathWithTilde
    }

    "should handle nested path with tilde prefix" {
        val homeDir = Path(System.getProperty("user.home"))
        val result = PathUtils.expandTilde(Path("~/work/projects/my-app"))

        result shouldBe homeDir.resolve("work/projects/my-app")
    }

    "should handle tilde with spaces in path" {
        val homeDir = Path(System.getProperty("user.home"))
        val result = PathUtils.expandTilde(Path("~/My Documents/Projects"))

        result shouldBe homeDir.resolve("My Documents/Projects")
    }

    "should handle empty string" {
        val result = PathUtils.expandTilde(Path(""))

        result shouldBe Path("")
    }

    "should handle tilde followed by character other than slash" {
        // Tilde followed by username syntax like ~username is NOT expanded
        // Only ~/ and standalone ~ are expanded
        val result = PathUtils.expandTilde(Path("~user/path"))

        result shouldBe Path("~user/path")
    }

    "projectRoot should return a directory path unchanged" {
        PathUtils.projectRoot(rootDir) shouldBe rootDir
    }

    "projectRoot should return the enclosing directory of a file" {
        val file = rootDir.resolve("MODULE.bazel").apply { writeText("module(name = \"test\")") }

        PathUtils.projectRoot(file) shouldBe rootDir
    }

    "projectRoot should return the parent of a path that does not exist" {
        val missing = rootDir.resolve("no-such-file.bazel")

        PathUtils.projectRoot(missing) shouldBe rootDir
    }

    "projectRoot should return the nested directory for a nested file" {
        val nested = rootDir.resolve("nested").createDirectory()
        val file = nested.resolve("MODULE.bazel").apply { writeText("") }

        PathUtils.projectRoot(file) shouldBe nested
    }

    "projectRoot should return null for a parentless path" {
        PathUtils.projectRoot(Path("MODULE.bazel")).shouldBeNull()
    }
})
