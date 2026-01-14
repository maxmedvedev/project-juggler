# project-juggler

A Kotlin-based CLI tool that manages separate IntelliJ IDEA instances per project with isolated configurations. Each project gets its own config, system, logs, and plugins directories while sharing the same IntelliJ installation.

## Features

- **Isolated Configurations**: Each project has separate config, system, logs, and plugins folders
- **VM Options Management**: Copies your base VM options once on first open, preserving manual edits
- **Auto-Detection**: Automatically detects VM options, IntelliJ config and plugins directories
- **Sync Command**: Explicitly synchronize project settings (VM options, config, plugins) from base
- **Recent Projects**: Quick access to recently opened projects with interactive selection
- **Main Project**: Designate a project as "main" to open it with base settings (no isolation)
- **Cross-Platform**: Supports macOS, Linux, and Windows
- **Zero Dependencies**: Lightweight custom CLI framework with no external dependencies (except Kotlin stdlib)
- **IntelliJ Plugin**: IDE integration with recent projects popup, sync actions, and main project management

## Installation

### Via Homebrew (macOS and Linux)

```bash
brew tap maxmedvedev/project-juggler
brew install project-juggler
```

## Quick Start

### 1. Configure IntelliJ Path (Optional)

```bash
project-juggler config --auto-detect
```

### 2. Open a Project

```bash
project-juggler open ~/projects/my-app
```

That's it! IntelliJ will launch with an isolated configuration for this project.

## Commands

### `open <project-path>`

Open a project with a dedicated IntelliJ instance.

```bash
project-juggler open ~/projects/my-app
project-juggler open /path/to/project
project-juggler open .  # Open current directory
```

**What happens:**
- Generates a stable project ID from the path
- Creates isolated config/system/logs/plugins directories
- **On first open**: Copies base VM options, config, and plugins from base directories
- **On subsequent opens**: Preserves your existing settings (use `sync` to update)
- Generates project-specific VM options file with directory overrides
- Launches IntelliJ with custom directories
- Updates recent projects list

### `main`

Open the main project configured via `config --main-project`.

```bash
project-juggler main
```

**Requirements:**
- Main project must be configured first using `config --main-project <path>`

**What happens:**
- Launches the main project

**Example setup:**
```bash
# First, configure your main project
project-juggler config --main-project ~/work/my-main-project

# Then quickly open it anytime
project-juggler main
```

### `open-copy --source-path <source-path> --destination-path <destination-path> --branch <branch-name>`

Create a git worktree from a source repository and open it in a new IntelliJ instance.

```bash
project-juggler open-copy --source-path /path/to/repo --destination-path /path/to/new-worktree --branch fix-123
```
**What happens:**
- Creates a git worktree with the specified branch name
- Automatically opens the worktree in IntelliJ with an isolated configuration

**Use case:**
Perfect for working on multiple features/branches of the same repository simultaneously, each in its own IntelliJ window with isolated settings.

### `list [--verbose]`

List all tracked projects.

```bash
project-juggler list
project-juggler list --verbose  # Show additional details
project-juggler list -v         # Short form
```
### `recent [--limit N]`

Show recently opened projects with interactive selection.

```bash
project-juggler recent
project-juggler recent --limit 5  # Show only 5 most recent
project-juggler recent -n 5       # Short form
```

**Interactive Selection:**
```
Recently opened projects:

  1. my-app (2 hours ago)
     /Users/max/projects/my-app

  2. other-project (yesterday)
     /Users/max/projects/other-project

Select project number to open (or press Enter to cancel): 1
Opening my-app...
```

### `sync --id <project-id> | --path <project-path> [options]`

Synchronize project settings with base settings.

```bash
# Sync all settings (vmoptions, config, plugins) by project ID
project-juggler sync --id a1b2c3d4e5f6g7h8
project-juggler sync -i a1b2c3d4e5f6g7h8  # Short form

# Sync all settings by project path
project-juggler sync --path ~/projects/my-app
project-juggler sync -p ~/projects/my-app  # Short form

# Sync only VM options
project-juggler sync -i a1b2c3d4e5f6g7h8 --vmoptions

# Sync only config and plugins
project-juggler sync -p ~/projects/my-app --config --plugins

# Force sync all settings
project-juggler sync -i a1b2c3d4e5f6g7h8 --all
```

**Options:**
- `-i, --id <project-id>` - Specify project by ID
- `-p, --path <project-path>` - Specify project by path (supports `~` expansion)
- `--vmoptions` - Sync VM options from base-vmoptions (requires configuration)
- `--config` - Sync config from base-config (auto-detected or configured)
- `--plugins` - Sync plugins from base-plugins (auto-detected or configured)
- `-a, --all` - Sync all settings

**Default behavior (no flags):**
- If base-vmoptions is configured: syncs vmoptions, config, and plugins
- If base-vmoptions is not configured: syncs only config and plugins

**Example output:**
```
Synchronizing project: my-app

  Syncing VM options from: /Users/max/Library/Application Support/JetBrains/IntelliJIdea2026.1/idea.vmoptions
  Syncing config from: /Users/max/Library/Application Support/JetBrains/IntelliJIdea2026.1
  Syncing plugins from: /Users/max/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins

Successfully synchronized project settings.
```

### `clean --id <project-id> | --path <project-path> [--force]`

Clean up configuration folders for a project.

```bash
# By project path
project-juggler clean --path ~/projects/my-app
project-juggler clean -p ~/projects/my-app  # Short form

# Skip confirmation prompt
project-juggler clean -i a1b2c3d4e5f6g7h8 --force
project-juggler clean -p ~/projects/my-app -f  # Short form
```

**Options:**
- `-p, --path <project-path>` - Specify project by path (supports `~` expansion)
- `-f, --force` - Skip confirmation prompt

**What gets deleted:**
- Config directory (`~/.project-juggler/projects/<id>/config`)
- System directory (`~/.project-juggler/projects/<id>/system`)
- Logs directory (`~/.project-juggler/projects/<id>/logs`)
- Plugins directory (`~/.project-juggler/projects/<id>/plugins`)
- VM options file (`~/.project-juggler/projects/<id>/idea.vmoptions`)
- Project metadata
- Recent projects entry

### `config [options]`

Configure global settings.

```bash
# Show current configuration
project-juggler config --show

# Set IntelliJ path
project-juggler config --intellij-path /Applications/IntelliJ\ IDEA.app/Contents/MacOS/idea

# Set base VM options file
project-juggler config --base-vmoptions ~/.config/JetBrains/IntelliJIdea2024.3/idea.vmoptions

# Set base config directory
project-juggler config --base-config ~/.config/JetBrains/IntelliJIdea2024.3

# Set base plugins directory
project-juggler config --base-plugins ~/.config/JetBrains/IntelliJIdea2024.3/plugins

# Set main project
project-juggler config --main-project ~/work/my-main-project
```

**Options:**
- `--show` - Display current configuration
- `--intellij-path <path>` - Path to IntelliJ executable (auto-detected if not set)
- `--base-vmoptions <path>` - Path to base VM options file (must be configured for sync)
- `--base-config <path>` - Path to base config directory (auto-detected if not set)
- `--base-plugins <path>` - Path to base plugins directory (auto-detected if not set)
- `--main-project <path>` - Path to the main project

### Building Distribution

```bash
# Build Homebrew distribution
./gradlew :cli:homebrewDist

# Output: cli/build/distributions/project-juggler-<version>.tar.gz
# Size: ~2.2 MB (no heavy dependencies)

# Generate checksum for Homebrew formula
./gradlew :cli:homebrewChecksum
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT licence.

## Acknowledgments

- Uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON handling
- Tested with [Kotest](https://kotest.io/) and [MockK](https://mockk.io/)
- Custom lightweight CLI framework (~300 lines, zero external dependencies)
