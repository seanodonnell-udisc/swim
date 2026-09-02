package swim.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class Swim : CliktCommand(name = "swim") {
    private val version by option("--version").flag()

    override fun run() {
        if (version) echo("0.1.0")
    }
}

fun main(args: Array<String>) = Swim().main(args)
