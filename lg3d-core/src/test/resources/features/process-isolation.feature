Feature: QNX-style process isolation for desktop applications

  An application that runs in its own operating-system process can crash, leak
  or be OOM-killed without taking the desktop JVM down. The 2D desktop
  classifies such an app as CHILD_PROCESS, forks it into an isolated JVM on the
  lg3d display (where the X11 compositor redirects its window back into an MDI
  frame under the combined launch), and supervises it with a bounded restart
  policy so a crash recovers on its own and never loops forever.

  Scenario: the Web Browser is forked into an isolated child process
    Given the start-menu command "java org.jdesktop.lg3d.apps.webbrowser.WebBrowser"
    When the desktop classifies the command
    Then the kind is "CHILD_PROCESS"
    And its child main class is "org.jdesktop.lg3d.apps.webbrowser.WebBrowserApp"
    And it has no in-JVM hosted panel

  Scenario: an isolated child inherits the desktop launcher and a bounded heap
    Given an isolated child app "org.jdesktop.lg3d.apps.webbrowser.WebBrowserApp"
    When the desktop builds its launch command
    Then the command starts with this JVM's java launcher
    And the command runs the child main class on the desktop classpath
    And the JVM arguments bound the child heap with "-Xmx512m"

  Scenario: the child environment disables the WebKit compositor paths
    Given an isolated browser child
    When the desktop builds its environment over the privacy base
    Then the environment disables WebKit compositing and DMABUF
    And the environment targets the lg3d display
    And a privacy-critical base variable is never overwritten

  Scenario: a crashed child within its budget is restarted
    Given a restart policy allowing 2 attempts over a 15000 ms stable window
    When a child exits with code 1 after 10 ms having taken 0 attempts
    Then the supervisor decides to relaunch it

  Scenario: a cleanly closed child is not restarted
    Given a restart policy allowing 2 attempts over a 15000 ms stable window
    When a child exits with code 0 after 60000 ms having taken 0 attempts
    Then the supervisor decides to stop cleanly

  Scenario: a crash after a stable run earns a fresh budget
    Given a restart policy allowing 1 attempts over a 1000 ms stable window
    When a child exits with code 1 after 5000 ms having taken 3 attempts
    Then the supervisor decides to relaunch on a fresh budget

  Scenario: a child that keeps crashing is abandoned after its budget
    Given a restart policy allowing 2 attempts over a 15000 ms stable window
    When a child exits with code 1 after 10 ms having taken 2 attempts
    Then the supervisor decides to give up

  Scenario: a leaf panel is isolated through the generic panel host
    Given the leaf panel class "org.jdesktop.lg3d.apps.calculator.CalculatorPanel"
    When the desktop builds its panel-host recipe
    Then the child main is the generic panel host
    And the host arguments name the panel class

  Scenario: an isolated Swing child inherits the desktop configuration
    Given a desktop configuration property "lg.test.cucumber.resourcedir" set to "/opt/lg3d/resources/"
    When the desktop builds the child JVM arguments for the "SWING" profile
    Then the arguments mark the child isolated
    And the arguments carry the configuration property "lg.test.cucumber.resourcedir"
    And the arguments bound a Swing child heap with "-Xmx256m"
    And the arguments carry no JavaFX pipeline

  Scenario: the desktop reaps an isolated child when it exits
    Given an isolated child supervised by the desktop
    When the desktop shuts the child down
    Then the child process is no longer alive
    And the child is not restarted
    And no stop is reported to the user
