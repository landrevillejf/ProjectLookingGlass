Feature: The standard library as a script sees it
  Every built-in type's members, the global functions, and the std.* modules a
  script reaches with 'use'. Each case is the source a program writes and the text
  its answer displays, mirroring the JUnit StandardLibraryTest.

  Scenario Outline: a Str member answers as the library promises
    When the one-line program <expr> runs
    Then its value is <answer>

    Examples:
      | expr                            | answer    |
      | '"abc".upper()'                 | 'ABC'     |
      | '"ABC".lower()'                 | 'abc'     |
      | '"  pad  ".trim()'              | 'pad'     |
      | '"a,b,c".split(",").length'     | '3'       |
      | '"aXbXc".replace("X", "-")'     | 'a-b-c'   |
      | '"hello".contains("ell")'       | 'true'    |
      | '"hello".startsWith("he")'      | 'true'    |
      | '"hello".indexOf("z")'          | '-1'      |
      | '"hello"[1]'                    | 'e'       |
      | '"hello".substring(1, 3)'       | 'el'      |
      | '"42".toInt() + 1'              | '43'      |
      | '"2.5".toDouble() * 2'          | '5.0'     |
      | '"ab".repeat(3)'                | 'ababab'  |
      | '"7".padStart(4, "0")'          | '0007'    |
      | '"hello".length'                | '5'       |

  Scenario Outline: a List member answers as the library promises
    When the one-line program <expr> runs
    Then its value is <answer>

    Examples:
      | expr                                            | answer   |
      | '[1, 2, 3].length'                              | '3'      |
      | '[1, 2, 3].first'                               | '1'      |
      | '[1, 2, 3].last'                                | '3'      |
      | '[].isEmpty()'                                  | 'true'   |
      | '[3, 1, 2].sort().join(",")'                    | '1,2,3'  |
      | '[1, 2].reversed().join(",")'                   | '2,1'    |
      | '[1, 2, 3].map({ x -> x * 2 }).join(",")'       | '2,4,6'  |
      | '[1, 2, 3, 4].filter({ x -> x % 2 == 0 }).join(",")' | '2,4' |
      | '[1, 2, 3].reduce({ a, b -> a + b })'           | '6'      |
      | '[1, 2, 3].any({ x -> x > 2 })'                 | 'true'   |
      | '[1, 2, 3].find({ x -> x == 2 })'               | '2'      |
      | '[1, 2, 3].indexOf(2)'                          | '1'      |
      | '[1, 2, 3].sum()'                               | '6'      |
      | '[3, 1].min()'                                  | '1'      |
      | '[3, 1].max()'                                  | '3'      |
      | '[[1], [2]].flatten().join(",")'                | '1,2'    |
      | '[1, 2, 3].get(9, 0)'                           | '0'      |

  Scenario Outline: a Map, range and number member answers as the library promises
    When the one-line program <expr> runs
    Then its value is <answer>

    Examples:
      | expr                                              | answer    |
      | 'let m = {"a": 1}; m.get("a")'                    | '1'       |
      | 'let m = {"a": 1}; m.get("b", 9)'                 | '9'       |
      | 'let m = {"a": 1}; m.has("a")'                    | 'true'    |
      | 'let m = {"a": 1}; m.keys.join(",")'              | 'a'       |
      | 'let m = {"a": 1}; m.mapValues({ v -> v + 1 }).get("a")' | '2' |
      | '(0..2).toList().join(",")'                       | '0,1,2'   |
      | '(1..3).sum()'                                    | '6'       |
      | '(0..100).sum()'                                  | '5050'    |
      | '(1..3).contains(3)'                              | 'true'    |
      | '(0..8 step 3).toList().join(",")'                | '0,3,6'   |
      | '(-5).abs()'                                      | '5'       |
      | '2.7.round()'                                     | '3'       |
      | '2.2.floor()'                                     | '2.0'     |
      | '2.1.ceil()'                                      | '3.0'     |
      | '(9).sqrt()'                                      | '3.0'     |
      | '(5).isWhole()'                                   | 'true'    |

  Scenario Outline: a global function answers as the library promises
    When the one-line program <expr> runs
    Then its value is <answer>

    Examples:
      | expr                            | answer   |
      | 'str(12)'                       | '12'     |
      | 'int("7")'                      | '7'      |
      | 'double("1.5") + 0.5'           | '2.0'    |
      | 'bool(0)'                       | 'false'  |
      | 'length([1, 2])'                | '2'      |
      | 'typeOf(1.5)'                   | 'Double' |
      | 'min(3, 1, 2)'                  | '1'      |
      | 'max(3, 1, 2)'                  | '3'      |
      | 'abs(-2)'                       | '2'      |
      | 'keys({"a": 1}).join(",")'      | 'a'      |

  Scenario: the json module encodes and parses
    When the program runs:
      """
      use std.json
      json.encode([1, "a", null])
      """
    Then its value is '[1,"a",null]'

  Scenario: the json module reads a field back out
    When the program runs:
      """
      use std.json
      json.parse("{\"k\": [1, 2]}").k[1]
      """
    Then its value is '2'

  Scenario: the math module answers in Doubles
    When the program runs:
      """
      use std.math
      math.pow(2, 10)
      """
    Then its value is '1024.0'

  Scenario: the time module renders an instant in ISO form
    When the program runs:
      """
      use std.time
      time.iso(0)
      """
    Then its value is '1970-01-01T00:00:00Z'

  Scenario: a desktop script reads the file beside it
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.read("note.txt").startsWith("alpha")
      """
    Then its value is 'true'

  Scenario: a desktop script sees the file is not there
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.exists("missing.txt")
      """
    Then its value is 'false'

  Scenario: a desktop script writes and reads back
    Given a desktop sandbox engine over a temporary directory
    When the program runs:
      """
      use std.fs
      fs.write("out.txt", "x")
      fs.append("out.txt", "y")
      fs.read("out.txt")
      """
    Then its value is 'xy'

  Scenario Outline: a library failure is a catchable error with a kind
    When the one-line program <expr> runs
    Then the program raises a <kind> error

    Examples:
      | expr                       | kind        |
      | '(-9).sqrt()'              | ValueError  |
      | '[1, 2].map(3)'            | TypeError   |
      | '"hello".charAt(9)'        | IndexError  |
      | 'use std.nope'             | ValueError  |
      | 'assert(1 == 2, "nope")'   | AssertError |
