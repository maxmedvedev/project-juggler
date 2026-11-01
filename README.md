# project-juggler

An IntelliJ plugin that opens each project in its own IDE instance with an isolated configuration. Every project gets separate config, system, logs and plugins directories, copied from your main IDE on first open, while sharing the same IDE installation.

## Features

- **Isolated instances**: separate config, system, logs and plugins per project; caches of one project never slow down another
- **Sync from base**: update VM options, configuration or plugins of one or all projects from the main IDE; running instances close and reopen automatically, including the one you are working in
- **Main project**: one project opens in the main IDE with the base settings, no isolation
- **Recent Juggled Projects popup** (`Cmd/Ctrl+Shift+P`): reopen or focus a project, browse for a new one, open it with another installed IDE
- **Open a Project Copy**: create a git worktree on a new branch and open it isolated
- **Keeps up with the IDE**: offers a plugin sync after an IDE update, warns when stored base paths drift, imports recent projects from the IDE
- **Settings page**: Settings | Tools | Project Juggler
- **Cross-platform**: macOS, Linux and Windows

## Installation

Install the plugin from the JetBrains Marketplace or build it from source (below).

## Usage

### Recent Juggled Projects (`Cmd/Ctrl+Shift+P`)

The main entry point. The popup lists the main project and the recently juggled projects with their git branch, a green badge when the instance is running, and a star on the main project.

Top-level items:

- **Browse...** opens a file-or-folder chooser (a file such as `MODULE.bazel` is a valid project)
- **Sync all projects** / **Sync VM Options / Configuration / Plugins (all projects)**
- **Offer plugin sync on IDE update** toggle
- **Import recent projects from IntelliJ...** when nothing is tracked yet

Right-arrow or click the arrow on a project for its actions:

- **Open Project**: launch the isolated instance, or focus it if it is already running
- **Open with <IDE>**: the same with another installed IDE that the plugin has run in
- **Set / Unset Main Project**
- **Sync All Settings / VM Options / Configuration / Plugins**
- **Remove Project**: delete the project's isolated data (the project files are not touched)

### Main project

One project can be the main project. It opens in the main IDE instance with the base settings, has nothing to sync, and is shown with a star. The first time you open a project from the main IDE, the plugin asks whether to make it the main project; the question can be switched off and back on in the settings.

### Sync

Sync copies the base VM options, config directory or plugins directory into a project's isolated directories. A running instance is asked to close, synced, and reopened. When you sync the project you are working in, a small bundled helper runs the sync from outside: the IDE closes, the helper syncs and relaunches it, and the relaunched IDE shows the result.

### Open a Project Copy

File | Open a Project Copy creates a git worktree of a repository on a new branch and opens it as an isolated project.

### Settings

Settings | Tools | Project Juggler shows the detected IDE, lets you set or clear the main project, the number of recent projects to keep, the main-project prompt, and the base config, plugins and VM options paths. "Use current IDE paths" fills them from the running IDE.

## How it works

1. **First open** of a project: a stable id is derived from the path, isolated `config/`, `system/`, `logs/` and `plugins/` directories are created, base config and plugins are copied in, and an `idea.vmoptions` is generated from the base file with the isolated paths appended (and a per-project debug port when the base enables JDWP).
2. **Later opens** keep the isolated settings. The VM options file is regenerated when the base file changed since it was last written; otherwise only the path block is refreshed.
3. **Sync** replaces the chosen parts from the base, stopping and restarting a running instance through a stop-request file that the isolated IDE polls.

## Opening projects from other apps

Launchers, scripts and terminals can open a juggled project the way the Recent Projects popup does: focus the window of
its running IDE, or launch it. The plugin writes a launcher script at every IDE start:

```bash
~/.project-juggler/bin/sync-helper open --ide <ide-executable> --path <project-dir>
```

`--ide` is the IDE executable (e.g. `/Applications/IntelliJ IDEA.app/Contents/MacOS/idea`); that IDE must have run the
plugin once. Exit code 0 means launched or focused, 1 a failure (message on stderr), 2 bad arguments. On Windows the
script is `sync-helper.cmd`. With several IDEs, the script belongs to the one started last, but each command runs in
the helper and JBR of the IDE given with `--ide`.

## Data storage

Everything lives under `~/.project-juggler/` (override with `-Dproject.juggler.base.dir=<dir>`), keyed by IDE installation:

```
~/.project-juggler/
  bin/sync-helper                # launcher for other apps (`sync-helper open ...`)
  launcher-logs/                 # output of launched IDEs that failed to start
  v2/<ide-dir>/
    config.json                  # base paths, main project, limits for this IDE installation
    recent.json                  # recent projects of this installation
    projects/<project-id>/
      metadata.json              # path, open count, debug port
      idea.vmoptions             # generated VM options
      config/ system/ logs/ plugins/
```

The behavioral specifications in `docs/` (`core-spec.md`, `plugin-spec.md`, `sync-helper-spec.md`) describe the details.

## Building from source

```bash
./gradlew build                  # Build all modules
./gradlew test                   # Run all tests
./gradlew :plugin:buildPlugin    # Build the plugin zip
./gradlew :plugin:runIde         # Run a sandbox IDE with the plugin
```

Gradle runs on a JDK 17 pinned in `gradle.properties`; adjust `org.gradle.java.home` for your machine.

## Contributing

Contributions are welcome. Please feel free to submit a pull request.

## License

MIT licence.

## Acknowledgments

- Uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON handling and [Koin](https://insert-koin.io/) for dependency injection
- Tested with [Kotest](https://kotest.io/) and [MockK](https://mockk.io/)
