package com.projectjuggler.synchelper

internal const val USAGE = """Usage: sync-helper --ide <ide-path> --path <project-path> (--all | --vmoptions | --config | --plugins)...

  --ide <ide-path>        Executable of the IDE whose Project Juggler data holds the project
  --path <project-path>   Path of the isolated project to sync
  --all                   Sync VM options, config and plugins
  --vmoptions             Sync VM options
  --config                Sync the config directory
  --plugins               Sync the plugins directory

Exit codes: 0 synced, 1 sync failure, 2 usage error."""

/** Parsed sync-helper command line; see [USAGE]. */
internal data class SyncHelperArgs(
    val idePath: String,
    val projectPath: String,
    val syncVmOptions: Boolean,
    val syncConfig: Boolean,
    val syncPlugins: Boolean,
)

/** Invalid sync-helper command line; the message says what is wrong. */
internal class UsageException(message: String) : Exception(message)

/**
 * Parses [args] per [USAGE]. `--ide`, `--path` and at least one sync-type flag are required; unknown tokens,
 * a value flag without a value, and a repeated `--ide`/`--path` are rejected.
 *
 * @throws UsageException when [args] do not follow [USAGE]
 */
internal fun parseArgs(args: Array<String>): SyncHelperArgs {
    var idePath: String? = null
    var projectPath: String? = null
    var syncVmOptions = false
    var syncConfig = false
    var syncPlugins = false

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
            "--all" -> {
                syncVmOptions = true
                syncConfig = true
                syncPlugins = true
            }
            "--vmoptions" -> syncVmOptions = true
            "--config" -> syncConfig = true
            "--plugins" -> syncPlugins = true
            else -> throw UsageException("Unknown argument: $arg")
        }
        i++
    }

    if (idePath == null) throw UsageException("--ide is required")
    if (projectPath == null) throw UsageException("--path is required")
    if (!syncVmOptions && !syncConfig && !syncPlugins) {
        throw UsageException("At least one of --all, --vmoptions, --config, --plugins is required")
    }
    return SyncHelperArgs(idePath, projectPath, syncVmOptions, syncConfig, syncPlugins)
}
