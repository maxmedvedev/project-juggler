package com.projectjuggler.plugin.actions.recent

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

    "display names" {
        SyncType.All.displayName shouldBe "all settings"
        SyncType.VmOptions.displayName shouldBe "VM options"
        SyncType.Config.displayName shouldBe "configuration"
        SyncType.Plugins.displayName shouldBe "plugins"
    }
})
