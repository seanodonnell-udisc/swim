package swim.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.CancellationException
import swim.core.model.NotFoundError
import swim.core.model.ScopeError
import swim.core.model.SwimError
import platform.posix.exit as posixExit

/** The whole exit-code contract. Agents read these, so nothing else may set a status. */
object Exit {
    const val OK: Int = 0
    const val ERROR: Int = 1
    const val USAGE: Int = 2
    const val NOT_FOUND: Int = 3
}

/** Reports the message on stderr and stops the process. A stack trace is never printed. */
fun fail(code: Int, message: String): Nothing {
    Out.status("${red("error:")} $message")
    posixExit(code)
    throw IllegalStateException("unreachable")
}

/**
 * Every command runs inside this. It is the one place that turns a failure into an exit code.
 */
abstract class SwimCommand(name: String) : SuspendingCliktCommand(name) {
    /** The command body. Throw a [SwimError] to select an exit code. */
    abstract suspend fun execute()

    final override suspend fun run() {
        try {
            execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CliktError) {
            throw e
        } catch (e: ScopeError) {
            fail(Exit.USAGE, e.message ?: "Bad scope.")
        } catch (e: NotFoundError) {
            fail(Exit.NOT_FOUND, e.message ?: "Not found.")
        } catch (e: SwimError) {
            fail(Exit.ERROR, e.message ?: "Failed.")
        } catch (e: Exception) {
            fail(Exit.ERROR, e.message ?: e.toString())
        }
    }
}
