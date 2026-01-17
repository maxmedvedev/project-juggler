package com.projectjuggler.plugin

import com.intellij.ide.AppLifecycleListener
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.di.pluginModule

/**
 * Initializes Koin when the IDE starts.
 */
internal class PluginStartup : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        // todo not sure if this works when plugin is installed dynamically
        KoinInit.init(pluginModule)
    }
}
