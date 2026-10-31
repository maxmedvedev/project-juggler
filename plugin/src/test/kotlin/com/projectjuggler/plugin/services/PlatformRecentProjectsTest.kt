package com.projectjuggler.plugin.services

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class PlatformRecentProjectsTest : StringSpec({
    "another manager implementation has no recent paths" {
        PlatformRecentProjects.recentPaths(mockk<RecentProjectsManager>()).shouldBeEmpty()
    }

    "another manager implementation gets the generic project icon" {
        PlatformRecentProjects.projectIcon("/x", mockk<RecentProjectsManager>()) shouldBe AllIcons.Nodes.IdeaProject
    }

    "the platform manager's recent paths are passed through" {
        val manager = mockk<RecentProjectsManagerBase> { every { getRecentPaths() } returns listOf("/a") }

        PlatformRecentProjects.recentPaths(manager) shouldContainExactly listOf("/a")
    }
})
