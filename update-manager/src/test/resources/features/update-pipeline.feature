Feature: Update pipeline
  The update checker compares semantic version strings to decide whether
  a published release is newer than the running desktop.

  Scenario: A higher minor version is detected as newer
    When comparing latest "1.70.0" against current "1.69.0"
    Then the result is newer

  Scenario: The same version is not newer
    When comparing latest "1.69.0" against current "1.69.0"
    Then the result is not newer

  Scenario: A lower version is not newer
    When comparing latest "1.68.0" against current "1.69.0"
    Then the result is not newer
