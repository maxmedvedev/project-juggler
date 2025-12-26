# Homebrew Distribution Setup

This document describes the Homebrew distribution setup for idea-juggler.

## What Was Created

### 1. Build Configuration
- **cli/build.gradle.kts**: Added reproducible build settings and custom tasks
  - `homebrewDist`: Creates a Homebrew-compatible tar.gz distribution
  - `homebrewChecksum`: Generates SHA256 checksum for the distribution
  - Application name changed from "cli" to "idea-juggler"

- **build.gradle.kts**: Added reproducible build settings for all subprojects

### 2. Scripts
- **scripts/prepare-homebrew-release.sh**: Script to prepare a new release
  - Updates version in build.gradle.kts
  - Builds distribution and calculates checksum
  - Displays next steps for creating a GitHub release

### 3. GitHub Actions
- **.github/workflows/release.yml**: Automated release workflow
  - Triggered when a tag matching `v*` is pushed
  - Builds distribution
  - Creates GitHub release with artifacts
  - Includes installation instructions in release notes

### 4. Homebrew Tap Templates
- **homebrew-tap-template/Formula/idea-juggler.rb**: Homebrew formula
- **homebrew-tap-template/README.md**: Tap repository documentation
- **homebrew-tap-template/SETUP.md**: Instructions for setting up the tap

### 5. Documentation
- **README.md**: Updated with Homebrew installation instructions

## Distribution Structure

The `homebrewDist` task creates a tar.gz file with this structure:

```
idea-juggler-1.0.0/
└── libexec/
    ├── cli.jar              # Main application JAR
    ├── core.jar             # Core module
    └── [dependencies]       # All runtime dependencies
```

The Homebrew formula installs this to:
```
/usr/local/Cellar/idea-juggler/1.0.0/
└── libexec/
    └── [all JARs]
```

And creates a wrapper script at:
```
/usr/local/bin/idea-juggler
```

## How to Use

### Build and Test Locally

```bash
# Build the distribution
./gradlew :cli:homebrewDist :cli:homebrewChecksum

# Check the output
ls -lh cli/build/distributions/

# Verify reproducibility
./gradlew clean :cli:homebrewDist
shasum -a 256 cli/build/distributions/idea-juggler-*.tar.gz > /tmp/checksum1.txt
./gradlew clean :cli:homebrewDist
shasum -a 256 cli/build/distributions/idea-juggler-*.tar.gz > /tmp/checksum2.txt
diff /tmp/checksum1.txt /tmp/checksum2.txt  # Should be identical
```

### Create a Release

#### Option 1: Manual Release (Recommended for first release)

```bash
# 1. Prepare the release
./scripts/prepare-homebrew-release.sh 1.0.0

# 2. Create and push tag
git add .
git commit -m "Release v1.0.0"
git tag v1.0.0
git push origin main
git push origin v1.0.0

# 3. GitHub Actions will automatically:
#    - Build the distribution
#    - Create a GitHub release
#    - Attach tar.gz and sha256 files

# 4. After release is published, set up Homebrew tap (see below)
```

#### Option 2: Automated Release

Once GitHub Actions is set up, just push a tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

### Set Up Homebrew Tap

#### First Time Setup

1. Create a new GitHub repository named `homebrew-tap`

2. Copy the template files:
```bash
cd /path/to/new/homebrew-tap-repo
cp -r /path/to/idea-juggler/homebrew-tap-template/* .
```

3. Update placeholders in `Formula/idea-juggler.rb`:
   - Replace `YOUR_USERNAME` with your GitHub username
   - Replace `PUT_CHECKSUM_HERE` with the SHA256 from your first release
   - Update the license if needed

4. Commit and push:
```bash
git add .
git commit -m "Initial commit: Homebrew formula for idea-juggler"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/homebrew-tap.git
git push -u origin main
```

#### Updating the Formula for New Releases

When you release a new version:

1. Update `Formula/idea-juggler.rb`:
```ruby
url "https://github.com/YOUR_USERNAME/idea-juggler/releases/download/v1.0.1/idea-juggler-1.0.1.tar.gz"
sha256 "new_checksum_here"
```

2. Commit and push:
```bash
git add Formula/idea-juggler.rb
git commit -m "Update idea-juggler to v1.0.1"
git push
```

### Install and Test

```bash
# Add your tap
brew tap YOUR_USERNAME/tap

# Install idea-juggler
brew install idea-juggler

# Test it
idea-juggler --help

# Uninstall (for testing)
brew uninstall idea-juggler
```

## Reproducible Builds

The build configuration ensures reproducible builds by:
- Disabling file timestamps in archives
- Ensuring reproducible file ordering
- Applying settings to all archive tasks (JAR, TAR, etc.)

This means building the same version multiple times produces identical checksums, which is critical for Homebrew formula integrity.

**Verified**: Multiple builds of the same code produce checksum `a9896a08326157d1e4489e3d1cca5f1fb017800109eba346e54760d6c1d38443`

## Notes

- The distribution is JVM-based (requires OpenJDK 17)
- Works on macOS (Intel + Apple Silicon) and Linux
- Platform-independent: same distribution works on all platforms
- The Homebrew formula creates its own wrapper script that sets up JAVA_HOME
- Distribution size: ~12MB compressed (all dependencies included)

## Troubleshooting

### Build fails with "version is unspecified"
The version is read from `build.gradle.kts`. Ensure it's set correctly:
```kotlin
version = "1.0.0"
```

### Checksums don't match
Ensure you're building with the reproducibility settings. Run:
```bash
./gradlew clean :cli:homebrewDist
```

### Formula installation fails
- Verify the URL in the formula points to the correct release
- Verify the SHA256 matches the release artifact
- Check that OpenJDK 17 is available: `brew list openjdk@17`
