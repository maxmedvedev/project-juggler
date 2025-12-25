package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class IdeaJugglerCommand : CliktCommand(
    name = "idea-juggler",
    help = "Manage IntelliJ IDEA projects with isolated configurations"
) {
    override fun run() {
        // If no subcommand is provided, show help
        if (currentContext.invokedSubcommand == null) {
            echo(currentContext.command.getFormattedHelp())
        }
    }
}

fun createCLI(): IdeaJugglerCommand {
    return IdeaJugglerCommand().subcommands(
        OpenCommand(),
        ListCommand(),
        CleanCommand(),
        ConfigCommand(),
        RecentCommand()
    )
}
