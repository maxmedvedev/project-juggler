package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ProjectCleanerTest : StringSpec({
    extensions(KoinTestExtension())

    lateinit var repository: IdeConfigRepository
    lateinit var projectManager: ProjectManager
    lateinit var directoryManager: DirectoryManager
    lateinit var cleaner: ProjectCleaner
    var mainPath = ProjectPath("/unset")
    var otherPath = ProjectPath("/unset")

    beforeEach {
        repository = IdeConfigRepository(createTempDir("test-cleaner"), IdeInstallation("/test/ide", "Test IDE"))
        projectManager = ProjectManager.getInstance(repository)
        directoryManager = DirectoryManager.getInstance(repository)
        cleaner = ProjectCleaner.getInstance(repository)
        mainPath = projectManager.resolvePath(createTempDir("test-main").toString())
        otherPath = projectManager.resolvePath(createTempDir("test-other").toString())

        // Both projects were opened isolated at some point
        for (path in listOf(mainPath, otherPath)) {
            val metadata = projectManager.registerOrUpdate(path)
            directoryManager.ensureProjectDirectories(metadata)
            RecentProjectsIndex.getInstance(repository).recordOpen(path)
        }
    }

    "cleanStaleMainProjectEntry should do nothing when no main project is set" {
        cleaner.cleanStaleMainProjectEntry().shouldBeNull()
        projectManager.listAll().size shouldBe 2
    }

    "cleanStaleMainProjectEntry should do nothing when the main project was never tracked" {
        MainProjectService.setMainProject(repository, projectManager.resolvePath(createTempDir("untracked").toString()))
        cleaner.cleanStaleMainProjectEntry().shouldBeNull()
        projectManager.listAll().size shouldBe 2
    }

    "cleanStaleMainProjectEntry should remove the main project's entry, directories and recent item" {
        MainProjectService.setMainProject(repository, mainPath)
        val mainRoot = directoryManager.getProjectRoot(mainPath)
        mainRoot.exists() shouldBe true

        val removed = cleaner.cleanStaleMainProjectEntry()

        removed?.path shouldBe mainPath
        mainRoot.exists() shouldBe false
        projectManager.listAll().map { it.path } shouldContainExactly listOf(otherPath)
        RecentProjectsIndex.getInstance(repository).getRecent(10).map { it.path } shouldContainExactly listOf(otherPath)
        // Second call is a no-op
        cleaner.cleanStaleMainProjectEntry().shouldBeNull()
    }

    "cleanStaleMainProjectEntry should keep the entry while its isolated instance is running" {
        MainProjectService.setMainProject(repository, mainPath)
        val lock = directoryManager.getProjectRoot(mainPath).resolve("config/.lock")
        lock.parent.createDirectories()
        lock.writeText("12345")

        cleaner.cleanStaleMainProjectEntry().shouldBeNull()
        projectManager.listAll().size shouldBe 2
        directoryManager.getProjectRoot(mainPath).exists() shouldBe true
    }

    "cleanProject removes the recent entry and the project root, leaving other projects intact" {
        val mainRoot = directoryManager.getProjectRoot(mainPath)
        val otherRoot = directoryManager.getProjectRoot(otherPath)

        cleaner.cleanProject(projectManager.get(mainPath)!!)

        mainRoot.exists() shouldBe false
        otherRoot.exists() shouldBe true
        val recentJson = repository.baseDir.resolve("recent.json").readText()
        recentJson shouldNotContain mainPath.id.id
        recentJson shouldContain otherPath.id.id
        RecentProjectsIndex.getInstance(repository).getRecent(10).map { it.path } shouldContainExactly listOf(otherPath)
    }
})
