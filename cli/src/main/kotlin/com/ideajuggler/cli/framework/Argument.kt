package com.ideajuggler.cli.framework

class StringArgument(
    val name: String,
    val help: String
) {
    private var value: String? = null

    fun parseAndSet(arg: String) {
        value = arg
    }

    fun getValue(): String =
        value ?: throw CliException("Missing required argument: $name")
}
