Feature: The grammar's promises and refusals
  What parses and what does not, phrased as the two opposite shipped defects the
  SyntaxTest guards against: a form that stops parsing breaks every script already
  written, and a mistake that parses runs and answers wrongly. Each refusal names
  the fragment of the message an author needs to fix it.

  Scenario Outline: every form the grammar accepts parses
    When the snippet parses:
      """
      <source>
      """
    Then the snippet is accepted

    Examples: functions, lambdas and returns
      | source                          |
      | fun f() -> Int { return 1 }     |
      | [1, 2].map { x -> x * 2 }        |
      | [1, 2].map({ x -> x * 2 })       |
      | let g = { -> 2 }                |

    Examples: control flow
      | source                          |
      | for i in 0..8 step 2 { i }      |
      | if true { 1 }                   |
      | loop { break }                   |

    Examples: expressions
      | source                          |
      | 2 ** 10 % 3                     |
      | 1 \|> plus(2)                   |
      | null ?? "alt"                   |
      | match 3 { 1 => "a", _ => "b" }   |

  Scenario Outline: a mistake is reported with the words an author needs
    When the snippet parses:
      """
      <source>
      """
    Then the parser refuses it, mentioning '<fragment>'

    Examples:
      | source                   | fragment                     |
      | 1 +                      | expected an expression       |
      | let                      | expected a pattern           |
      | let 1 = 2                | needs a name                 |
      | fun f(                   | unterminated parameter list  |
      | if { 1 }                 | a condition                  |
      | return 1                 | return                       |
