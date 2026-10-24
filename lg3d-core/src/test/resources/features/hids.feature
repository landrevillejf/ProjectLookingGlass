Feature: Host-intrusion-detection seam for the LFS desktop manager

  The HIDS panel is a thin front-end over the intrusion-detection tools the
  lg3d build profile ships: fail2ban, rkhunter, lynis and auditd. Detection is
  a pure filter over injected PATH probes, every operation builds an exact
  argument vector (never a shell string), and every tool's output is parsed by
  a pure function the UI renders verbatim beside its one-line summary. A
  read-only query runs unprivileged; a mutation or long scan is escalated, and
  the seam refuses to cross that line. None of these scenarios installs a tool
  or spawns a process.

  Scenario: a tool whose binary is on PATH is present
    Given hids probes "fail2ban-client,rkhunter,lynis,auditctl,ausearch"
    When the tool "FAIL2BAN" is checked for presence
    Then the tool is present

  Scenario: a tool that is not installed is reported absent, never as an error
    Given hids probes ""
    When the tool "LYNIS" is checked for presence
    Then the tool is not present

  Scenario: auditd is present when only one of its two binaries exists
    Given hids probes "ausearch"
    When the tool "AUDITD" is checked for presence
    Then the tool is present

  Scenario: the installed tools are listed in a stable display order
    Given hids probes "lynis,fail2ban-client,ausearch"
    When the installed tools are listed
    Then the installed tools are "FAIL2BAN,LYNIS,AUDITD" in order

  Scenario: a read-only query builds an exact unprivileged vector
    Given the hids operation "FAIL2BAN_JAIL_STATUS"
    When its vector is built with parameters "sshd"
    Then the hids vector is "fail2ban-client,status,sshd"

  Scenario: a ban builds the mutating fail2ban vector
    Given the hids operation "FAIL2BAN_BAN"
    When its vector is built with parameters "sshd,1.2.3.4"
    Then the hids vector is "fail2ban-client,set,sshd,banip,1.2.3.4"

  Scenario: a malformed parameter yields an empty vector rather than a partial command
    Given the hids operation "FAIL2BAN_BAN"
    When its vector is built with parameters "sshd,1.2.3.4 5.6.7.8"
    Then the hids vector is empty

  Scenario: a long scan is classified as mutating so the UI escalates it
    Given the hids operation "RKHUNTER_CHECK"
    When its confirmation class is queried
    Then the operation is mutating

  Scenario: a status read is classified read-only so the UI never escalates it
    Given the hids operation "AUDITCTL_STATUS"
    When its confirmation class is queried
    Then the operation is not mutating

  Scenario: the fail2ban server status is parsed into a running summary
    When the fail2ban status output "Status\n|- Number of jails:\t2\n`- Jail list:\tsshd, postfix\n" is parsed
    Then the fail2ban summary is "2 jails: sshd, postfix"

  Scenario: a jail's ban state is parsed into counts and addresses
    When the fail2ban jail output "Status for the jail: sshd\n`- Actions\n   |- Currently banned:\t2\n   |- Total banned:\t7\n   `- Banned IP list:\t1.2.3.4 5.6.7.8\n" is parsed
    Then the jail summary is "sshd: 2 banned now, 7 total"

  Scenario: rkhunter warnings are counted from the scan output
    When the rkhunter output "Checking system binaries\nWarning: replaced binary\nWarning: hidden dir\nSystem checks summary\n" is parsed
    Then the rkhunter summary is "2 warnings"

  Scenario: the lynis hardening index and counts are parsed from the report
    When the lynis output "  Hardening index : [72]\n  Suggestions: 12\n  Warnings: 3\n" is parsed
    Then the lynis summary is "hardening index 72, 3 warning(s), 12 suggestion(s)"

  Scenario: the auditd status is parsed from the single-line AUDIT_STATUS form
    When the auditd output "AUDIT_STATUS: enabled=1 pid=1000 backlog_limit=8192 lost=0 backlog=2\n" is parsed
    Then the auditd summary is "enabled, pid 1000, 0 lost"

  Scenario: ausearch events are counted by record separator
    When the ausearch output "type=USER_AUTH msg=audit(1)\n----\ntype=USER_LOGIN msg=audit(2)\n----\n" is counted
    Then the event count is 2

  Scenario: a read helper refuses a mutating operation without spawning
    When the read helper is asked to run "FAIL2BAN_BAN"
    Then the read helper did not start a process

  Scenario: a mutating helper refuses a read-only operation without escalating
    When the mutating helper is asked to run "AUDITCTL_STATUS"
    Then the mutating helper reported an error
