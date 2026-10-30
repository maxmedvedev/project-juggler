package com.projectjuggler.di

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

class IdeScopeManagerTest : StringSpec({
    extensions(KoinTestExtension())

    val installation = IdeInstallation("/test/ide", "Test IDE")

    fun newRepository(installation: IdeInstallation) = IdeConfigRepository(createTempDir("test-scope"), installation)

    "repositories with the same installation share one scope" {
        ProjectManager.getInstance(newRepository(installation)) shouldBeSameInstanceAs
            ProjectManager.getInstance(newRepository(installation))
    }

    "different installations get different scopes" {
        val other = IdeInstallation("/other/ide", "Other IDE")

        ProjectManager.getInstance(newRepository(installation)) shouldNotBeSameInstanceAs
            ProjectManager.getInstance(newRepository(other))
    }

    "closeScope makes the next lookup create a new instance" {
        val repository = newRepository(installation)
        val before = ProjectManager.getInstance(repository)

        IdeScopeManager.getInstance().closeScope(installation)

        ProjectManager.getInstance(repository) shouldNotBeSameInstanceAs before
    }

    "closeAllScopes makes the next lookup create a new instance" {
        val repository = newRepository(installation)
        val before = ProjectManager.getInstance(repository)

        IdeScopeManager.getInstance().closeAllScopes()

        ProjectManager.getInstance(repository) shouldNotBeSameInstanceAs before
    }
})
