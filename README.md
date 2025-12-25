# idea-juggler

A Kotlin-based CLI tool that manages separate IntelliJ IDEA instances per project with isolated configurations. Each project gets its own config, system, logs, and plugins directories while sharing the same IntelliJ installation.

## Features

- **Isolated Configurations**: Each project has separate config, system, logs, and plugins folders
- **Stable Project IDs**: Uses SHA-256 hashing of canonical paths for consistent project identification
- **VM Options Management**: Extends your Toolbox's base VM options with project-specific directory overrides
- **Auto-Detection**: Automatically detects base VM options changes and regenerates project configurations
- **Recent Projects**: Quick access to recently opened projects with interactive selection
- **Cross-Platform**: Supports macOS, Linux, and Windows
- **Fast Startup**: Optional GraalVM native compilation for sub-100ms startup times

## Installation

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

### 2. Configure Base VM Options

Point to your Toolbox's VM options file:

```bash
# macOS (Toolbox)
idea-juggler config --base-vmoptions ~/Library/Application\ Support/JetBrains/IntelliJIdea2024.3/idea.vmoptions

# Linux (Toolbox)
idea-juggler config --base-vmoptions ~/.config/JetBrains/IntelliJIdea2024.3/idea.vmoptions

# Windows (Toolbox)
idea-juggler config --base-vmoptions %USERPROFILE%\.config\JetBrains\IntelliJIdea2024.3\idea.vmoptions
```

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
- Generates project-specific VM options file
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

### `clean <project-id-or-path>`

Clean up configuration folders for a project.

```bash
idea-juggler clean a1b2c3d4e5f6g7h8  # By project ID
idea-juggler clean ~/projects/my-app # By path
idea-juggler clean my-app --force    # Skip confirmation
```

**What gets deleted:**
- Config directory (`~/.idea-juggler/projects/<id>/config`)
- System directory (`~/.idea-juggler/projects/<id>/system`)
- Logs directory (`~/.idea-juggler/projects/<id>/logs`)
- Plugins directory (`~/.idea-juggler/projects/<id>/plugins`)
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
```

## How It Works

### Project Identity

Each project is identified by a stable SHA-256 hash (first 16 characters) of its canonical path. This ensures:
- Same project always gets the same ID
- Handles symlinks, relative paths, and path variations
- No collisions between different projects

### VM Options Extension

Instead of modifying IntelliJ's configuration, idea-juggler creates per-project `.vmoptions` files:

1. Reads your Toolbox's base VM options file
2. Filters out any existing `-Didea.*.path` properties
3. Appends project-specific directory overrides
4. Saves to `~/.idea-juggler/projects/<project-id>/idea.vmoptions`

Example generated file:
```
# Base VM options from: /Users/max/Library/Application Support/JetBrains/IntelliJIdea2024.3/idea.vmoptions
-Xms256m
-Xmx2048m
-XX:ReservedCodeCacheSize=512m
... (all other options from base file)

# idea-juggler overrides (auto-generated)
-Didea.config.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/config
-Didea.system.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/system
-Didea.log.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/logs
-Didea.plugins.path=/Users/max/.idea-juggler/projects/a1b2c3d4e5f6g7h8/plugins
```

### Automatic Base File Sync

When you run `open`, idea-juggler:
1. Checks if the base VM options file has changed (SHA-256 hash comparison)
2. If changed, regenerates `.vmoptions` files for ALL tracked projects
3. Updates the stored hash

This ensures all projects stay in sync with your Toolbox settings.

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
  "openCount": 5
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

3. Regenerate configurations:
   ```bash
   # Force regeneration by "updating" the base path
   idea-juggler config --base-vmoptions /path/to/idea.vmoptions
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

While idea-juggler manages directory paths, you can manually edit project VM options:

```bash
# Edit the generated file
vim ~/.idea-juggler/projects/<project-id>/idea.vmoptions

# Add custom options (they won't be overwritten)
echo "-Xmx4096m" >> ~/.idea-juggler/projects/<project-id>/idea.vmoptions
```

Note: Directory path overrides will be regenerated if the base file changes.

### Bulk Operations

```bash
# Clean all projects older than X days (manual script)
find ~/.idea-juggler/projects -type d -mtime +90 -exec basename {} \; | while read id; do
    idea-juggler clean $id --force
done
```

## Development

### Project Structure

```
src/main/kotlin/com/ideajuggler/
├── Main.kt                              # Entry point
├── cli/                                 # CLI commands
│   ├── IdeaJugglerCommand.kt
│   ├── OpenCommand.kt
│   ├── ListCommand.kt
│   ├── CleanCommand.kt
│   ├── ConfigCommand.kt
│   └── RecentCommand.kt
├── core/                                # Core logic
│   ├── ProjectManager.kt
│   ├── ProjectIdGenerator.kt
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
│   └── ProcessLauncher.kt
└── util/                                # Utilities
    └── HashUtils.kt
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
  - `VMOptionsGeneratorTest` - VM options file generation, property filtering
  - `BaseVMOptionsTrackerTest` - Change detection, hash updates
  - `DirectoryManagerTest` - Directory creation, cleanup operations
  - `ConfigRepositoryTest` - JSON persistence, CRUD operations
  - `IntelliJLocatorTest` - Platform-specific IntelliJ detection

- **Integration Tests**:
  - `IntegrationTest` - End-to-end workflows including:
    - Full project lifecycle (configure → open → list → clean)
    - Base VM options change detection and propagation
    - Multiple project management

**Test Framework:** [Kotest](https://kotest.io/) with [MockK](https://mockk.io/) for mocking

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

- Built with [Clikt](https://ajalt.github.io/clikt/) - Kotlin CLI framework
- Uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON handling
