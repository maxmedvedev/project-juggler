package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class IsolatedInstanceTest : StringSpec({
    extensions(KoinTestExtension())

    val repository = testRepository(createTempDir("juggler"))
    val projectDir = createTempDir("project")
    val otherDir = createTempDir("other-project")

    "extracts the project id from an isolated config path" {
        IsolatedInstance.projectIdFromConfigPath("/b/v2/ide/projects/p-1a2b/config") shouldBe ProjectId("p-1a2b")
    }

    "accepts a trailing slash" {
        IsolatedInstance.projectIdFromConfigPath("/b/v2/ide/projects/p-1a2b/config/") shouldBe ProjectId("p-1a2b")
    }

    "returns null for a directory other than config under the project" {
        IsolatedInstance.projectIdFromConfigPath("/b/v2/ide/projects/x/system").shouldBeNull()
    }

    "returns null for a config path without a projects segment" {
        IsolatedInstance.projectIdFromConfigPath("/home/u/.config/JetBrains/IntelliJIdea2025.1").shouldBeNull()
    }

    "does not recognise Windows separators (gap 2)" {
        // plugin-spec gap 2: the path is split on '/' only, so isolated detection fails on Windows
        IsolatedInstance.projectIdFromConfigPath("C:\\b\\projects\\x\\config").shouldBeNull()
    }

    "isCurrentProject is true for the registered project whose id matches" {
        val metadata = registerProject(repository, projectDir)

        IsolatedInstance.isCurrentProject(repository, metadata.path, currentProjectId = metadata.id) shouldBe true
    }

    "isCurrentProject is false for a registered project with another id" {
        val metadata = registerProject(repository, projectDir)

        IsolatedInstance.isCurrentProject(repository, metadata.path, currentProjectId = ProjectId("other")) shouldBe false
    }

    "isCurrentProject is false for an unregistered project even when the id matches" {
        val unregistered = ProjectPath(otherDir.toString())

        IsolatedInstance.isCurrentProject(repository, unregistered, currentProjectId = unregistered.id) shouldBe false
    }

    "isCurrentProject is false in the main instance" {
        val metadata = registerProject(repository, projectDir)

        IsolatedInstance.isCurrentProject(repository, metadata.path, currentProjectId = null) shouldBe false
    }
})
