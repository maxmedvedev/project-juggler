package com.ideajuggler.core

import java.nio.file.Path

data class ProjectDirectories(
    val root: Path,
    val config: Path,
    val system: Path,
    val logs: Path,
    val plugins: Path
)