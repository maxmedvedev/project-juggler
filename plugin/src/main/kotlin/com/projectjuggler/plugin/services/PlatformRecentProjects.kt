package com.projectjuggler.plugin.services

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

/**
 * The only place that touches [RecentProjectsManagerBase]: public and not internal, but an implementation class,
 * so another [RecentProjectsManager] (remote dev, thin client) gets the fallbacks instead of a `ClassCastException`.
 */
internal object PlatformRecentProjects {
    private val LOG = logger<PlatformRecentProjects>()
    private val loggedUnsupported = AtomicBoolean()

    /** The platform's recent project paths, or an empty list when the manager does not expose them. */
    fun recentPaths(manager: RecentProjectsManager = RecentProjectsManager.getInstance()): List<String> =
        base(manager)?.getRecentPaths().orEmpty()

    /** The platform's icon for the project at [path], or the generic project icon. */
    fun projectIcon(path: String, manager: RecentProjectsManager = RecentProjectsManager.getInstance()): Icon =
        base(manager)?.getProjectIcon(path, true) ?: AllIcons.Nodes.IdeaProject

    private fun base(manager: RecentProjectsManager): RecentProjectsManagerBase? {
        val base = manager as? RecentProjectsManagerBase
        if (base == null && loggedUnsupported.compareAndSet(false, true)) {
            LOG.info("${manager.javaClass.name} is not a RecentProjectsManagerBase; no recent paths or project icons")
        }
        return base
    }
}
