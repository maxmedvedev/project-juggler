package com.ideajuggler.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path

class PathUtilsTest : StringSpec({

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
})
