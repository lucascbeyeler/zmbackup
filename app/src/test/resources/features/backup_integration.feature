Feature: Backup lifecycle across real adapters

  BackupService, HousekeepService, and SessionService wired against real port
  implementations: UnboundIdLdapAdapter backed by an in-memory LDAP directory,
  ZimbraRestMailboxExporter backed by a WireMock server standing in for
  Zimbra's REST mailbox endpoint, LocalStorageProvider backed by a temp
  directory, and SqliteMetadataStore backed by an in-memory SQLite database.

  Scenario: Back up discovered LDAP accounts writes real LDIF and SQLite metadata
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
      | bob@example.com   |
    When I run an LDAP backup
    Then the backup session status is FINISHED
    And the LDIF file for "alice@example.com" contains "dn: uid=alice,dc=example,dc=com"
    And the LDIF file for "bob@example.com" contains "mail: bob@example.com"
    And the metadata store has 2 account records for the session

  Scenario: Back up a domain writes real LDIF and SQLite metadata
    Given an in-memory LDAP directory with domain "other.com"
    When I run a domain backup
    Then the backup session status is FINISHED
    And the LDIF file for "other.com" contains "zimbraDomainName: other.com"

  Scenario: A real LDAP export failure marks the session FAILED
    Given an in-memory LDAP directory with no data
    When I run a domain backup for "nowhere.invalid"
    Then the backup session status is FAILED
    And the metadata store has 0 account records for the session

  Scenario: Listing sessions returns most recently started first
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    When I run an LDAP backup for "alice@example.com"
    And I run a signature backup for "alice@example.com"
    Then listing sessions returns the signature session before the LDAP session

  Scenario: Deleting a session removes real files and metadata
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    And I have run an LDAP backup for "alice@example.com"
    When I delete that session
    Then the session's LDIF file no longer exists
    And the metadata store has no record of the session

  Scenario: Housekeeping rotates old sessions across real storage and metadata
    Given a stored session "ldap-old" completed 10 days ago with account "alice@example.com"
    When I rotate sessions older than 7 days
    Then the old session's LDIF file no longer exists
    And the metadata store has no record of the old session

  Scenario: A full backup writes real LDIF and the WireMock mailbox archive
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    And the mailbox export endpoint for "alice@example.com" returns tgz content "mailbox-bytes"
    When I run a full backup
    Then the backup session status is FINISHED
    And the LDIF file for "alice@example.com" contains "dn: uid=alice,dc=example,dc=com"
    And the mailbox archive for "alice@example.com" contains "mailbox-bytes"
    And the metadata store has 1 account records for the session

  Scenario: A full backup marks the session FAILED when the mailbox endpoint errors
    Given an in-memory LDAP directory with accounts:
      | alice@example.com |
    And the mailbox export endpoint for "alice@example.com" returns HTTP 500
    When I run a full backup
    Then the backup session status is FAILED
    And the LDIF file for "alice@example.com" contains "dn: uid=alice,dc=example,dc=com"
    And the metadata store has 0 account records for the session
