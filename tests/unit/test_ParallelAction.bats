#!/usr/bin/env bats

load '../setup'

setup() {
  setup_mock_path
  create_workdir
  load_test_config
  stub_all_temps
  source "${LIB_DIR}/ParallelAction.sh"

  # Pre-declare all mock flags so inline VAR=value prefixes on function calls are visible to child processes
  export MOCK_LDAPSEARCH_FAIL=0
  export MOCK_ZMMAILBOX_FAIL=0
  export MOCK_ZMMAILBOX_EMPTY=0
  export MOCK_ZMMAILBOX_204=0
  export MOCK_LDAPADD_FAIL=0
  export MOCK_LDAPDELETE_FAIL=0

  # Create a blockedlist in a place ldap_filter can find it
  BLOCKEDLIST_DIR="$(mktemp -d)"
  mkdir -p "${BLOCKEDLIST_DIR}/zmbackup"
  touch "${BLOCKEDLIST_DIR}/zmbackup/blockedlist.conf"
  # Patch /etc/zmbackup/blockedlist.conf reference via a symlink trick in tests
  # We use BATS_TMPDIR as a scratch area
  export BLOCKEDLIST_DIR
}

teardown() {
  unset STYPE SESSION
  cleanup_temps
  destroy_workdir
  rm -rf "${BLOCKEDLIST_DIR:-}"
}

# ---------------------------------------------------------------------------
# ldap_backup
# ---------------------------------------------------------------------------

@test "ldap_backup: success sets ERRCODE=0" {
  MOCK_LDAPSEARCH_FAIL=0
  ldap_backup "user@example.com" "(objectclass=zimbraAccount)"
  [ "$ERRCODE" -eq 0 ]
}

@test "ldap_backup: success creates ldiff file" {
  MOCK_LDAPSEARCH_FAIL=0
  ldap_backup "user@example.com" "(objectclass=zimbraAccount)"
  [ -f "${TEMPDIR}/user@example.com.ldiff" ]
}

@test "ldap_backup: failure sets ERRCODE=1" {
  MOCK_LDAPSEARCH_FAIL=1 ldap_backup "user@example.com" "(objectclass=zimbraAccount)"
  [ "$ERRCODE" -eq 1 ]
}

# ---------------------------------------------------------------------------
# mailbox_backup
# ---------------------------------------------------------------------------

@test "mailbox_backup: full backup success sets ERRCODE=0" {
  INC="FALSE"
  MOCK_ZMMAILBOX_FAIL=0
  MOCK_ZMMAILBOX_EMPTY=0
  mailbox_backup "user@example.com"
  [ "$ERRCODE" -eq 0 ]
}

@test "mailbox_backup: full backup creates tgz file" {
  INC="FALSE"
  MOCK_ZMMAILBOX_FAIL=0
  MOCK_ZMMAILBOX_EMPTY=0
  mailbox_backup "user@example.com"
  [ -f "${TEMPDIR}/user@example.com.tgz" ]
}

@test "mailbox_backup: failure sets ERRCODE=1" {
  INC="FALSE"
  MOCK_ZMMAILBOX_FAIL=1 mailbox_backup "user@example.com"
  [ "$ERRCODE" -eq 1 ]
}

@test "mailbox_backup: empty tgz file sets ERRCODE=1 and removes file" {
  INC="FALSE"
  MOCK_ZMMAILBOX_FAIL=0
  MOCK_ZMMAILBOX_EMPTY=1 mailbox_backup "user@example.com"
  [ "$ERRCODE" -eq 1 ]
  [ ! -f "${TEMPDIR}/user@example.com.tgz" ]
}

@test "mailbox_backup: HTTP 204 No Content sets ERRCODE=0" {
  INC="FALSE"
  MOCK_ZMMAILBOX_204=1 mailbox_backup "user@example.com"
  [ "$ERRCODE" -eq 0 ]
}

@test "mailbox_backup: HTTP 204 No Content logs at info level not error" {
  INC="FALSE"
  MOCK_ZMMAILBOX_204=1 mailbox_backup "user@example.com"
  grep -q "\[local7.info\]" "${LOGFILE}"
  ! grep -q "\[local7.err\]" "${LOGFILE}"
}

@test "mailbox_backup: incremental with TXT session reads date from sessions.txt" {
  INC="TRUE"
  SESSION_TYPE="TXT"
  echo "inc-20240101:user@example.com:01/01/24" >> "${WORKDIR}/sessions.txt"
  MOCK_ZMMAILBOX_FAIL=0
  MOCK_ZMMAILBOX_EMPTY=0
  mailbox_backup "user@example.com"
  [ "$ERRCODE" -eq 0 ]
}

@test "mailbox_backup: incremental with SQLITE3 session reads date from database" {
  INC="TRUE"
  SESSION_TYPE="SQLITE3"
  sqlite3 "${WORKDIR}/sessions.sqlite3" < "${PROJECT_ROOT}/project/lib/sqlite3/database.sql"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_session values('full-20240101120000','2024-01-01T12:00:00.000',
     '2024-01-01T12:30:00.000','100M','Full Backup','FINISHED')"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_account(email,sessionID,account_size,initial_date,conclusion_date)
     values('user@example.com','full-20240101120000','50M','2024-01-01T12:00:00.000','2024-01-01T12:30:00.000')"
  MOCK_ZMMAILBOX_FAIL=0
  MOCK_ZMMAILBOX_EMPTY=0
  mailbox_backup "user@example.com"
  [ "$ERRCODE" -eq 0 ]
}

# ---------------------------------------------------------------------------
# ldap_restore
# ---------------------------------------------------------------------------

@test "ldap_restore: success returns 0" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  MOCK_LDAPDELETE_FAIL=0
  MOCK_LDAPADD_FAIL=0
  run ldap_restore "$session" "user@example.com"
  [ "$status" -eq 0 ]
}

@test "ldap_restore: returns 1 when ldiff has no DN line" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  echo "objectClass: top" > "${WORKDIR}/${session}/user@example.com.ldiff"
  run ldap_restore "$session" "user@example.com"
  [ "$status" -eq 1 ]
  [[ "$output" == *"Could not extract DN"* ]]
}

@test "ldap_restore: returns non-zero when ldapadd fails" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  MOCK_LDAPADD_FAIL=1
  run ldap_restore "$session" "user@example.com"
  [ "$status" -ne 0 ]
}

# ---------------------------------------------------------------------------
# mailbox_restore
# ---------------------------------------------------------------------------

@test "mailbox_restore: success returns 0" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/user@example.com.tgz"
  MOCK_ZMMAILBOX_FAIL=0
  run mailbox_restore "$session" "user@example.com"
  [ "$status" -eq 0 ]
}

@test "mailbox_restore: failure prints error and returns non-zero" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/user@example.com.tgz"
  MOCK_ZMMAILBOX_FAIL=1
  run mailbox_restore "$session" "user@example.com"
  [ "$status" -ne 0 ]
  [[ "$output" == *"Error during the restore"* ]]
}

@test "mailbox_restore: prints skip message and returns 0 when output contains 'No such file or directory'" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/user@example.com.tgz"
  export MOCK_ZMMAILBOX_NOFILE=1
  run mailbox_restore "$session" "user@example.com"
  [ "$status" -eq 0 ]
  [[ "$output" == *"skipping"* ]]
}

@test "mailbox_restore: writes to MAIL_FAILDIR on failure" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/user@example.com.tgz"
  MAIL_FAILDIR=$(mktemp -d)
  export MAIL_FAILDIR
  MOCK_ZMMAILBOX_FAIL=1
  run mailbox_restore "$session" "user@example.com"
  [ -f "$MAIL_FAILDIR/user@example.com" ]
  rm -rf "$MAIL_FAILDIR"
}

@test "mailbox_restore: does not write to MAIL_FAILDIR on success" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/user@example.com.tgz"
  MAIL_FAILDIR=$(mktemp -d)
  export MAIL_FAILDIR
  MOCK_ZMMAILBOX_FAIL=0
  mailbox_restore "$session" "user@example.com"
  [ ! -f "$MAIL_FAILDIR/user@example.com" ]
  rm -rf "$MAIL_FAILDIR"
}

@test "ldap_restore: writes to LDAP_FAILDIR when ldapadd fails" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  LDAP_FAILDIR=$(mktemp -d)
  export LDAP_FAILDIR
  MOCK_LDAPADD_FAIL=1
  run ldap_restore "$session" "user@example.com"
  [ -f "$LDAP_FAILDIR/user@example.com" ]
  rm -rf "$LDAP_FAILDIR"
}

@test "ldap_restore: writes to LDAP_FAILDIR when ldiff has no DN" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  echo "objectClass: top" > "${WORKDIR}/${session}/user@example.com.ldiff"
  LDAP_FAILDIR=$(mktemp -d)
  export LDAP_FAILDIR
  run ldap_restore "$session" "user@example.com"
  [ -f "$LDAP_FAILDIR/user@example.com" ]
  rm -rf "$LDAP_FAILDIR"
}

@test "ldap_restore: does not write to LDAP_FAILDIR on success" {
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  LDAP_FAILDIR=$(mktemp -d)
  export LDAP_FAILDIR
  MOCK_LDAPDELETE_FAIL=0
  MOCK_LDAPADD_FAIL=0
  ldap_restore "$session" "user@example.com"
  [ ! -f "$LDAP_FAILDIR/user@example.com" ]
  rm -rf "$LDAP_FAILDIR"
}

# ---------------------------------------------------------------------------
# ldap_filter
# ---------------------------------------------------------------------------

@test "ldap_filter: appends account to TEMPACCOUNT when not blocked" {
  # Create empty blockedlist in expected location
  # We temporarily redirect the hardcoded path using a bash function override
  grep() {
    if [[ "$*" == *"blockedlist.conf"* ]]; then
      return 1  # not in blocklist
    fi
    command grep "$@"
  }
  LOCK_BACKUP="false"
  ldap_filter "user@example.com"
  grep -q "user@example.com" "$TEMPACCOUNT"
}

@test "ldap_filter: does not add blocked account to TEMPACCOUNT" {
  grep() {
    if [[ "$*" == *"blockedlist.conf"* ]]; then
      return 0  # found in blocklist
    fi
    command grep "$@"
  }
  LOCK_BACKUP="false"
  run ldap_filter "spam@example.com"
  [ "$status" -eq 0 ]
  [[ "$output" == *"blocked list"* ]]
  run grep -q "spam@example.com" "$TEMPACCOUNT"
  [ "$status" -ne 0 ]
}

@test "ldap_filter: skips account already backed up today (TXT mode)" {
  LOCK_BACKUP="true"
  SESSION_TYPE="TXT"
  local today
  today="$(date +%m/%d/%y)"
  echo "full-20240101:user@example.com:${today}" > "${WORKDIR}/sessions.txt"
  grep() {
    if [[ "$*" == *"blockedlist.conf"* ]]; then
      return 1
    fi
    command grep "$@"
  }
  run ldap_filter "user@example.com"
  [[ "$output" == *"already has backup today"* ]]
}

@test "ldap_filter: skips account already backed up today (SQLITE3 mode)" {
  LOCK_BACKUP="true"
  SESSION_TYPE="SQLITE3"
  sqlite3 "${WORKDIR}/sessions.sqlite3" < "${PROJECT_ROOT}/project/lib/sqlite3/database.sql"
  local now
  now="$(date +%Y-%m-%dT%H:%M:%S.%N)"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_session values('full-20240101','2024-01-01T00:00:00.000',
     '${now}','100M','Full Backup','FINISHED')"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_account(email,sessionID,account_size,initial_date,conclusion_date)
     values('user@example.com','full-20240101','50M','${now}','${now}')"
  grep() {
    if [[ "$*" == *"blockedlist.conf"* ]]; then
      return 1
    fi
    command grep "$@"
  }
  run ldap_filter "user@example.com"
  [[ "$output" == *"already has backup today"* ]]
}
