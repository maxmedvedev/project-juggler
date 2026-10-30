package com.projectjuggler.di

import io.kotest.core.listeners.TestListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest

/**
 * Kotest extension for Koin-based tests.
 * Starts Koin before each spec and stops it after.
 * Cleans up scopes between tests to avoid stale state.
 */
class KoinTestExtension : TestListener, KoinTest {

    override suspend fun beforeSpec(spec: Spec) {
        startKoin {
            modules(coreModule, ideScopedModule)
        }
    }

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        // Clean up scopes between tests to avoid stale state
        IdeScopeManager.getInstance().closeAllScopes()
    }

    override suspend fun afterSpec(spec: Spec) {
        IdeScopeManager.reset()
        stopKoin()
    }
}
