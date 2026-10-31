package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.MainProjectService
import java.nio.file.Path

/**
 * Other IDE installations a project can be opened with: not the current one, base settings already captured.
 *
 * An installation counts as current when its `directoryName` matches [current] or its executable lies under
 * [currentHome] (the same IDE can be keyed differently, see gap 10). Only installations whose repository has
 * `baseConfigPath` set (the plugin ran there once) are offered: for the others core would fall back to the newest
 * config directory and plugins on the machine, which may belong to a different IDE version. Sorted by display name.
 * Reads each repository's `config.json`; creates nothing.
 */
internal fun openWithInstallations(
    all: List<IdeInstallation>,
    current: IdeInstallation,
    currentHome: Path,
    repositoryFor: (IdeInstallation) -> IdeConfigRepository,
): List<IdeInstallation> =
    all
        .filterNot { it.directoryName == current.directoryName }
        .filterNot { Path.of(it.executablePath).toAbsolutePath().normalize().startsWith(currentHome) }
        .filter { repositoryFor(it).load().baseConfigPath != null }
        .sortedBy { it.displayName }

/**
 * Whether launching against [target] should first offer to make the project the main one. Only for the current
 * installation's repository ([current]): the prompt is about the running IDE's main instance.
 */
internal fun shouldPromptForMainProject(
    target: IdeConfigRepository,
    current: IdeConfigRepository,
    currentConfigDir: Path,
): Boolean =
    target.installation.directoryName == current.installation.directoryName &&
        MainProjectService.shouldPromptForMainProject(target, currentConfigDir)
