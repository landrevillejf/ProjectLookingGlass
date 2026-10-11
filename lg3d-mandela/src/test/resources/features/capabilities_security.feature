Feature: What the capability profiles allow and refuse
  The four documented sandboxes -- console, webPage, desktop and open -- are the
  host's promise about what a script may do. A page computes but touches nothing on
  the machine; a desktop app reads and writes one directory and nothing above it; the
  JVM door stays shut even when files are open. Each refusal is a PermissionError a
  host can name to its user, quoting the sandbox that set the rule.

  Background: the same program is judged by different grants
    Given a console sandbox engine

  Scenario: a console computes, but has no filesystem
    When the one-line program '2 * 21' runs
    Then its value is '42'

  Scenario: a console cannot read a file
    When the program runs:
      """
      use std.fs
      fs.read("x")
      """
    Then the program raises a PermissionError error

  Scenario: a console cannot start a thread
    When the program runs:
      """
      use std.time
      time.sleep(1)
      """
    Then the program raises a PermissionError error

  Scenario: an unbounded loop is stopped by the instruction budget
    When the program runs:
      """
      var i = 0
      loop { i += 1 }
      """
    Then the program raises a LimitError error

  Scenario: a web page computes and nothing else
    Given a webPage sandbox engine
    When the one-line program '2 * 21' runs
    Then its value is '42'

  Scenario: a web page is refused even the console, and told which sandbox said so
    Given a webPage sandbox engine
    When the program runs:
      """
      print("x")
      """
    Then the program raises a PermissionError error
    And the failure mentions 'webpage'

  Scenario: a web page cannot read a file
    Given a webPage sandbox engine
    When the program runs:
      """
      use std.fs
      fs.read("note.txt")
      """
    Then the program raises a PermissionError error

  Scenario: a web page cannot read the environment
    Given a webPage sandbox engine
    When the program runs:
      """
      use std.env
      env.get("HOME")
      """
    Then the program raises a PermissionError error

  Scenario: a desktop app reads the file inside its root
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.read("note.txt").length
      """
    Then its value is '11'

  Scenario: a desktop app writes inside its root and reads the value back
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.write("out.txt", "made by mandela")
      fs.read("out.txt")
      """
    Then its value is 'made by mandela'

  Scenario: a desktop app is refused the file above its root
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.read("../secret")
      """
    Then the program raises a PermissionError error

  Scenario: the desktop sandbox describes its file grant but never the JVM door
    Given a desktop sandbox engine over a temporary directory
    Then the sandbox describes a file_read grant
    And the sandbox does not allow the JVM type 'java.lang.Runtime'

  Scenario: an open host reads its environment but still bars JVM types
    Given an open sandbox engine
    When the program runs:
      """
      use std.env
      env.separator.length
      """
    Then its value is '1'
    And the sandbox does not allow the JVM type 'java.lang.Runtime'
