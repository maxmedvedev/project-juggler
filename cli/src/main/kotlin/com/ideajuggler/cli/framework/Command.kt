package com.ideajuggler.cli.framework

abstract class Command(
    val name: String,
    val help: String
) {
    internal val options = mutableListOf<OptionSpec<*>>()
    internal val arguments = mutableListOf<ArgumentSpec<*>>()

    abstract fun run()

    fun execute(args: List<String>) {
        try {
            parseArgs(args)
            run()
        } catch (e: CliException) {
            System.err.println("Error: ${e.message}")
            throw ExitException(1)
        }
    }

    protected fun echo(message: String = "", err: Boolean = false) {
        if (err) System.err.println(message) else println(message)
    }

    protected fun prompt(message: String): String? {
        print("$message: ")
        return readlnOrNull()
    }

    protected fun fail(message: String): Nothing {
        throw CliException(message)
    }
}

class CliException(message: String) : Exception(message)
class ExitException(val code: Int) : Exception()
