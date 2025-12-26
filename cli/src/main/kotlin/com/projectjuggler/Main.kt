package com.projectjuggler

import com.projectjuggler.cli.*
import com.projectjuggler.cli.framework.CliApp

fun main(args: Array<String>) {
    val app = CliApp(
        name = "project-juggler",
        version = CLI_VERSION,
        commands = listOf(
            OpenCommand(),
            OpenCopyCommand(),
            ListCommand(),
            CleanCommand(),
            ConfigCommand(),
            RecentCommand(),
            SyncCommand()
        )
    )

    app.run(args)
}
