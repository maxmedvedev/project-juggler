package com.ideajuggler.cli.framework

import java.nio.file.Path

sealed class ArgumentSpec<T>(
    val name: String,
    val help: String
) {
    private var value: T? = null
    abstract fun parse(arg: String): T

    fun parseAndSet(arg: String) {
        value = parse(arg)
    }

    fun getValue(): T =
        value ?: throw CliException("Missing required argument: $name")
}

class StringArgument(
    name: String,
    help: String
) : ArgumentSpec<String>(name, help) {
    override fun parse(arg: String) = arg
}

class PathArgument(
    name: String,
    help: String
) : ArgumentSpec<Path>(name, help) {
    override fun parse(arg: String): Path = Path.of(arg)
}
