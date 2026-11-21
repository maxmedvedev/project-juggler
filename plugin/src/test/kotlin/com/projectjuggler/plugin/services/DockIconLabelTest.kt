package com.projectjuggler.plugin.services

import com.intellij.ide.AppLifecycleListener
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.startup.DockIconLabelAppListener
import com.projectjuggler.plugin.test.elements
import com.projectjuggler.plugin.test.pluginXml
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class DockIconLabelTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectsDir = createTempDir("projects")
    val baseConfigDir = createTempDir("base-config")
    val otherConfigDir = createTempDir("other-config")

    /** A fresh repository whose base config is [baseConfigDir], i.e. the main instance runs on it. */
    fun newRepository(): IdeConfigRepository = testRepository(createTempDirectory(baseDir, "ide")).apply {
        update { it.copy(baseConfigPath = baseConfigDir.toString()) }
    }

    /** One px per character per font point, so fitting is predictable. */
    val fixedWidth: (String, Float) -> Int = { text, size -> (text.length * size).toInt() }

    fun solidIcon(size: Int, rgb: Int = 0xFF3366CC.toInt()) =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            for (x in 0 until size) for (y in 0 until size) setRGB(x, y, rgb)
        }

    "dockIconLabel is null in the main instance without a main project" {
        dockIconLabel(newRepository(), projectId = null, baseConfigDir).shouldBeNull()
    }

    "dockIconLabel is the main project's name in the main instance" {
        val repository = newRepository()
        val mainProject = projectsDir.resolve("dev").createDirectories()
        MainProjectService.setMainProject(repository, ProjectPath(mainProject.toString()))

        dockIconLabel(repository, projectId = null, baseConfigDir) shouldBe "dev"
    }

    "dockIconLabel is null in a non-isolated IDE that does not run on the base config" {
        val repository = newRepository()
        MainProjectService.setMainProject(repository, ProjectPath(projectsDir.resolve("dev").toString()))

        dockIconLabel(repository, projectId = null, otherConfigDir).shouldBeNull()
    }

    "dockIconLabel is null for a project without metadata" {
        dockIconLabel(newRepository(), ProjectId("unknown-0123456789abcdef"), otherConfigDir).shouldBeNull()
    }

    "dockIconLabel is the project display name" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectsDir.resolve("juggler").createDirectories())

        dockIconLabel(repository, metadata.id, otherConfigDir) shouldBe "juggler"
    }

    "dockIconLabel includes the selected file of a file-based project" {
        val repository = newRepository()
        val repo = projectsDir.resolve("repo").createDirectories()
        val moduleFile = repo.resolve("MODULE.bazel").apply { writeText("") }
        val metadata = registerProject(repository, moduleFile)

        dockIconLabel(repository, metadata.id, otherConfigDir) shouldBe "repo [MODULE.bazel]"
    }

    "dockIconLabel is null when the setting is off, in an isolated and in the main instance" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectsDir.resolve("off").createDirectories())
        MainProjectService.setMainProject(repository, ProjectPath(projectsDir.resolve("dev").toString()))
        repository.update { it.copy(showProjectNameOnDockIcon = false) }

        dockIconLabel(repository, metadata.id, otherConfigDir).shouldBeNull()
        dockIconLabel(repository, projectId = null, baseConfigDir).shouldBeNull()
    }

    "fitLabel keeps a short label at the largest font size" {
        fitLabel("abc", maxWidth = 100, maxFontSize = 10f, minFontSize = 5f, width = fixedWidth) shouldBe
            FittedLabel("abc", 10f)
    }

    "fitLabel shrinks the font for a medium label" {
        fitLabel("abcdefghijklmn", maxWidth = 100, maxFontSize = 10f, minFontSize = 5f, width = fixedWidth) shouldBe
            FittedLabel("abcdefghijklmn", 7f)
    }

    "fitLabel ellipsizes a long label at the smallest font size" {
        val fitted = fitLabel("a".repeat(40), maxWidth = 100, maxFontSize = 10f, minFontSize = 5f, width = fixedWidth)

        fitted.fontSize shouldBe 5f
        fitted.text shouldStartWith "aaa"
        fitted.text shouldEndWith "…"
        fixedWidth(fitted.text, 5f) shouldBeLessThanOrEqual 100
        fitted.text.length shouldBe 20
    }

    "fitLabel falls back to an ellipsis when nothing fits" {
        fitLabel("abc", maxWidth = 1, maxFontSize = 10f, minFontSize = 5f, width = fixedWidth) shouldBe
            FittedLabel("…", 5f)
    }

    "renderLabeledIcon draws the banner at the top and leaves the rest and the base untouched" {
        for (size in listOf(128, 256)) {
            val base = solidIcon(size)
            val labeled = renderLabeledIcon(base, "project-juggler")

            labeled.width shouldBe size
            labeled.height shouldBe size
            // Banner middle-left changed, the base image did not
            labeled.getRGB(size / 8, size / 6) shouldNotBe base.getRGB(size / 8, size / 6)
            base.getRGB(size / 8, size / 6) shouldBe 0xFF3366CC.toInt()
            val firstUntouchedRow = (size * BANNER_BOTTOM).toInt() + 1
            for (y in firstUntouchedRow until size) for (x in 0 until size) {
                labeled.getRGB(x, y) shouldBe base.getRGB(x, y)
            }
        }
    }

    "largestVariant picks the largest resolution of a multi-resolution image" {
        val small = solidIcon(128)
        val large = solidIcon(256)

        largestVariant(BaseMultiResolutionImage(small, large)) shouldBe large
        largestVariant(small) shouldBe small
    }

    "plugin.xml registers the Dock icon listener" {
        val listeners = pluginXml().elements("listener")
            .filter { it.parentNode.nodeName == "applicationListeners" }
            .map { it.getAttribute("class") to it.getAttribute("topic") }

        listeners shouldContain (DockIconLabelAppListener::class.java.name to AppLifecycleListener::class.java.name)
    }
})
