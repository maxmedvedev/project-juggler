#!/bin/bash
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 1.0.0"
    exit 1
fi

echo "Preparing Homebrew release for version $VERSION..."

# Update version in build.gradle.kts
sed -i.bak "s/version = \".*\"/version = \"$VERSION\"/" build.gradle.kts
rm build.gradle.kts.bak

# Clean and build
echo "Building distribution..."
./gradlew clean :cli:homebrewDist :cli:homebrewChecksum

# Get paths
DIST_FILE="cli/build/distributions/idea-juggler-${VERSION}.tar.gz"
CHECKSUM_FILE="${DIST_FILE}.sha256"

if [ ! -f "$DIST_FILE" ]; then
    echo "Error: Distribution file not found at $DIST_FILE"
    exit 1
fi

# Read checksum
CHECKSUM=$(cut -d' ' -f1 "$CHECKSUM_FILE")

# Test the distribution
echo ""
echo "Testing distribution..."
TEMP_DIR=$(mktemp -d)
tar -xzf "$DIST_FILE" -C "$TEMP_DIR"
EXTRACTED_DIR="$TEMP_DIR/idea-juggler-$VERSION"

# Check structure
if [ ! -f "$EXTRACTED_DIR/bin/idea-juggler" ]; then
    echo "Error: bin/idea-juggler not found in distribution!" >&2
    exit 1
fi

if [ ! -x "$EXTRACTED_DIR/bin/idea-juggler" ]; then
    echo "Error: bin/idea-juggler is not executable!" >&2
    exit 1
fi

# Test script syntax
if ! bash -n "$EXTRACTED_DIR/bin/idea-juggler"; then
    echo "Error: Shell script has syntax errors!" >&2
    exit 1
fi

# Test help command (if Java available)
if command -v java >/dev/null 2>&1; then
    if ! "$EXTRACTED_DIR/bin/idea-juggler" --help >/dev/null 2>&1; then
        echo "Warning: Script execution test failed (Java may not be configured)" >&2
    fi
fi

rm -rf "$TEMP_DIR"
echo "Distribution structure validated ✓"

echo ""
echo "============================================"
echo "Release artifacts ready!"
echo "============================================"
echo "Distribution: $DIST_FILE"
echo "SHA256: $CHECKSUM"
echo ""
echo "Next steps:"
echo "1. Create a git tag: git tag v$VERSION"
echo "2. Push tag: git push origin v$VERSION"
echo "3. Create GitHub release and attach $DIST_FILE"
echo "4. Update Homebrew formula with:"
echo "   url: https://github.com/YOUR_USERNAME/idea-juggler/releases/download/v$VERSION/idea-juggler-$VERSION.tar.gz"
echo "   sha256: $CHECKSUM"
echo "============================================"
