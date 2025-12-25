package com.ideajuggler.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object HashUtils {
    fun calculateFileHash(file: Path): String {
        val bytes = Files.readAllBytes(file)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun calculateStringHash(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
