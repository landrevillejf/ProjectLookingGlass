Feature: Crash reporting
  The desktop's bug-report pipeline signs every uncaught exception so the
  same defect always files the same issue (CrashSignature).

  Scenario: A NullPointerException produces a non-empty hex signature
    Given an uncaught exception of type "java.lang.NullPointerException" with message "test"
    When the crash signature is computed
    Then the signature is a non-empty hexadecimal string

  Scenario: The same exception always produces the same signature
    Given an uncaught exception of type "java.lang.NullPointerException" with message "test"
    When the crash signature is computed
    And the crash signature is computed again
    Then both signatures are identical
