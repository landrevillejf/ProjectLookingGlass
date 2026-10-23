Feature: Start-menu items self-hide when their launcher cannot run

  A descriptor's command is a whole command line, and the desktop shows the item
  only when every executable that line depends on is actually installed. For an
  ordinary command that is just its leading program, so a browser opened on a URL
  gates on the browser and never mistakes the URL for a missing executable. The
  one exception is a privilege-escalation wrapper: an installer launched as
  "pkexec calamares" is pointless without calamares, so the elevated program is
  required too -- which is what lets the "Install WBLFS (LG3D)" item hide on a
  host that has pkexec but no calamares, instead of showing an item that dies
  when it is clicked.

  Scenario: only the leading program of a plain command is required
    Given the launch command "firefox https://github.com/landrevillejf/ProjectLookingGlass"
    When the desktop resolves the executables the command requires
    Then the required executables are "firefox"

  Scenario: a privilege-wrapped installer requires the wrapper and the installer
    Given the launch command "pkexec calamares"
    When the desktop resolves the executables the command requires
    Then the required executables are "pkexec,calamares"

  Scenario: a wrapper's options are skipped to find the elevated program
    Given the launch command "sudo -E calamares"
    When the desktop resolves the executables the command requires
    Then the required executables are "sudo,calamares"

  Scenario: an in-JVM app is always available
    Given the launch command "java org.jdesktop.lg3d.apps.calculator.Calculator"
    When the desktop checks whether the command is available
    Then the command is available

  Scenario: a wrapped installer is available when both the wrapper and the installer exist
    Given a privilege wrapper that exists and an installer program that exists
    When the desktop checks whether the command is available
    Then the command is available

  Scenario: a wrapped installer self-hides when the installer is missing
    Given a privilege wrapper that exists but an installer program that does not
    When the desktop checks whether the command is available
    Then the command is not available
