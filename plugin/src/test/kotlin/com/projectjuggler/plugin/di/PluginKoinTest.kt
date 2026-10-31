package com.projectjuggler.plugin.di

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.di.KoinInit
import com.projectjuggler.di.coreModule
import com.projectjuggler.di.ideScopedModule
import com.projectjuggler.plugin.services.IdeInstallationService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/** Manages Koin itself (no `KoinTestExtension`); never touches `currentRepository`, which needs `PathManager`. */
class PluginKoinTest : StringSpec({
    beforeTest { stopKoin() }
    afterSpec { KoinInit.stop() }

    "getInstance starts Koin when nothing started it" {
        IdeInstallationService.getInstance().shouldNotBeNull()

        GlobalContext.get().getOrNull<IdeRegistry>().shouldNotBeNull()
    }

    "getInstance twice returns the same instance" {
        IdeInstallationService.getInstance() shouldBeSameInstanceAs IdeInstallationService.getInstance()
    }

    "getInstance loads pluginModule into a Koin started without it" {
        // What KoinTestExtension starts
        startKoin { modules(coreModule, ideScopedModule) }

        IdeInstallationService.getInstance().shouldNotBeNull()
    }
})
