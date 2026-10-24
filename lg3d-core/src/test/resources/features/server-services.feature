Feature: Server-services catalog for the LFS desktop manager

  The Servers panel is a thin front-end over a curated catalog of the internet
  servers and databases the lg3d build profile installs. Detection is a pure
  filter over injected PATH/filesystem probes, and every lifecycle operation is
  delegated to the init-system abstraction with the catalog entry's service unit,
  so a server is driven exactly like any other service and no init logic is
  duplicated. The config view is a bounded read-only file view; the GUI never
  writes /etc. None of these scenarios installs a server or spawns a process.

  Scenario: a server whose binary is on PATH is installed
    Given server probes with binaries "httpd" and configs ""
    When the catalog entry "httpd" is checked for installation
    Then the server is installed

  Scenario: a server whose only trace is its config file is installed
    Given server probes with binaries "" and configs "/etc/named.conf"
    When the catalog entry "named" is checked for installation
    Then the server is installed

  Scenario: a server with neither binary nor config is not installed
    Given server probes with binaries "" and configs ""
    When the catalog entry "postfix" is checked for installation
    Then the server is not installed

  Scenario: the installed list is filtered and kept in catalog order
    Given server probes with binaries "smbd,httpd" and configs ""
    When the installed servers are listed
    Then the installed servers are "Apache httpd,Samba (SMB/CIFS)" in order

  Scenario: the lifecycle vector is exactly the init abstraction's for the unit
    Given the catalog entry "httpd"
    When the "START" vector is built for init "SYSTEMD"
    Then the vector is "systemctl,start,httpd"

  Scenario: an unknown init yields an empty vector rather than a shell string
    Given the catalog entry "httpd"
    When the "START" vector is built for init "UNKNOWN"
    Then the vector is empty

  Scenario: sysvinit cannot enable a server at boot
    Given the init system "SYSVINIT"
    When support for the "ENABLE" operation is queried
    Then the operation is not supported

  Scenario: Tomcat has no single well-known config path
    Given the catalog entry "tomcat"
    When its config view is resolved
    Then the config is reported "not present"
