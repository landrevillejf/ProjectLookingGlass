#!/bin/bash
# Post-install hook for lpm-console

set -e

echo "Installing LPM Console..."

# Create launcher symlink
ln -sf /opt/lpm-console/bin/lpm-console /usr/bin/lpm-console

# Install desktop entry
mkdir -p /usr/share/applications
cp /opt/lpm-console/lpm-console.desktop /usr/share/applications/

# Install polkit action (if using pkexec)
if command -v pkexec &> /dev/null; then
    mkdir -p /usr/share/polkit-1/actions
    cp /opt/lpm-console/com.lpmconsole.policy /usr/share/polkit-1/actions/
fi

# Update desktop database
update-desktop-database /usr/share/applications &> /dev/null || true

echo "LPM Console installed successfully."
