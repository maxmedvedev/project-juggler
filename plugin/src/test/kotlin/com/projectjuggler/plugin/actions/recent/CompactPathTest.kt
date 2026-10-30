package com.projectjuggler.plugin.actions.recent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CompactPathTest : StringSpec({

    val home = "/home/u"

    "replaces the home directory with ~" {
        compactPath("/home/u/work/x", home) shouldBe "~/work/x"
    }

    "shortens a long path outside home to its parent and name" {
        val path = "/srv/" + "a".repeat(56) + "/parent/x"
        path.length shouldBe 70

        compactPath(path, home) shouldBe ".../parent/x"
    }

    "keeps a short path outside home as it is" {
        compactPath("/srv/work/x", home) shouldBe "/srv/work/x"
    }

    "prefers the ~ form for a long path under home" {
        val tail = "/" + "a".repeat(60) + "/x"

        compactPath(home + tail, home) shouldBe "~$tail"
    }
})
