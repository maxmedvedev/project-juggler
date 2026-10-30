package com.projectjuggler.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PlatformTest : StringSpec({
    "fromOsName maps known OS names" {
        Platform.fromOsName("Mac OS X") shouldBe Platform.MACOS
        Platform.fromOsName("Darwin") shouldBe Platform.MACOS
        Platform.fromOsName("Windows 11") shouldBe Platform.WINDOWS
        Platform.fromOsName("Linux") shouldBe Platform.LINUX
        Platform.fromOsName("AIX") shouldBe Platform.LINUX
    }

    "fromOsName throws for unsupported OS names" {
        shouldThrow<UnsupportedOperationException> { Platform.fromOsName("FreeBSD") }
    }
})
