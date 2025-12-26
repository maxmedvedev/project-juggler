package com.ideajuggler.cli.framework

sealed class OptionSpec<T>(
    val shortName: String?,
    val longName: String,
    val help: String
) {
    private var value: T? = null
    abstract fun parse(arg: String): T
    abstract fun defaultValue(): T?

    fun parseAndSet(arg: String) {
        value = parse(arg)
    }

    fun applyDefault() {
        if (value == null) {
            value = defaultValue()
        }
    }

    // Helper functions for command classes
    fun getValue(): T = value ?: defaultValue()
    ?: throw CliException("Missing required option: $longName")

    fun getValueOrNull(): T? = value ?: defaultValue()

}

class FlagOption(
    shortName: String?,
    longName: String,
    help: String
) : OptionSpec<Boolean>(shortName, longName, help) {
    override fun parse(arg: String) = true
    override fun defaultValue() = false
}

class StringOption(
    shortName: String?,
    longName: String,
    help: String,
    private val default: String? = null
) : OptionSpec<String>(shortName, longName, help) {
    override fun parse(arg: String) = arg
    override fun defaultValue() = default
}

class IntOption(
    shortName: String?,
    longName: String,
    help: String,
    private val default: Int? = null
) : OptionSpec<Int>(shortName, longName, help) {
    override fun parse(arg: String) = arg.toIntOrNull()
        ?: throw CliException("Invalid integer value for $longName: $arg")

    override fun defaultValue() = default
}
