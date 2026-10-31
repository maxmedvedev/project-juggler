package com.projectjuggler.plugin.shutdown

import com.intellij.ide.AppLifecycleListener
import com.projectjuggler.plugin.test.elements
import com.projectjuggler.plugin.test.pluginXml
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain

class ShutdownSignalRegistrationTest : StringSpec({

    "plugin.xml registers the app lifecycle listener that starts stop-request monitoring" {
        val listeners = pluginXml().elements("listener")
            .filter { it.parentNode.nodeName == "applicationListeners" }
            .map { it.getAttribute("class") to it.getAttribute("topic") }

        listeners shouldContain (ShutdownSignalAppListener::class.java.name to AppLifecycleListener::class.java.name)
    }
})
