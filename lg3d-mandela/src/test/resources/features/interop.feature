Feature: The host bridge a script reads nothing special about
  A Java value handed to the engine crosses through the same conversion a binding
  would use, so a script author writes it as an ordinary list or function. The
  refusals -- an arbitrary object, an overloaded callable -- are what keep a host's
  whole object graph out of a sandboxed page.

  Scenario: a Java list crosses in as a script list
    Given the host binds names to the Java list ada and lin
    When the host evaluates the program:
      """
      names.join(",")
      """
    Then the host reads the answer 'ada,lin'

  Scenario: a Java function is called exactly like a script one
    Given the host binds tax to a Java function that doubles its argument
    When the host evaluates the program:
      """
      tax(21)
      """
    Then the host reads the answer '42'
