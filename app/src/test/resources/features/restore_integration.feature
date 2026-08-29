Feature: Restore lifecycle across real adapters

  RestoreService wired against the same real port implementations as
  backup_integration.feature. Each scenario first runs a real backup to
  produce genuine LDIF/tgz artifacts, then restores from them.

  Scenario: Restoring LDAP re-adds a deleted account entry
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    And I have run an LDAP backup for "alice@example.com"
    And the LDAP entry for "alice@example.com" is deleted
    When I restore LDAP for "alice@example.com"
    Then the LDAP restore result has 0 failed accounts
    And the LDAP entry for "alice@example.com" exists again

  Scenario: Restoring a domain treats an already-existing entry as success
    Given an in-memory LDAP directory with domain "other.com"
    And I have run a domain backup for "other.com"
    When I restore domain "other.com"
    Then the domain restore result has 0 failed accounts

  Scenario: Restoring a mailbox posts the archive to the WireMock restore endpoint
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    And the mailbox export endpoint for "alice@example.com" returns tgz content "mailbox-bytes"
    And I have run a full backup for "alice@example.com"
    When I restore the mailbox for "alice@example.com"
    Then the WireMock server received a mailbox restore POST for "alice@example.com" with body "mailbox-bytes"

  Scenario: Restoring a mailbox into a different account posts to the destination's endpoint
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    And the mailbox export endpoint for "alice@example.com" returns tgz content "mailbox-bytes"
    And I have run a full backup for "alice@example.com"
    When I restore the mailbox for "alice@example.com" into "bob@example.com"
    Then the WireMock server received a mailbox restore POST for "bob@example.com" with body "mailbox-bytes"

  Scenario: A restore failure is reported without throwing
    Given an in-memory LDAP directory with no data
    When I restore LDAP for "missing@example.com" from session "ldap-no-such-session"
    Then the LDAP restore result has 1 failed accounts
