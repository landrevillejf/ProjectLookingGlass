Feature: Help Center content
  The Help Center ships a JavaHelp bundle with a HelpSet, a map, a table of
  contents and a keyword index. Every referenced topic must resolve so the
  viewer never shows a blank pane.

  Scenario: The help content resources exist on the classpath
    Given the help content directory
    Then the HelpSet file "lg3d-help.hs" exists
    And the map file "map.jhm" exists
    And the table of contents "toc.xml" exists
