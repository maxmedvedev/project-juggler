package com.projectjuggler.synchelper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SyncHelperArgsTest : StringSpec({
    val required = arrayOf("--ide", "/ide/bin/idea", "--path", "/work/project")

    fun parse(vararg args: String) = parseArgs(args.toList().toTypedArray())

    fun usageError(vararg args: String): String =
        shouldThrow<UsageException> { parse(*args) }.message!!

    "a single type flag selects only that part" {
        parse(*required, "--plugins") shouldBe
            SyncHelperArgs("/ide/bin/idea", "/work/project", syncVmOptions = false, syncConfig = false, syncPlugins = true)
    }

    "several type flags combine in any order" {
        parse("--config", "--path", "/work/project", "--vmoptions", "--ide", "/ide/bin/idea") shouldBe
            SyncHelperArgs("/ide/bin/idea", "/work/project", syncVmOptions = true, syncConfig = true, syncPlugins = false)
    }

    "--all implies all three parts" {
        parse(*required, "--all") shouldBe
            SyncHelperArgs("/ide/bin/idea", "/work/project", syncVmOptions = true, syncConfig = true, syncPlugins = true)
    }

    "a repeated type flag is accepted" {
        parse(*required, "--config", "--config").syncConfig shouldBe true
    }

    "a missing --ide is a usage error" {
        usageError("--path", "/work/project", "--all") shouldContain "--ide"
    }

    "a missing --path is a usage error" {
        usageError("--ide", "/ide/bin/idea", "--all") shouldContain "--path"
    }

    "no type flag is a usage error" {
        usageError(*required) shouldContain "At least one of"
    }

    "an unknown flag is a usage error" {
        usageError(*required, "--plugin") shouldContain "Unknown argument: --plugin"
    }

    "--all-projects is a usage error" {
        usageError("--ide", "/ide/bin/idea", "--all-projects", "--all") shouldContain "Unknown argument: --all-projects"
    }

    "a trailing value flag is a usage error" {
        usageError("--ide", "/ide/bin/idea", "--all", "--path") shouldContain "--path needs a value"
    }

    "a value flag followed by another flag is a usage error" {
        usageError("--ide", "--path", "/work/project", "--all") shouldContain "--ide needs a value"
    }

    "a repeated --path is a usage error" {
        usageError(*required, "--path", "/work/other", "--all") shouldContain "--path is given more than once"
    }

    "a repeated --ide is a usage error" {
        usageError(*required, "--ide", "/ide/other", "--all") shouldContain "--ide is given more than once"
    }
})
