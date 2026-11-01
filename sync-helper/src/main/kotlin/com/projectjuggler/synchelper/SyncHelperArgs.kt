package com.projectjuggler.synchelper

internal const val USAGE = """Usage:
  sync-helper sync --ide <ide-path> --path <project-path> (--all | --vmoptions | --config | --plugins)...
  sync-helper open --ide <ide-path> --path <project-path>

Commands:
  sync                    Sync an isolated project with the base settings, stopping and relaunching its IDE
  open                    Focus the IDE window of the project if it is running, launch the project otherwise

  --ide <ide-path>        Executable of the IDE whose Project Juggler data holds the project
  --path <project-path>   Path of the project
  --all                   Sync VM options, config and plugins
  --vmoptions             Sync VM options
  --config                Sync the config directory
  --plugins               Sync the plugins directory

Exit codes: 0 done, 1 failure, 2 usage error."""

/** Parsed sync-helper command line; see [USAGE]. */
internal sealed interface HelperCommand {
    val idePath: String
    val projectPath: String

    data class Sync(
        override val idePath: String,
        override val projectPath: String,
        val syncVmOptions: Boolean,
        val syncConfig: Boolean,
        val syncPlugins: Boolean,
    ) : HelperCommand

    data class Open(
        override val idePath: String,
        override val projectPath: String,
    ) : HelperCommand
}

/** Invalid sync-helper command line; the message says what is wrong. */
internal class UsageException(message: String) : Exception(message)

private const val ALL = "--all"
private const val VM_OPTIONS = "--vmoptions"
private const val CONFIG = "--config"
private const val PLUGINS = "--plugins"

/**
 * Parses [args] per [USAGE]. The first token is the command. `--ide` and `--path` are required, `sync` also needs at
 * least one sync-type flag; unknown tokens (sync-type flags for `open`), a value flag without a value, and a repeated
 * `--ide`/`--path` are rejected.
 *
 * @throws UsageException when [args] do not follow [USAGE]
 */
internal fun parseArgs(args: Array<String>): HelperCommand {
    val command = args.firstOrNull() ?: throw UsageException("A command is required: sync or open")
    val rest = args.drop(1)
    return when (command) {
        "sync" -> parseSync(rest)
        "open" -> parseOptions(rest, flags = emptySet()).let { HelperCommand.Open(it.idePath, it.projectPath) }
        else -> throw UsageException("Unknown command: $command")
    }
}

private fun parseSync(args: List<String>): HelperCommand.Sync {
    val options = parseOptions(args, flags = setOf(ALL, VM_OPTIONS, CONFIG, PLUGINS))
    val all = ALL in options.flags
    val sync = HelperCommand.Sync(
        options.idePath,
        options.projectPath,
        syncVmOptions = all || VM_OPTIONS in options.flags,
        syncConfig = all || CONFIG in options.flags,
        syncPlugins = all || PLUGINS in options.flags,
    )
    if (!sync.syncVmOptions && !sync.syncConfig && !sync.syncPlugins) {
        throw UsageException("At least one of --all, --vmoptions, --config, --plugins is required")
    }
    return sync
}

private class Options(val idePath: String, val projectPath: String, val flags: Set<String>)

/** Parses the required `--ide`/`--path` values and the given value-less [flags]; anything else is rejected. */
private fun parseOptions(args: List<String>, flags: Set<String>): Options {
    var idePath: String? = null
    var projectPath: String? = null
    val givenFlags = mutableSetOf<String>()

    var i = 0
    fun value(flag: String, current: String?): String {
        if (current != null) throw UsageException("$flag is given more than once")
        val value = args.getOrNull(i + 1)
        if (value == null || value.startsWith("--")) throw UsageException("$flag needs a value")
        i++
        return value
    }

    while (i < args.size) {
        when (val arg = args[i]) {
            "--ide" -> idePath = value(arg, idePath)
            "--path" -> projectPath = value(arg, projectPath)
            in flags -> givenFlags += arg
            else -> throw UsageException("Unknown argument: $arg")
        }
        i++
    }

    if (idePath == null) throw UsageException("--ide is required")
    if (projectPath == null) throw UsageException("--path is required")
    return Options(idePath, projectPath, givenFlags)
}
