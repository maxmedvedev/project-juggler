package com.projectjuggler.synchelper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class SyncHelperArgsTest : StringSpec({
    val required = arrayOf("--ide", "/ide/bin/idea", "--path", "/work/project")

    fun parse(vararg args: String) = parseArgs(args.toList().toTypedArray())

    fun sync(vararg args: String): HelperCommand.Sync = parse("sync", *args).shouldBeInstanceOf<HelperCommand.Sync>()

    fun usageError(vararg args: String): String =
        shouldThrow<UsageException> { parse(*args) }.message!!

    fun syncUsageError(vararg args: String): String = usageError("sync", *args)

    "a single type flag selects only that part" {
        sync(*required, "--plugins") shouldBe
            HelperCommand.Sync("/ide/bin/idea", "/work/project", syncVmOptions = false, syncConfig = false, syncPlugins = true)
    }

    "several type flags combine in any order" {
        sync("--config", "--path", "/work/project", "--vmoptions", "--ide", "/ide/bin/idea") shouldBe
            HelperCommand.Sync("/ide/bin/idea", "/work/project", syncVmOptions = true, syncConfig = true, syncPlugins = false)
    }

    "--all implies all three parts" {
        sync(*required, "--all") shouldBe
            HelperCommand.Sync("/ide/bin/idea", "/work/project", syncVmOptions = true, syncConfig = true, syncPlugins = true)
    }

    "a repeated type flag is accepted" {
        sync(*required, "--config", "--config").syncConfig shouldBe true
    }

    "a missing --ide is a usage error" {
        syncUsageError("--path", "/work/project", "--all") shouldContain "--ide"
    }

    "a missing --path is a usage error" {
        syncUsageError("--ide", "/ide/bin/idea", "--all") shouldContain "--path"
    }

    "no type flag is a usage error" {
        syncUsageError(*required) shouldContain "At least one of"
    }

    "an unknown flag is a usage error" {
        syncUsageError(*required, "--plugin") shouldContain "Unknown argument: --plugin"
    }

    "--all-projects is a usage error" {
        syncUsageError("--ide", "/ide/bin/idea", "--all-projects", "--all") shouldContain "Unknown argument: --all-projects"
    }

    "a trailing value flag is a usage error" {
        syncUsageError("--ide", "/ide/bin/idea", "--all", "--path") shouldContain "--path needs a value"
    }

    "a value flag followed by another flag is a usage error" {
        syncUsageError("--ide", "--path", "/work/project", "--all") shouldContain "--ide needs a value"
    }

    "a repeated --path is a usage error" {
        syncUsageError(*required, "--path", "/work/other", "--all") shouldContain "--path is given more than once"
    }

    "a repeated --ide is a usage error" {
        syncUsageError(*required, "--ide", "/ide/other", "--all") shouldContain "--ide is given more than once"
    }

    "a missing command is a usage error" {
        usageError() shouldContain "A command is required"
    }

    "flags without a command are a usage error" {
        usageError(*required, "--all") shouldContain "Unknown command: --ide"
    }

    "an unknown command is a usage error" {
        usageError("launch", *required) shouldContain "Unknown command: launch"
    }

    "open takes --ide and --path" {
        parse("open", "--path", "/work/project", "--ide", "/ide/bin/idea") shouldBe
            HelperCommand.Open("/ide/bin/idea", "/work/project")
    }

    "open rejects sync-type flags" {
        usageError("open", *required, "--all") shouldContain "Unknown argument: --all"
    }

    "open without --path is a usage error" {
        usageError("open", "--ide", "/ide/bin/idea") shouldContain "--path is required"
    }
})
