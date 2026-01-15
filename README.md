# project-juggler

An IntelliJ IDEA plugin that manages separate IDE instances per project with isolated configurations. Each project gets its own config, system, logs, and plugins directories while sharing the same IntelliJ installation.

## Features

- **Isolated Configurations**: Each project has separate config, system, logs, and plugins folders
- **VM Options Management**: Copies your base VM options once on first open, preserving manual edits
- **Auto-Detection**: Automatically detects VM options, IntelliJ config and plugins directories
- **Sync Actions**: Synchronize project settings (VM options, config, plugins) from base via popup menu
- **Recent Projects Popup**: Quick access to recently opened projects with Cmd/Ctrl+Shift+P
- **Main Project**: Designate a project as "main" to open it with base settings (no isolation)
- **Cross-Platform**: Supports macOS, Linux, and Windows

## Installation

Install the plugin from the JetBrains Marketplace or build from source.

## Usage

### Recent Projects Popup (Cmd/Ctrl+Shift+P)

The main interface for project-juggler. Press `Cmd+Shift+P` (macOS) or `Ctrl+Shift+P` (Windows/Linux) to open the popup.

**Features:**
- View recently opened isolated projects
- Open projects in new isolated IDE instances
- Sync settings (VM options, config, plugins) for any project
- Mark/unmark projects as "main project"
- Open file chooser to add new projects

### Project Actions

Right-arrow or click on a project in the popup to access actions:

- **Open** - Launch the project in a new isolated IDE instance
- **Sync VM Options** - Update VM options from base
- **Sync Config** - Update config from base
- **Sync Plugins** - Update plugins from base
- **Sync All** - Update all settings from base
- **Toggle Main Project** - Mark/unmark as main project
- **Remove** - Remove project from tracking

### Main Project

You can designate one project as the "main project". The main project:
- Opens with base settings (no isolation)
- Can be quickly accessed from the popup
- Is indicated with a special icon in the list

## How It Works

When you open a project through project-juggler:

1. **First open**:
   - Generates a stable project ID from the path
   - Creates isolated config/system/logs/plugins directories
   - Copies base VM options, config, and plugins

2. **Subsequent opens**:
   - Preserves your existing settings
   - Use sync actions to update from base when needed

3. **Sync operations**:
   - If syncing the currently running IDE, it will close and reopen automatically
   - Other projects can be synced in the background

## Data Storage

All project data is stored in `~/.project-juggler/`:
- `config.json` - Global configuration and project metadata
- `projects/<id>/` - Per-project isolated directories (config, system, logs, plugins)

## Building from Source

```bash
./gradlew build              # Build all modules
./gradlew test               # Run all tests
./gradlew :plugin:buildPlugin    # Build IntelliJ plugin
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT licence.

## Acknowledgments

- Uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON handling
- Tested with [Kotest](https://kotest.io/) and [MockK](https://mockk.io/)
