Feature: SELinux policy management (inventory control H2, gap G2)
  The desktop already read the SELinux state (getenforce); it had no front-end
  for policy management. SecurityService now builds the exact semanage /
  restorecon / semodule / load_policy vectors and drives the ausearch ->
  audit2allow review pipe. Everything here reaches only the pure seam -
  detection, vector building, validation, parsing and the non-spawning run
  guards - so it runs headless with no SELinux toolchain installed.

  Scenario Outline: a policy tool is present only when its binary is on PATH
    Given selinux policy probes "<probes>"
    When the policy tool "<tool>" is checked for presence
    Then the policy tool is <present>

    Examples:
      | probes                                            | tool        | present     |
      | semanage                                          | SEMANAGE    | present     |
      | semanage                                          | RESTORECON  | not present |
      | restorecon,semodule                               | SEMODULE    | present     |
      | load_policy,ausearch,audit2allow                  | LOAD_POLICY | present     |
      | load_policy,ausearch,audit2allow                  | AUDIT2ALLOW | present     |
      |                                                   | SEMANAGE    | not present |

  Scenario Outline: each policy operation is driven by one binary
    Given the policy operation "<op>"
    When the tool that drives it is queried
    Then the driving tool is "<tool>"

    Examples:
      | op                     | tool        |
      | SEMANAGE_FCONTEXT_ADD  | SEMANAGE    |
      | SEMANAGE_BOOLEAN_SET   | SEMANAGE    |
      | SEMANAGE_PORT_DEL      | SEMANAGE    |
      | RESTORECON             | RESTORECON  |
      | SEMODULE_INSTALL       | SEMODULE    |
      | LOAD_POLICY            | LOAD_POLICY |
      | AUSEARCH_AVC           | AUSEARCH    |
      | AUDIT2ALLOW            | AUDIT2ALLOW |

  Scenario: a semanage list operation takes no parameters
    Given the policy operation "SEMANAGE_FCONTEXT_LIST"
    When the policy vector is built with parameters ""
    Then the selinux vector is "semanage,fcontext,-l"

  Scenario: adding a file-context rule carries the type and the path regex
    Given the policy operation "SEMANAGE_FCONTEXT_ADD"
    When the policy vector is built with parameters "httpd_sys_content_t,/var/www(/.*)?"
    Then the selinux vector is "semanage,fcontext,-a,-t,httpd_sys_content_t,/var/www(/.*)?"

  Scenario: a malformed file-context add yields no vector rather than a partial command
    Given the policy operation "SEMANAGE_FCONTEXT_ADD"
    When the policy vector is built with parameters "only_one_argument"
    Then the selinux vector is empty

  Scenario: setting a boolean maps on and off to the semanage flags
    Given the policy operation "SEMANAGE_BOOLEAN_SET"
    When the policy vector is built with parameters "httpd_can_network_connect,on"
    Then the selinux vector is "semanage,boolean,-m,--on,httpd_can_network_connect"

  Scenario: an invalid boolean value yields no vector
    Given the policy operation "SEMANAGE_BOOLEAN_SET"
    When the policy vector is built with parameters "httpd_can_network_connect,maybe"
    Then the selinux vector is empty

  Scenario: adding a port context carries type, protocol and port
    Given the policy operation "SEMANAGE_PORT_ADD"
    When the policy vector is built with parameters "http_port_t,tcp,8080"
    Then the selinux vector is "semanage,port,-a,-t,http_port_t,-p,tcp,8080"

  Scenario: restorecon relabels a path recursively and verbosely
    Given the policy operation "RESTORECON"
    When the policy vector is built with parameters "/var/www"
    Then the selinux vector is "restorecon,-Rv,/var/www"

  Scenario: a path carrying a control character is rejected before a vector is built
    Given the policy operation "RESTORECON"
    When the policy vector is built with parameters "/var/www\n/etc"
    Then the selinux vector is empty

  Scenario: semodule lists, installs and removes modules
    Given the policy operation "SEMODULE_INSTALL"
    When the policy vector is built with parameters "/root/mymodule.pp"
    Then the selinux vector is "semodule,-i,/root/mymodule.pp"

  Scenario: load_policy reloads the kernel policy with no parameters
    Given the policy operation "LOAD_POLICY"
    When the policy vector is built with parameters ""
    Then the selinux vector is "load_policy"

  Scenario: the AVC review pipe reads recent denials then drafts a module
    Given the policy operation "AUSEARCH_AVC"
    When the policy vector is built with parameters ""
    Then the selinux vector is "ausearch,-m,avc,-ts,recent"

  Scenario Outline: a policy write is destructive, a read is not
    Given the policy operation "<op>"
    When the policy confirmation class is queried
    Then the policy operation is <class>

    Examples:
      | op                     | class           |
      | SEMANAGE_FCONTEXT_ADD  | destructive     |
      | SEMANAGE_FCONTEXT_DEL  | destructive     |
      | SEMANAGE_BOOLEAN_SET   | destructive     |
      | RESTORECON             | destructive     |
      | SEMODULE_INSTALL       | destructive     |
      | SEMODULE_REMOVE        | destructive     |
      | LOAD_POLICY            | destructive     |
      | SEMANAGE_FCONTEXT_LIST | not destructive |
      | SEMODULE_LIST          | not destructive |
      | AUSEARCH_AVC           | not destructive |
      | AUDIT2ALLOW            | not destructive |

  Scenario Outline: parameter validation rejects an unsafe word or path
    When the token "<value>" is validated as a "<kind>"
    Then the value is <verdict>

    Examples:
      | value                 | kind  | verdict  |
      | httpd_sys_content_t   | token | accepted |
      | tcp                   | token | accepted |
      | two words             | token | rejected |
      | /var/www(/.*)?        | path  | accepted |
      | /home/a dir/b.pp      | path  | accepted |
      | /has\nnewline         | path  | rejected |

  Scenario: AVC denials are counted from the ausearch record lines
    When the AVC output "time->now\ntype=AVC msg=audit(1.2:3): avc:  denied  { read }\ntype=SYSCALL\n----\ntype=AVC msg=audit(1.2:4): avc:  denied  { write }" is counted
    Then the AVC denial count is 2

  Scenario: no AVC denials is reported as zero, not as an error
    When the AVC output "<no events>" is counted
    Then the AVC denial count is 0

  Scenario: an audit2allow draft is summarised by the rules it proposes
    When the audit2allow draft "module mydraft 1.0;\nrequire {\n type httpd_t;\n}\nallow httpd_t user_home_t:file read;\nallow httpd_t var_t:dir write;" is summarised
    Then the draft summary is "2 allow rules"

  Scenario: the read helper refuses a destructive operation before spawning
    When the policy read helper is asked to run "RESTORECON" with "/var/www"
    Then the policy read helper did not start a process

  Scenario: the read helper refuses a malformed read before spawning
    When the policy read helper is asked to run "SEMANAGE_FCONTEXT_DEL" with ""
    Then the policy read helper did not start a process

  Scenario: the mutating helper refuses a read-only operation before spawning
    When the policy mutating helper is asked to run "SEMODULE_LIST" with ""
    Then the policy mutating helper reported an error
