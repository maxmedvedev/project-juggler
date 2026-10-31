package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.util.PathUtils
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The editable `IdeConfig` fields of the current IDE as the settings page shows them: unset paths are `""`,
 * `askForMainProject` is the inverse of `dontAskAboutMainProject`. No Swing; the page binds its fields to these.
 */
internal data class JugglerSettings(
    var baseConfigPath: String = "",
    var basePluginsPath: String = "",
    var baseVmOptionsPath: String = "",
    var syncPluginsOnIdeUpdate: Boolean = false,
    var askForMainProject: Boolean = true,
    var mainProjectPath: String = "",
    var maxRecentProjects: Int = 10,
) {
    /** The base path entered for [kind]. */
    fun basePath(kind: IdePathKind): String = when (kind) {
        IdePathKind.CONFIG -> baseConfigPath
        IdePathKind.PLUGINS -> basePluginsPath
        IdePathKind.VM_OPTIONS -> baseVmOptionsPath
    }

    fun setBasePath(kind: IdePathKind, value: String) {
        when (kind) {
            IdePathKind.CONFIG -> baseConfigPath = value
            IdePathKind.PLUGINS -> basePluginsPath = value
            IdePathKind.VM_OPTIONS -> baseVmOptionsPath = value
        }
    }

    companion object {
        val MAX_RECENT_RANGE = 1..50
    }
}

/** The main-project change the settings page applies through [setMainProject] / [clearMainProject]. */
internal sealed interface MainProjectChange {
    data class Set(val path: ProjectPath) : MainProjectChange
    data object Clear : MainProjectChange
}

internal fun loadJugglerSettings(repository: IdeConfigRepository): JugglerSettings {
    val config = repository.load()
    return JugglerSettings(
        syncPluginsOnIdeUpdate = config.syncPluginsOnIdeUpdate,
        askForMainProject = !config.dontAskAboutMainProject,
        mainProjectPath = config.mainProjectPath.orEmpty(),
        maxRecentProjects = config.maxRecentProjects,
    ).apply {
        IdePathKind.entries.forEach { setBasePath(it, it.stored(config).orEmpty()) }
    }
}

/** Bundle error message for the path entered for [kind], or null when valid (blank is valid). */
internal fun validateBasePath(repository: IdeConfigRepository, kind: IdePathKind, input: String): String? {
    if (input.isBlank()) return null
    val path = resolveOrNull(input) ?: return ProjectJugglerBundle.message("settings.error.path.invalid", input)
    return when {
        !Files.exists(path) -> ProjectJugglerBundle.message("settings.error.path.missing", path)
        kind.expectsDirectory && !Files.isDirectory(path) ->
            ProjectJugglerBundle.message("settings.error.path.not.directory", path)
        !kind.expectsDirectory && !Files.isRegularFile(path) ->
            ProjectJugglerBundle.message("settings.error.path.not.file", path)
        DirectoryManager.getInstance(repository).isIsolatedConfigDir(path) ->
            ProjectJugglerBundle.message("settings.error.path.isolated", path)
        else -> null
    }
}

/** Bundle error message for the main project entered, or null when valid (blank clears it). */
internal fun validateMainProject(input: String): String? {
    if (input.isBlank()) return null
    val path = resolveOrNull(input) ?: return ProjectJugglerBundle.message("settings.error.path.invalid", input)
    return if (Files.exists(path)) null else ProjectJugglerBundle.message("settings.error.main.project.missing", path)
}

/**
 * Writes every field except the main project in one `update`; returns the main-project change to apply, if any.
 * Paths are stored absolute and normalized with `~` expanded, blank as null. A changed vmoptions path refreshes
 * the stored vmoptions hash. Inputs must have passed [validateBasePath] / [validateMainProject].
 */
internal fun saveJugglerSettings(repository: IdeConfigRepository, settings: JugglerSettings): MainProjectChange? {
    val paths = IdePathKind.entries.associateWith { kind ->
        settings.basePath(kind).takeIf { it.isNotBlank() }?.let { PathUtils.resolveAbsolute(it.trim()).toString() }
    }
    val mainProject = settings.mainProjectPath.takeIf { it.isNotBlank() }
        ?.let { ProjectManager.getInstance(repository).resolvePath(it.trim()) }

    var vmOptionsChanged = false
    var storedMainProject: String? = null
    repository.update { config ->
        vmOptionsChanged = config.baseVmOptionsPath != paths[IdePathKind.VM_OPTIONS]
        storedMainProject = config.mainProjectPath
        paths.entries.fold(config) { updated, (kind, path) -> kind.withStored(updated, path) }.copy(
            syncPluginsOnIdeUpdate = settings.syncPluginsOnIdeUpdate,
            dontAskAboutMainProject = !settings.askForMainProject,
            maxRecentProjects = settings.maxRecentProjects.coerceIn(JugglerSettings.MAX_RECENT_RANGE),
        )
    }

    if (vmOptionsChanged) {
        BaseVMOptionsTracker.getInstance(repository).updateHash()
    }

    val stored = storedMainProject
    return when {
        mainProject == null -> if (stored != null) MainProjectChange.Clear else null
        stored != null && PathUtils.isSamePath(Path.of(stored), mainProject.path) -> null
        else -> MainProjectChange.Set(mainProject)
    }
}

/** Applies [change] through the same services as the popup's Set / Unset Main Project, with their notifications. */
internal fun applyMainProjectChange(
    repository: IdeConfigRepository,
    change: MainProjectChange,
    notificationHandler: NotificationHandler,
) {
    when (change) {
        is MainProjectChange.Set -> setMainProject(change.path, notificationHandler, repository)
        MainProjectChange.Clear -> clearMainProject(notificationHandler, repository)
    }
}

/** These settings with the three base paths of the running IDE; the vmoptions path only when that file exists. */
internal fun JugglerSettings.withCurrentIdePaths(current: CurrentIdePaths): JugglerSettings = copy().apply {
    IdePathKind.entries
        .filter { it != IdePathKind.VM_OPTIONS || Files.exists(current.vmOptionsFile) }
        .forEach { setBasePath(it, it.current(current).toString()) }
}

private fun resolveOrNull(input: String): Path? = try {
    PathUtils.resolveAbsolute(input.trim())
} catch (_: InvalidPathException) {
    null
}
