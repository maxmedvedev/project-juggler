package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.ideajuggler.core.MessageOutput

/**
 * Adapter that implements MessageOutput by delegating to CliktCommand.echo()
 */
class CliktMessageOutput(private val command: CliktCommand) : MessageOutput {
    override fun echo(message: String) {
        command.echo(message)
    }
}
