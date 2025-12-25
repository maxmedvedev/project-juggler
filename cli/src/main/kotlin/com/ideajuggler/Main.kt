package com.ideajuggler

import com.ideajuggler.cli.createCLI
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        val cli = createCLI()
        cli.main(args)
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}
