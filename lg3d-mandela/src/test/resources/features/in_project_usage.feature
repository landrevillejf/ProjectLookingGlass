Feature: Mandela runs its own repository
  The language is dogfooded here: the automation scripts in scripts/mandela are
  written in Mandela and checked by the real compiler on every build, the browser's
  userscripts and the desktop's Mandela tools run on the same engine, and a script is
  handed its arguments by the host exactly as the command line does. These scenarios
  hold up that real usage rather than a toy program, so a grammar change that quietly
  breaks a shipped tool fails here before it fails in CI.

  Scenario: every automation script the repository ships still compiles
    Then every shipped automation script compiles
    And at least 4 automation scripts are read

  Scenario: a script reads the arguments the host passes it
    When a script reads the arguments the host passed:
      """
      args[0] + " on " + args[1]
      """
    Then the argument script answers 'my-script on example.com'

  Scenario: a script counts its arguments the way a launcher does
    When a script reads the arguments the host passed:
      """
      args.length
      """
    Then the argument script answers '2'

  Scenario: a documentation checker walks a file it is confined to
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.read("note.txt").split("\n").filter({ line -> line.length > 0 }).length
      """
    Then its value is '2'

  Scenario: a userscript generator writes into the sandbox it was given
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.write("draft.mnd", "// generated\n")
      fs.read("draft.mnd").startsWith("// generated")
      """
    Then its value is 'true'
