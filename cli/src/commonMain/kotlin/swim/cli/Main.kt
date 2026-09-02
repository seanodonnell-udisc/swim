package swim.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlinx.coroutines.runBlocking
import swim.cli.commands.AuthCommand
import swim.cli.commands.BlockersCommand
import swim.cli.commands.BulkRelateCommand
import swim.cli.commands.CommentCleanupCommand
import swim.cli.commands.DownstreamCommand
import swim.cli.commands.LabelsCommand
import swim.cli.commands.ListCommand
import swim.cli.commands.NextCommand
import swim.cli.commands.ProjectsCommand
import swim.cli.commands.ReadyCommand
import swim.cli.commands.RefsCommand
import swim.cli.commands.RelateCommand
import swim.cli.commands.ShowCommand
import swim.cli.commands.StatusCommand
import swim.cli.commands.TeamsCommand

/** The root command. It only holds the tree together. */
class Swim : SuspendingCliktCommand(name = "swim") {
    init {
        versionOption(VERSION)
    }

    override fun help(context: Context): String =
        "Work with Linear projects that have hundreds of issues and deep blocker trees.\n" +
            "Every command that reads a set of issues needs a scope: a Linear URL, or " +
            "--team / --project / --label / --cycle / --assignee.\n" +
            "Add --json for machine-readable output. " +
            "Exit codes: 0 ok, 1 error, 2 usage or unknown scope, 3 not found."

    override suspend fun run() = Unit
}

/** The CLI version. */
const val VERSION: String = "0.1.0"

fun main(args: Array<String>): Unit = runBlocking {
    Swim().subcommands(
        AuthCommand(),
        ListCommand(),
        ShowCommand(),
        TeamsCommand(),
        ProjectsCommand(),
        LabelsCommand(),
        StatusCommand(),
        ReadyCommand(),
        NextCommand(),
        BlockersCommand(),
        DownstreamCommand(),
        RelateCommand(),
        BulkRelateCommand(),
        CommentCleanupCommand(),
        RefsCommand(),
    ).main(args)
}
