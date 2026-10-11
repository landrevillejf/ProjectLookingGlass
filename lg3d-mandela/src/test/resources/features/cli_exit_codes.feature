Feature: The command line keeps its promise about exit codes
  A launcher, a build step and a console panel all branch on the code {@code mandela}
  returns. Five codes, each with a meaning the person running it can act on: success,
  a usage mistake, a program that failed, a file that could not be read, and a file
  that compiles into findings. This is the same method a desktop embeds, so a code
  that lied about what happened would report success on a failed build.

  Scenario: an empty command line prints the usage and succeeds
    When the command line runs:
      """
      --help
      """
    Then the exit code is 0
    And the standard output mentions 'usage: mandela'

  Scenario: version describes the engine without running a program
    When the command line runs:
      """
      version
      """
    Then the exit code is 0
    And the standard output mentions 'mandela'

  Scenario: about names the project
    When the command line runs:
      """
      about
      """
    Then the exit code is 0
    And the standard output mentions 'Project Mandela'

  Scenario: eval prints the value of an expression
    When the command line runs:
      """
      eval
      1 + 2
      """
    Then the exit code is 0
    And the standard output mentions '3'

  Scenario: a typo is a usage error before any program starts
    When the command line runs:
      """
      frobnicate
      """
    Then the exit code is 1
    And the error output mentions 'unknown command'

  Scenario: a command refuses to guess when its own word is missing
    When the command line runs:
      """
      eval
      """
    Then the exit code is 1

  Scenario: a failing program is a different answer from a bad command
    When the command line runs:
      """
      eval
      1 / 0
      """
    Then the exit code is 2
    And the error output mentions 'RangeError'

  Scenario: a missing file is a different answer from a broken program
    When the command line runs:
      """
      run
      /no/such/program.mnd
      """
    Then the exit code is 3
    And the error output mentions 'cannot read'

  Scenario: check compiles without running and reports findings
    When the command line checks a program that does not compile
    Then the exit code is 4
    And the error output mentions 'error(s)'
