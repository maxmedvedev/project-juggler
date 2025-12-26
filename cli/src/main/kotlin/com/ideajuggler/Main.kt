package com.ideajuggler

import com.ideajuggler.cli.*
import com.ideajuggler.cli.framework.CliApp

fun main(args: Array<String>) {
    val app = CliApp(
        name = "idea-juggler",
        version = "0.0.3",
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
