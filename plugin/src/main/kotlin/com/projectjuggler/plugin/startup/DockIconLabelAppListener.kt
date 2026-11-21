package com.projectjuggler.plugin.startup

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IsolatedInstance
import com.projectjuggler.plugin.services.dockIconLabel
import com.projectjuggler.plugin.services.largestVariant
import com.projectjuggler.plugin.services.renderLabeledIcon
import java.awt.Taskbar

/**
 * On macOS, draws a project name on this IDE's Dock icon ([renderLabeledIcon]) so the instances can be told apart:
 * the isolated instance's project, or the main project in the main instance ([dockIconLabel]). Does nothing when
 * there is no such name or the setting is off.
 *
 * Runs at frame creation, before a project opens: IntelliJ's `AppIcon` caches the Dock image the first time it draws
 * progress or a badge and restores that image afterwards, so the label must be in place by then to survive. When the
 * plugin is loaded dynamically, the label appears from the next start. The hover name stays the bundle's name.
 */
internal class DockIconLabelAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        if (!SystemInfo.isMac) return
        if (!Taskbar.isTaskbarSupported() || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) return
        val projectId = IsolatedInstance.currentProjectId()

        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            try {
                val repository = IdeInstallationService.currentIdeConfigRepository
                val label = dockIconLabel(repository, projectId, PathManager.getConfigDir())
                    ?: return@executeOnPooledThread
                application.invokeLater({ setDockIconLabel(label) }, ModalityState.any())
            } catch (t: Throwable) {
                t.rethrowIfControlFlow()
                LOG.warn("Failed to read the Dock icon label", t)
            }
        }
    }

    private fun setDockIconLabel(label: String) {
        try {
            val taskbar = Taskbar.getTaskbar()
            val base = taskbar.iconImage ?: return
            taskbar.iconImage = renderLabeledIcon(largestVariant(base), label)
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.warn("Failed to draw the project name on the Dock icon", t)
        }
    }
}

private val LOG = logger<DockIconLabelAppListener>()
