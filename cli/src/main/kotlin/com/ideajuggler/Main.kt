package com.ideajuggler

import com.ideajuggler.cli.*
import com.ideajuggler.cli.framework.CliApp

fun main(args: Array<String>) {
    val app = CliApp(
        name = "idea-juggler",
        version = "0.0.2",
        commands = listOf(
            OpenCommand(),
            ListCommand(),
            CleanCommand(),
            ConfigCommand(),
            RecentCommand(),
            SyncCommand()
        )
    )

    app.run(args)
}
