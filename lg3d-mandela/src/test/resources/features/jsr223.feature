Feature: The JSR-223 door a tool already knows how to open
  A host that embeds {@code javax.script} finds Mandela through the platform
  registry -- by name, by extension and by MIME type -- with no new dependency, and
  runs the real language through {@code eval}. The same engine the facade advertises.

  Scenario Outline: the registry lists the engine under every documented handle
    Then the registry finds the engine by <handle>

    Examples:
      | handle    |
      | name      |
      | extension |
      | mime      |

  Scenario: the engine runs the language, not a subset of it
    When the JSR-223 engine evaluates:
      """
      1 + 2
      """
    Then it answers '3'

  Scenario: the engine keeps a name a script declared for the host to read
    When the JSR-223 engine evaluates:
      """
      "a".upper()
      """
    Then it answers 'A'

  Scenario: a multi-line program runs through the same door
    When the JSR-223 engine evaluates:
      """
      let x = 5
      x * x
      """
    Then it answers '25'

  Scenario: the factory advertises the language the way a tool lists it
    Then the factory advertises the language 'Mandela'
