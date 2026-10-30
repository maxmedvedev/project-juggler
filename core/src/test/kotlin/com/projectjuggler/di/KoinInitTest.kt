package com.projectjuggler.di

import com.projectjuggler.config.IdeRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

class KoinInitTest : StringSpec({
    afterTest {
        KoinInit.stop()
    }

    "init twice does not throw and core bindings resolve" {
        KoinInit.init()
        KoinInit.init()

        GlobalContext.get().get<IdeRegistry>() shouldNotBe null
    }

    "init with an extra module on a running Koin loads the module" {
        KoinInit.init()

        KoinInit.init(module { single(named("extra")) { "extra-value" } })

        GlobalContext.get().get<String>(named("extra")) shouldBe "extra-value"
    }

    "stop clears the global context" {
        KoinInit.init()

        KoinInit.stop()

        GlobalContext.getOrNull() shouldBe null
    }
})
