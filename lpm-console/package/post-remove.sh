#!/bin/bash
# Post-remove hook for lpm-console

set -e

echo "Removing LPM Console..."

# Remove launcher symlink
rm -f /usr/bin/lpm-console

# Remove desktop entry
rm -f /usr/share/applications/lpm-console.desktop

# Remove polkit action
rm -f /usr/share/polkit-1/actions/com.lpmconsole.policy

# Update desktop database
update-desktop-database /usr/share/applications &> /dev/null || true

echo "LPM Console removed successfully."
