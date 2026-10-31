package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.plugin.ProjectJugglerBundle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SyncTypeTest : StringSpec({

    "All syncs everything" {
        SyncType.All.syncVmOptions shouldBe true
        SyncType.All.syncConfig shouldBe true
        SyncType.All.syncPlugins shouldBe true
    }

    "VmOptions syncs only VM options" {
        SyncType.VmOptions.syncVmOptions shouldBe true
        SyncType.VmOptions.syncConfig shouldBe false
        SyncType.VmOptions.syncPlugins shouldBe false
    }

    "Config syncs only configuration" {
        SyncType.Config.syncVmOptions shouldBe false
        SyncType.Config.syncConfig shouldBe true
        SyncType.Config.syncPlugins shouldBe false
    }

    "Plugins syncs only plugins" {
        SyncType.Plugins.syncVmOptions shouldBe false
        SyncType.Plugins.syncConfig shouldBe false
        SyncType.Plugins.syncPlugins shouldBe true
    }

    "cli flags match the sync-helper options" {
        SyncType.All.cliFlag shouldBe "--all"
        SyncType.VmOptions.cliFlag shouldBe "--vmoptions"
        SyncType.Config.cliFlag shouldBe "--config"
        SyncType.Plugins.cliFlag shouldBe "--plugins"
    }

    "display names come from the bundle" {
        SyncType.All.displayName shouldBe ProjectJugglerBundle.message("sync.type.all")
        SyncType.VmOptions.displayName shouldBe ProjectJugglerBundle.message("sync.type.vmoptions")
        SyncType.Config.displayName shouldBe ProjectJugglerBundle.message("sync.type.config")
        SyncType.Plugins.displayName shouldBe ProjectJugglerBundle.message("sync.type.plugins")
    }
})
