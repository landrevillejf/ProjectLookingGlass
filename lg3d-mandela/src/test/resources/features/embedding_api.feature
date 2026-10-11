Feature: The embedding API as a host meets it
  The two lines an app writes to run a script, the report-only {@code check} an
  editor's diagnostic line reads, and the {@code about} summary a language list
  shows. Mirrors the JUnit EmbeddingApiTest through the public facade.

  Scenario Outline: one-shot evaluation answers the last expression
    When the host evaluates the program:
      """
      <source>
      """
    Then the host reads the answer '<answer>'

    Examples:
      | source              | answer  |
      | 1 + 2               | 3       |
      | "a" + "b"           | ab      |
      | 2.5 * 4             | 10.0    |
      | 7 % 3               | 1       |

  Scenario: the answer of a multi-statement program is its last value
    When the host evaluates the program:
      """
      let x = 5
      x * x
      """
    Then the host reads the answer '25'

  Scenario: check answers findings for a sound program with none
    When the host checks the program:
      """
      let x = 1
      x
      """
    Then the check is clean

  Scenario: check names the rule a broken program broke
    When the host checks the program:
      """
      let x =
      """
    Then the check reports the rule syntax

  Scenario: the engine describes itself to a tool that lists languages
    Then the engine says its language is 'Mandela'
    And the engine says its file extension is 'mnd'
