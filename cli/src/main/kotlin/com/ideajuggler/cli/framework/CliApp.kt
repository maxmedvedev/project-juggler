package com.ideajuggler.cli.framework

import kotlin.system.exitProcess

class CliApp(
    val name: String,
    val version: String,
    private val commands: List<Command>
) {
    fun run(args: Array<String>) {
        try {
            if (args.isEmpty()) {
                printHelp()
                return
            }

            when (val commandName = args[0]) {
                "--help", "-h", "help" -> printHelp()
                "--version", "-v" -> printVersion()
                else -> executeCommand(commandName, args)
            }
        } catch (e: ExitException) {
            exitProcess(e.code)
        }
    }

    private fun executeCommand(commandName: String, args: Array<String>) {
        val command = commands.find { it.name == commandName }
        if (command == null) {
            System.err.println("Unknown command: $commandName")
            System.err.println("Run '$name help' for usage")
            throw ExitException(1)
        }

        val commandArgs = args.drop(1)
        if (commandArgs.contains("--help") || commandArgs.contains("-h")) {
            println(command.getHelp())
        } else {
            command.execute(commandArgs)
        }
    }

    private fun printVersion() {
        println("$name version $version")
    }

    private fun printHelp() {
        println("$name - IntelliJ IDEA project manager")
        println()
        println("Usage: $name <command> [options]")
        println()
        println("Commands:")
        commands.forEach { cmd ->
            println("  ${cmd.name.padEnd(12)} ${cmd.help}")
        }
        println()
        println("Run '$name <command> --help' for more information on a command")
    }
}
