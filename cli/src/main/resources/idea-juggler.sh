#!/bin/bash
#
# idea-juggler - CLI tool for managing IntelliJ IDEA instances per project
#

set -e

# Resolve symlinks to find the real script location
SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Error: Java not found. Please set JAVA_HOME or install Java 17+." >&2
    exit 1
fi

# Check Java version (optional but recommended)
if ! "$JAVA_CMD" -version 2>&1 | grep -q "version \"1[7-9]"; then
    echo "Warning: Java 17 or higher recommended" >&2
fi

# Build classpath (all JARs in libexec)
CLASSPATH="$APP_HOME/libexec/*"

# Support for JVM options via environment variable
JAVA_OPTS="${IDEA_JUGGLER_OPTS:-}"

# Execute
exec "$JAVA_CMD" $JAVA_OPTS -cp "$CLASSPATH" com.ideajuggler.MainKt "$@"
