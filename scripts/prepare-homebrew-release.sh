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
