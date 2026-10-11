Feature: The vocabulary an editor can trust
  The words and names a tool may show. The reserved set is pinned exactly, because
  making a word reserved costs every script that used it as a name and a reviewer must
  see that happen. The keyword list must never colour a word the parser does not read,
  and a half-typed buffer must still offer the names it already declared.

  Scenario: the reserved words are exactly the ones the grammar cannot do without
    Then the reserved words are exactly:
      """
      and as break catch class continue defer else enum extends false finally for
      fun if import in is let loop match not null or return super this throw true
      try type use var while
      """

  Scenario Outline: a word the parser really reads is offered as a keyword
    Then the keyword list contains <word>

    Examples:
      | word   |
      | fun    |
      | let    |
      | var    |
      | class  |
      | type   |
      | enum   |
      | match  |
      | use    |
      | export |
      | step   |

  Scenario Outline: a word the parser ignores is never shown as a keyword
    Then the keyword list does not contain <word>

    Examples:
      | word |
      | get  |
      | set  |
      | with |
      | on   |
      | out  |

  Scenario Outline: a name a program may read without declaring it is a global
    Then the globals list contains <name>

    Examples:
      | name    |
      | println |
      | print   |
      | str     |
      | int     |
      | typeOf  |
      | keys    |

  Scenario Outline: a module a use statement can ask for is listed
    Then the module list contains <module>

    Examples:
      | module |
      | json   |
      | math   |
      | fs     |
      | env    |
      | time   |

  Scenario Outline: the members a value carries are mirrorable to a tool
    Then a <type> value has the member <member>

    Examples:
      | type | member  |
      | Str  | length  |
      | Str  | upper   |
      | Str  | lower   |
      | Str  | split   |
      | List | map     |
      | List | filter  |
      | List | join    |

  Scenario: a half-written buffer keeps the names it declared
    When the editor reads the buffer:
      """
      fun outer(row: Map) -> Str {
        let inner = row.label
        return inner
      }
      for item in 1..3 { item }
      let [left, right] = [1, 2]
      """
    Then the buffer's vocabulary names outer
    And the buffer's vocabulary names inner
    And the buffer's vocabulary names item
    And the buffer's vocabulary names left
    And the buffer's vocabulary names right
