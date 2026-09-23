#!/bin/bash
# Build script to create the LPM Console .tar.xz package

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/lpm-console/build-gradle/libs"
PACKAGE_DIR="$SCRIPT_DIR/lpm-console-package"
OUTPUT_FILE="$PROJECT_ROOT/lpm-console/lpm-console-1.0.0.tar.xz"

echo "Building LPM Console package..."

# Clean previous build
rm -rf "$PACKAGE_DIR"
mkdir -p "$PACKAGE_DIR"

# Create directory structure
mkdir -p "$PACKAGE_DIR/opt/lpm-console/bin"
mkdir -p "$PACKAGE_DIR/opt/lpm-console/lib"
mkdir -p "$PACKAGE_DIR/files"

# Copy the built jar
if [ ! -f "$BUILD_DIR/lpm-console.jar" ]; then
    echo "Error: lpm-console.jar not found. Please run: ./gradlew :lpm-console:build"
    exit 1
fi

cp "$BUILD_DIR/lpm-console.jar" "$PACKAGE_DIR/opt/lpm-console/lib/"

# Create launcher script
cat > "$PACKAGE_DIR/opt/lpm-console/bin/lpm-console" << 'EOF'
#!/bin/bash
# Launcher script for LPM Console

JAVA_HOME=${JAVA_HOME:-/opt/jdk-21}
export JAVA_HOME

exec "$JAVA_HOME/bin/java" -jar /opt/lpm-console/lib/lpm-console.jar "$@"
EOF

chmod +x "$PACKAGE_DIR/opt/lpm-console/bin/lpm-console"

# Copy package metadata and hooks
cp "$SCRIPT_DIR/lpm-console.info" "$PACKAGE_DIR/"
cp "$SCRIPT_DIR/post-install.sh" "$PACKAGE_DIR/files/"
cp "$SCRIPT_DIR/post-remove.sh" "$PACKAGE_DIR/files/"
cp "$SCRIPT_DIR/lpm-console.desktop" "$PACKAGE_DIR/opt/lpm-console/"
cp "$SCRIPT_DIR/com.lpmconsole.policy" "$PACKAGE_DIR/opt/lpm-console/"

# Make hooks executable
chmod +x "$PACKAGE_DIR/files/post-install.sh"
chmod +x "$PACKAGE_DIR/files/post-remove.sh"

# Create the tar.xz package
cd "$PACKAGE_DIR"
tar -cJf "$OUTPUT_FILE" *

echo "Package created: $OUTPUT_FILE"
