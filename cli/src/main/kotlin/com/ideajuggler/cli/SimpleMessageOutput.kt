package com.ideajuggler.cli

import com.ideajuggler.core.MessageOutput

class SimpleMessageOutput : MessageOutput {
    override fun echo(message: String) {
        println(message)
    }
}
