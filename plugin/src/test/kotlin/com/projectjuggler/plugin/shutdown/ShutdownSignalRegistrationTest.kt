package com.projectjuggler.plugin.shutdown

import com.intellij.ide.AppLifecycleListener
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class ShutdownSignalRegistrationTest : StringSpec({

    "plugin.xml registers the app lifecycle listener that starts stop-request monitoring" {
        // The platform jars on the test classpath carry their own META-INF/plugin.xml; pick this plugin's
        val document = ShutdownSignalRegistrationTest::class.java.classLoader.getResources("META-INF/plugin.xml")
            .asSequence()
            .map { url -> url.openStream().use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) } }
            .firstOrNull { it.getElementsByTagName("id").item(0)?.textContent == "maxmedvedev.project-juggler" }
        document.shouldNotBeNull()

        val appListeners = document.getElementsByTagName("applicationListeners")
        val listeners = (0 until appListeners.length)
            .map { appListeners.item(it) as Element }
            .flatMap { group ->
                val nodes = group.getElementsByTagName("listener")
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
            .map { it.getAttribute("class") to it.getAttribute("topic") }

        listeners shouldContain (ShutdownSignalAppListener::class.java.name to AppLifecycleListener::class.java.name)
    }
})
