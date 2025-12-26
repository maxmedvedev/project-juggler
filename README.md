# idea-juggler

A Kotlin-based CLI tool that manages separate IntelliJ IDEA instances per project with isolated configurations. Each project gets its own config, system, logs, and plugins directories while sharing the same IntelliJ installation.

## Features

- **Isolated Configurations**: Each project has separate config, system, logs, and plugins folders
- **Stable Project IDs**: Uses SHA-256 hashing of canonical paths for consistent project identification
- **VM Options Management**: Copies your base VM options once on first open, preserving manual edits
- **Auto-Detection**: Automatically detects IntelliJ config and plugins directories
- **Sync Command**: Explicitly synchronize project settings (vmoptions, config, plugins) from base
- **Recent Projects**: Quick access to recently opened projects with interactive selection
- **Cross-Platform**: Supports macOS, Linux, and Windows
- **Zero Dependencies**: Lightweight custom CLI framework with no external dependencies (except Kotlin stdlib)

## Installation

### Via Homebrew (macOS and Linux)

```bash
brew tap maxmedvedev/idea-juggler
brew install idea-juggler
```

### Prerequisites

- JDK 17 or higher (for building from source)
- IntelliJ IDEA installed (Community or Ultimate)
- Gradle 8.5+ (optional, uses wrapper)

### Building from Source

```bash
# Clone the repository
git clone <repository-url>
cd idea-juggler

# Build the application
./gradlew build

# Run directly with Gradle
./gradlew run --args="--help"

# Or build a native executable (requires GraalVM)
./gradlew nativeCompile
```

### Installing the Binary

After building, copy the binary to a location in your PATH:

```bash
# macOS/Linux
sudo cp build/libs/idea-juggler-1.0.0.jar /usr/local/bin/idea-juggler

# Or if you built a native image
sudo cp build/native/nativeCompile/idea-juggler /usr/local/bin/
```

## Quick Start

### 1. Configure IntelliJ Path (Optional)

The tool will auto-detect IntelliJ, but you can set the path explicitly:

```bash
idea-juggler config --intellij-path /Applications/IntelliJ\ IDEA.app/Contents/MacOS/idea
```

### 2. Configure Base VM Options (Optional)

Point to your Toolbox's VM options file to copy base settings:

```bash
# macOS (Toolbox)
idea-juggler config --base-vmoptions ~/Library/Application\ Support/JetBrains/IntelliJIdea2024.3/idea.vmoptions

# Linux (Toolbox)
idea-juggler config --base-vmoptions ~/.config/JetBrains/IntelliJIdea2024.3/idea.vmoptions

# Windows (Toolbox)
idea-juggler config --base-vmoptions %USERPROFILE%\.config\JetBrains\IntelliJIdea2024.3\idea.vmoptions
```

**Note:** Config and plugins directories are auto-detected by default.

### 3. Open a Project

```bash
idea-juggler open ~/projects/my-app
```

That's it! IntelliJ will launch with isolated configuration for this project.

## Commands

### `open <project-path>`

Open a project with a dedicated IntelliJ instance.

```bash
idea-juggler open ~/projects/my-app
idea-juggler open /path/to/project
idea-juggler open .  # Open current directory
```

**What happens:**
- Generates a stable project ID from the path
- Creates isolated config/system/logs/plugins directories
- **On first open**: Copies base VM options, config, and plugins from base directories
- **On subsequent opens**: Preserves your existing settings (use `sync` to update)
- Generates project-specific VM options file with directory overrides
- Launches IntelliJ with custom directories
- Updates recent projects list

### `list [--verbose]`

List all tracked projects.

```bash
idea-juggler list
idea-juggler list --verbose  # Show additional details
idea-juggler list -v         # Short form
```

**Output:**
```
Tracked projects (3):

  my-app
    ID:          a1b2c3d4e5f6g7h8
    Path:        /Users/max/projects/my-app
    Last opened: 2 hours ago

  other-project
    ID:          9i8h7g6f5e4d3c2b
    Path:        /Users/max/projects/other-project
    Last opened: yesterday
```

### `recent [--limit N]`

Show recently opened projects with interactive selection.

```bash
idea-juggler recent
idea-juggler recent --limit 5  # Show only 5 most recent
idea-juggler recent -n 5       # Short form
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
idea-juggler sync --id a1b2c3d4e5f6g7h8
idea-juggler sync -i a1b2c3d4e5f6g7h8  # Short form

# Sync all settings by project path
idea-juggler sync --path ~/projects/my-app
idea-juggler sync -p ~/projects/my-app  # Short form

# Sync only VM options
idea-juggler sync -i a1b2c3d4e5f6g7h8 --vmoptions

# Sync only config and plugins
idea-juggler sync -p ~/projects/my-app --config --plugins

# Force sync all settings
idea-juggler sync -i a1b2c3d4e5f6g7h8 --all
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
# By project ID
idea-juggler clean --id a1b2c3d4e5f6g7h8
idea-juggler clean -i a1b2c3d4e5f6g7h8  # Short form

# By project path
idea-juggler clean --path ~/projects/my-app
idea-juggler clean -p ~/projects/my-app  # Short form

# Skip confirmation prompt
idea-juggler clean -i a1b2c3d4e5f6g7h8 --force
idea-juggler clean -p ~/projects/my-app -f  # Short form
```

**Options:**
- `-i, --id <project-id>` - Specify project by ID
- `-p, --path <project-path>` - Specify project by path (supports `~` expansion)
- `-f, --force` - Skip confirmation prompt

**What gets deleted:**
- Config directory (`~/.idea-juggler/projects/<id>/config`)
- System directory (`~/.idea-juggler/projects/<id>/system`)
- Logs directory (`~/.idea-juggler/projects/<id>/logs`)
- Plugins directory (`~/.idea-juggler/projects/<id>/plugins`)
- VM options file (`~/.idea-juggler/projects/<id>/idea.vmoptions`)
- Project metadata
- Recent projects entry

### `config [options]`

Configure global settings.

```bash
# Show current configuration
idea-juggler config --show

# Set IntelliJ path
idea-juggler config --intellij-path /Applications/IntelliJ\ IDEA.app/Contents/MacOS/idea

# Set base VM options file
idea-juggler config --base-vmoptions ~/.config/JetBrains/IntelliJIdea2024.3/idea.vmoptions

# Set base config directory
idea-juggler config --base-config ~/.config/JetBrains/IntelliJIdea2024.3

# Set base plugins directory
idea-juggler config --base-plugins ~/.config/JetBrains/IntelliJIdea2024.3/plugins
```

**Options:**
- `--show` - Display current configuration
- `--intellij-path <path>` - Path to IntelliJ executable (auto-detected if not set)
- `--base-vmoptions <path>` - Path to base VM options file (must be configured for sync)
- `--base-config <path>` - Path to base config directory (auto-detected if not set)
- `--base-plugins <path>` - Path to base plugins directory (auto-detected if not set)

**Example output:**
```
Current configuration:

  IntelliJ path:       /Applications/IntelliJ IDEA.app/Contents/MacOS/idea
  Base VM options:     /Users/max/Library/Application Support/JetBrains/IntelliJIdea2026.1/idea.vmoptions
  Base config:         /Users/max/Library/Application Support/JetBrains/IntelliJIdea2026.1
  Base plugins:        /Users/max/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins
  Max recent projects: 10

Configuration file: /Users/max/.idea-juggler/config.json
```

## How It Works

### Project Identity

Each project is identified by a stable SHA-256 hash (first 16 characters) of its canonical path. This ensures:
- Same project always gets the same ID
- Handles symlinks, relative paths, and path variations
- No collisions between different projects

### VM Options Management

idea-juggler creates per-project `.vmoptions` files that preserve your manual edits:

**On First Open:**
1. Copies your base VM options file (if configured)
2. Filters out any existing `-Didea.*.path` properties
3. Appends project-specific directory overrides
4. Saves to `~/.idea-juggler/projects/<project-id>/idea.vmoptions`

**On Subsequent Opens:**
1. Only updates the `-Didea.*.path` override section
2. Preserves all other settings you've manually added or modified

**To Update from Base:**
```bash
# When base VM options change, you'll see a note:
# "Note: Base VM options have changed. Use 'idea-juggler sync <project>' to update."

# Sync specific project
idea-juggler sync --path ~/projects/my-app --vmoptions

# Or sync all settings
idea-juggler sync -p ~/projects/my-app
```

Example generated file:
```
# Base VM options from: /Users/max/Library/Application Support/JetBrains/IntelliJIdea2024.3/idea.vmoptions
-Xms256m
-Xmx2048m
-XX:ReservedCodeCacheSize=512m
... (all other options from base file)

# Your custom edits here are preserved
-Xmx4096m  # You added this for a large project

# idea-juggler overrides (auto-generated)
-Didea.config.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/config
-Didea.system.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/system
-Didea.log.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/logs
-Didea.plugins.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/plugins
```

### Config and Plugins Synchronization

**On First Open:**
- Config files are copied from base-config directory (auto-detected or configured)
- Plugins are copied from base-plugins directory (auto-detected or configured)

**On Subsequent Opens:**
- Your project-specific config and plugins are preserved
- Use `sync` command to update when needed

### Launch Process

1. Set `IDEA_VM_OPTIONS` environment variable to project's `.vmoptions` file
2. Launch IntelliJ with the project path as argument
3. IntelliJ reads custom VM options and uses project-specific directories
4. CLI exits immediately (IntelliJ runs independently)

## Directory Structure

```
~/.idea-juggler/
├── config.json                          # Global configuration
├── recent.json                          # Recent projects index
└── projects/
    ├── a1b2c3d4e5f6g7h8/                # Project ID (hash of path)
    │   ├── idea.vmoptions               # Project-specific VM options
    │   ├── metadata.json                # Project metadata
    │   ├── config/                      # IntelliJ config directory
    │   ├── system/                      # IntelliJ system directory
    │   ├── logs/                        # IntelliJ logs directory
    │   └── plugins/                     # IntelliJ plugins directory
    └── 9i8h7g6f5e4d3c2b/                # Another project
        └── ...
```

## Configuration Files

### Global Config (`~/.idea-juggler/config.json`)

```json
{
  "intellijPath": "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
  "baseVmOptionsPath": "/Users/max/Library/Application Support/JetBrains/IntelliJIdea2024.3/idea.vmoptions",
  "baseVmOptionsHash": "abc123...",
  "baseConfigPath": "/Users/max/Library/Application Support/JetBrains/IntelliJIdea2024.3",
  "basePluginsPath": "/Users/max/Library/Application Support/JetBrains/IntelliJIdea2024.3/plugins",
  "maxRecentProjects": 10
}
```

### Project Metadata (`~/.idea-juggler/projects/<id>/metadata.json`)

```json
{
  "id": "a1b2c3d4e5f6g7h8",
  "path": "/Users/max/projects/my-app",
  "name": "my-app",
  "lastOpened": "2025-12-25T10:30:00Z",
  "openCount": 5,
  "debugPort": 5005
}
```

## Troubleshooting

### IntelliJ Not Found

If idea-juggler can't find IntelliJ:

```bash
# Set the path explicitly
idea-juggler config --intellij-path /path/to/intellij
```

Common paths:
- **macOS**: `/Applications/IntelliJ IDEA.app/Contents/MacOS/idea`
- **Linux**: `/opt/idea/bin/idea.sh`
- **Windows**: `C:\Program Files\JetBrains\IntelliJ IDEA\bin\idea64.exe`

### Base VM Options Not Applied

1. Check the base file path:
   ```bash
   idea-juggler config --show
   ```

2. Verify the file exists and is readable

3. Sync project settings:
   ```bash
   idea-juggler sync --path ~/projects/my-app --vmoptions
   ```

### Projects Not Isolated

If projects seem to share configurations:

1. Check if `IDEA_VM_OPTIONS` is being set correctly
2. Verify IntelliJ is actually using the custom directories:
   ```bash
   # Check the generated VM options file
   cat ~/.idea-juggler/projects/<project-id>/idea.vmoptions
   ```

3. Look for IntelliJ startup errors in:
   ```bash
   ~/.idea-juggler/projects/<project-id>/logs/idea.log
   ```

### Sync Not Working

If sync command fails:

1. For VM options sync, ensure base-vmoptions is configured:
   ```bash
   idea-juggler config --base-vmoptions <path>
   ```

2. For config/plugins sync, paths are auto-detected but you can configure them:
   ```bash
   idea-juggler config --base-config <path>
   idea-juggler config --base-plugins <path>
   ```

3. Check what paths will be used:
   ```bash
   idea-juggler config --show
   ```

### Permission Errors

If you get permission errors:

```bash
# Ensure the base directory exists and is writable
mkdir -p ~/.idea-juggler
chmod 755 ~/.idea-juggler
```

## Advanced Usage

### Multiple IntelliJ Installations

You can switch between different IntelliJ installations:

```bash
# Use Community Edition
idea-juggler config --intellij-path /Applications/IntelliJ\ IDEA\ CE.app/Contents/MacOS/idea

# Use Ultimate Edition
idea-juggler config --intellij-path /Applications/IntelliJ\ IDEA.app/Contents/MacOS/idea
```

### Custom VM Options Per Project

You can manually edit project VM options and they'll be preserved:

```bash
# Edit the generated file
vim ~/.idea-juggler/projects/<project-id>/idea.vmoptions

# Add custom options - they won't be overwritten on subsequent opens
echo "-Xmx4096m" >> ~/.idea-juggler/projects/<project-id>/idea.vmoptions
echo "-Dmy.custom.property=value" >> ~/.idea-juggler/projects/<project-id>/idea.vmoptions
```

**Note:** Only the `-Didea.*.path` override section is automatically updated. All other edits are preserved.

### Selective Synchronization

```bash
# Update only VM options when base file changes
idea-juggler sync -p ~/my-project --vmoptions

# Update only plugins (e.g., after installing a new plugin in base IntelliJ)
idea-juggler sync -i abc123 --plugins

# Update only config (e.g., after changing code style settings)
idea-juggler sync -i abc123 --config

# Update everything
idea-juggler sync -p ~/my-project --all
```

### Bulk Operations

```bash
# Sync all projects (iterate through list)
idea-juggler list | grep "ID:" | awk '{print $2}' | while read id; do
    idea-juggler sync --id $id
done

# Clean all projects older than X days (manual script)
find ~/.idea-juggler/projects -type d -mtime +90 -exec basename {} \; | while read id; do
    idea-juggler clean --id $id --force
done
```

## Development

### Project Structure

```
src/main/kotlin/com/ideajuggler/
├── Main.kt                              # Entry point
├── cli/                                 # CLI commands
│   ├── framework/                       # Custom CLI framework
│   │   ├── CliApp.kt
│   │   ├── Command.kt
│   │   ├── Option.kt
│   │   ├── Argument.kt
│   │   ├── ArgParser.kt
│   │   └── HelpFormatter.kt
│   ├── OpenCommand.kt
│   ├── ListCommand.kt
│   ├── CleanCommand.kt
│   ├── ConfigCommand.kt
│   ├── RecentCommand.kt
│   ├── SyncCommand.kt
│   └── SimpleMessageOutput.kt
├── core/                                # Core logic
│   ├── ProjectManager.kt
│   ├── ProjectIdGenerator.kt
│   ├── ProjectLauncher.kt
│   ├── IntelliJLauncher.kt
│   ├── DirectoryManager.kt
│   ├── VMOptionsGenerator.kt
│   └── BaseVMOptionsTracker.kt
├── config/                              # Configuration models
│   ├── GlobalConfig.kt
│   ├── ProjectMetadata.kt
│   ├── ConfigRepository.kt
│   └── RecentProjectsIndex.kt
├── platform/                            # Platform-specific code
│   ├── Platform.kt
│   ├── IntelliJLocator.kt
│   ├── ConfigLocator.kt
│   ├── PluginLocator.kt
│   └── ProcessLauncher.kt
└── util/                                # Utilities
    ├── HashUtils.kt
    ├── TimeUtils.kt
    ├── PathUtils.kt
    └── DirectoryCopier.kt
```

### Running Tests

The project includes comprehensive unit and integration tests:

```bash
# Run all tests
./gradlew test

# Run tests with detailed output
./gradlew test --info

# Run a specific test class
./gradlew test --tests "com.ideajuggler.core.ProjectIdGeneratorTest"
```

**Test Coverage:**
- **Unit Tests**:
  - `ProjectIdGeneratorTest` - Hash consistency, path variations, special characters
  - `HashUtilsTest` - SHA-256 hashing for strings and files
  - `VMOptionsGeneratorTest` - VM options file generation, property filtering, preservation
  - `BaseVMOptionsTrackerTest` - Change detection, hash updates
  - `DirectoryManagerTest` - Directory creation, cleanup operations, sync operations
  - `ConfigRepositoryTest` - JSON persistence, CRUD operations
  - `IntelliJLocatorTest` - Platform-specific IntelliJ detection
  - `CleanCommandTest` - Command-line argument parsing with -i/-p options
  - `OpenCommandTest` - Path validation and error handling

- **Integration Tests**:
  - `IntegrationTest` - End-to-end workflows including:
    - Full project lifecycle (configure → open → list → clean)
    - Base VM options change detection
    - Multiple project management
    - Sync operations

**Test Framework:** [Kotest](https://kotest.io/) with [MockK](https://mockk.io/) for mocking

**Total Tests:** 102 tests, all passing

### Building Distribution

```bash
# Build Homebrew distribution
./gradlew :cli:homebrewDist

# Output: cli/build/distributions/idea-juggler-<version>.tar.gz
# Size: ~2.2 MB (no heavy dependencies)

# Generate checksum for Homebrew formula
./gradlew :cli:homebrewChecksum
```

### Building Native Image

```bash
# Requires GraalVM
./gradlew nativeCompile

# Binary will be at:
# build/native/nativeCompile/idea-juggler
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

[Add your license here]

## Acknowledgments

- Uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON handling
- Tested with [Kotest](https://kotest.io/) and [MockK](https://mockk.io/)
- Custom lightweight CLI framework (~300 lines, zero external dependencies)
