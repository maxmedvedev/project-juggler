package com.projectjuggler.plugin.shutdown

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.service

/**
 * Starts [ShutdownSignalService] at application start, before IntelliJ decides between opening a project and showing
 * the welcome screen, so an isolated IDE without an open project still honors stop requests.
 */
internal class ShutdownSignalAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        service<ShutdownSignalService>().start()
    }
}
