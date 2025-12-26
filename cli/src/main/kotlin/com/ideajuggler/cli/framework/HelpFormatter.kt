package com.ideajuggler.cli.framework

fun Command.getHelp(): String = buildString {
    appendLine("$name - $help")
    appendLine()

    if (arguments.isNotEmpty() || options.isNotEmpty()) {
        append("Usage: $name")
        options.forEach { opt ->
            if (opt is FlagOption) {
                append(" [${opt.longName}]")
            } else {
                append(" [${opt.longName} <value>]")
            }
        }
        arguments.forEach { arg ->
            append(" <${arg.name}>")
        }
        appendLine()
        appendLine()
    }

    if (arguments.isNotEmpty()) {
        appendLine("Arguments:")
        arguments.forEach { arg ->
            appendLine("  ${arg.name.padEnd(20)} ${arg.help}")
        }
        appendLine()
    }

    if (options.isNotEmpty()) {
        appendLine("Options:")
        options.forEach { opt ->
            val names = buildString {
                opt.shortName?.let { append("-$it, ") }
                append("--${opt.longName}")
            }
            appendLine("  ${names.padEnd(20)} ${opt.help}")
        }
    }
}
