package com.ideajuggler.cli.framework

fun Command.parseArgs(args: List<String>) {
    val iterator = args.iterator()
    var argIndex = 0

    while (iterator.hasNext()) {
        val arg = iterator.next()

        when {
            // Long option: --name or --name=value
            arg.startsWith("--") -> {
                val parts = arg.substring(2).split('=', limit = 2)
                val optName = parts[0]
                val option = options.find { it.longName == optName }
                    ?: throw CliException("Unknown option: --$optName")

                parseArgs(option, parts, iterator, optName, false)
            }

            // Short option: -v or -n value
            arg.startsWith("-") && arg.length > 1 && !arg[1].isDigit() -> {
                val optChar = arg.substring(1)
                val option = options.find { it.shortName == optChar }
                    ?: throw CliException("Unknown option: -$optChar")

                parseArgs(option, listOf(arg), iterator, optChar, true)
            }

            // Positional argument
            else -> {
                if (argIndex >= arguments.size) {
                    throw CliException("Unexpected argument: $arg")
                }
                val argSpec = arguments[argIndex]
                argSpec.parseAndSet(arg)
                argIndex++
            }
        }
    }

    // Apply defaults for unparsed options
    options.forEach { option ->
        option.applyDefault()
    }

    // Verify required arguments
    arguments.forEach { argSpec ->
        argSpec.getValue()
    }
}

private fun parseArgs(
    option: OptionSpec<*>,
    parts: List<String>,
    iterator: Iterator<String>,
    optName: String,
    isShortCommandVersion: Boolean,
) {
    if (option is FlagOption) {
        option.parseAndSet("true")
    } else {
        val value = if (!isShortCommandVersion && parts.size > 1) {
            parts[1]
        } else {
            if (!iterator.hasNext()) {
                throw CliException("Option --$optName requires a value")
            }
            iterator.next()
        }
        option.parseAndSet(value)
    }
}
