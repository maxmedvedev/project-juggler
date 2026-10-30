package com.projectjuggler.plugin.test

import com.intellij.testFramework.common.initTestApplication
import io.kotest.core.listeners.TestListener
import io.kotest.core.spec.Spec

/**
 * Boots IntelliJ's headless test application before the spec runs.
 * The application lives for the rest of the test JVM; there is no teardown.
 * Only specs that need a real `Application` (notifications, `PathManager`, ...) register it.
 */
class TestApplicationExtension : TestListener {
    override suspend fun beforeSpec(spec: Spec) {
        initTestApplication().getOrThrow()
    }
}
