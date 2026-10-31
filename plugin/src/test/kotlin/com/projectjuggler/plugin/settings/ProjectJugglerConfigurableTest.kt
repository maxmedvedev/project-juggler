package com.projectjuggler.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBIntSpinner
import com.intellij.util.ui.UIUtil
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.services.CurrentIdePaths
import com.projectjuggler.plugin.test.TestApplicationExtension
import com.projectjuggler.plugin.test.elements
import com.projectjuggler.plugin.test.pluginXml
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import javax.swing.JComponent

class ProjectJugglerConfigurableTest : StringSpec({
    extensions(KoinTestExtension(), TestApplicationExtension())

    val repository = testRepository(createTempDir("juggler"))
    val configDir = createTempDir("ide-config")
    val current = CurrentIdePaths(configDir, createTempDir("ide-plugins"), configDir.resolve("idea.vmoptions"))

    "the page builds and reset reloads config.json" {
        repository.update { it.copy(baseConfigPath = configDir.toString(), maxRecentProjects = 5) }
        val configurable = ProjectJugglerConfigurable({ repository }, { current })

        ApplicationManager.getApplication().invokeAndWait {
            try {
                val component = configurable.createComponent().shouldNotBeNull()
                configurable.reset()
                configurable.isModified shouldBe false
                component.recentLimitSpinner().value shouldBe 5

                repository.update { it.copy(maxRecentProjects = 7) }
                configurable.reset()
                configurable.isModified shouldBe false
                component.recentLimitSpinner().value shouldBe 7
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    "plugin.xml registers the page under Tools" {
        val configurables = pluginXml().elements("applicationConfigurable")
            .map { Triple(it.getAttribute("instance"), it.getAttribute("id"), it.getAttribute("parentId")) }

        configurables shouldContain
            Triple(ProjectJugglerConfigurable::class.java.name, ProjectJugglerConfigurable.ID, "tools")
    }
})

// The page has one spinner: the recent projects limit
private fun JComponent.recentLimitSpinner(): JBIntSpinner =
    UIUtil.findComponentsOfType(this, JBIntSpinner::class.java).single()
