package com.projectjuggler.plugin

import com.projectjuggler.plugin.actions.recent.SyncType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.ResourceBundle

class ProjectJugglerBundleTest : StringSpec({

    val bundle = ResourceBundle.getBundle(BUNDLE)

    "the keys for formerly hardcoded strings are present" {
        listOf(
            "sync.type.all",
            "sync.type.vmoptions",
            "sync.type.config",
            "sync.type.plugins",
            "notification.info.sync.self.shutdown.project",
            "notification.info.sync.self.shutdown.all",
            "notification.error.sync.self.shutdown.failed",
            "ide.path.kind.config",
            "ide.path.kind.plugins",
            "ide.path.kind.vmoptions",
            "popup.recent.projects.action.open.with",
            "notification.error.launch.no.output",
            "error.unknown",
        ).forEach { key ->
            bundle.containsKey(key) shouldBe true
        }
    }

    "sync type names come from the sync.type keys" {
        mapOf(
            SyncType.All to "sync.type.all",
            SyncType.VmOptions to "sync.type.vmoptions",
            SyncType.Config to "sync.type.config",
            SyncType.Plugins to "sync.type.plugins",
        ).forEach { (type, key) ->
            type.displayName shouldBe ProjectJugglerBundle.message(key)
        }
    }

    "the self-shutdown texts keep their wording" {
        ProjectJugglerBundle.message("notification.info.sync.self.shutdown.project", SyncType.Plugins.displayName) shouldBe
            "IntelliJ will close to sync plugins and reopen automatically..."
        ProjectJugglerBundle.message("notification.info.sync.self.shutdown.all") shouldBe
            "IntelliJ will close to sync all projects and reopen automatically..."
    }
})
