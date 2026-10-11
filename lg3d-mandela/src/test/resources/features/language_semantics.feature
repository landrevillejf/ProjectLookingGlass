Feature: Language semantics end to end
  The observable meaning of a Mandela program, from source to the value its last
  expression answers with. Every example here is the same source the JUnit
  LanguageTest pins, phrased as behaviour a reader of the guide can run.

  Scenario: arithmetic answers in the type it deserves
    When the program runs:
      """
      1 + 2 * 3
      """
    Then its value is '7'

  Scenario: parentheses group before multiplication
    When the program runs:
      """
      (1 + 2) * 3
      """
    Then its value is '9'

  Scenario: exponentiation is a power
    When the program runs:
      """
      2 ** 10
      """
    Then its value is '1024'

  Scenario: division answers exact between integers that divide
    When the program runs:
      """
      6 / 3
      """
    Then its value is '2'

  Scenario: division answers a Double otherwise
    When the program runs:
      """
      7 / 3
      """
    Then its value is '2.3333333333333335'

  Scenario: modulo is the remainder
    When the program runs:
      """
      7 % 3
      """
    Then its value is '1'

  Scenario: string interpolation reads names and expressions
    When the program runs:
      """
      let n = 7
      let d = 2.5
      "n=${n} d=${d} sum=${n + d}"
      """
    Then its value is 'n=7 d=2.5 sum=9.5'

  Scenario: a recursive function computes
    When the program runs:
      """
      fun fib(k: Int) -> Int {
        if k < 2 { return k }
        return fib(k - 1) + fib(k - 2)
      }
      fib(15)
      """
    Then its value is '610'

  Scenario: a trailing lambda maps a list
    When the program runs:
      """
      [1, 2, 3].map { v -> v * 2 }
      """
    Then its value is '[2, 4, 6]'

  Scenario: a closure captures its enclosing variable
    When the program runs:
      """
      fun counter() -> Fun {
        var total = 0
        return { -> total += 1; total }
      }
      let bump = counter()
      bump()
      bump()
      bump()
      """
    Then its value is '3'

  Scenario: a range with a stride is a value
    When the program runs:
      """
      (0..8 step 2).toList()
      """
    Then its value is '[0, 2, 4, 6, 8]'

  Scenario: membership uses the language's equality
    When the program runs:
      """
      1.0 in [1]
      """
    Then its value is 'true'

  Scenario: a class holds state across method calls
    When the program runs:
      """
      class Box(w: Int) {
        var hits = 0
        fun touch() -> Int { hits += 1; hits * w }
      }
      let b = Box(3)
      b.touch()
      b.touch()
      """
    Then its value is '6'

  Scenario: a type compares by value
    When the program runs:
      """
      type Point(x: Int, y: Int)
      Point(1, 2) == Point(1, 2)
      """
    Then its value is 'true'

  Scenario: a match with guards picks an arm
    When the program runs:
      """
      fun rank(v: Int) -> Str {
        match v {
          1 => "ace",
          n if n > 8 => "high",
          _ => "low:${v}"
        }
      }
      rank(1) + "," + rank(11) + "," + rank(4)
      """
    Then its value is 'ace,high,low:4'

  Scenario: a divide by zero is refused, never an infinity
    When the program runs:
      """
      1 / 0
      """
    Then the program raises a RangeError error

  Scenario: adding a string to a number is a type error
    When the program runs:
      """
      1 + "x"
      """
    Then the program raises a TypeError error

  Scenario: an unbounded loop hits the instruction budget
    When the program runs:
      """
      var i = 0
      loop { i += 1 }
      """
    Then the program raises a LimitError error
