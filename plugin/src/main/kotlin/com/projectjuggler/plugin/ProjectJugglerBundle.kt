package com.projectjuggler.plugin

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

internal const val BUNDLE = "messages.ProjectJugglerBundle"

object ProjectJugglerBundle : DynamicBundle(BUNDLE) {

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }
}
