#!/usr/bin/env bats

load '../setup'

setup() {
  setup_mock_path
  create_workdir
  load_test_config
  stub_all_temps
  source "${LIB_DIR}/ParallelAction.sh"
  source "${LIB_DIR}/ListAction.sh"
  source "${LIB_DIR}/RestoreAction.sh"
  PID="$(mktemp)"
  export PID

  # Pre-declare SESSION so parallel workers see the value tests assign to it
  export SESSION
  # Pre-declare mock flags at safe defaults
  export MOCK_ZMMAILBOX_FAIL=0
  export MOCK_LDAPADD_FAIL=0
  export MOCK_LDAPDELETE_FAIL=0

  # Export functions required by the parallel mock subprocess
  export -f mailbox_restore ldap_restore
}

teardown() {
  unset STYPE SESSION
  cleanup_temps
  destroy_workdir
}

# ---------------------------------------------------------------------------
# restore_main_mailbox
# ---------------------------------------------------------------------------

@test "restore_main_mailbox: prints nothing-to-do when session not in TXT" {
  SESSION_TYPE="TXT"
  run restore_main_mailbox "nonexistent-session" "" ""
  [[ "$output" == *"Nothing to do"* ]]
}

@test "restore_main_mailbox: restores when session exists in TXT" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_ZMMAILBOX_FAIL=0
  run restore_main_mailbox "$session" "" ""
  [ "$status" -eq 0 ]
  [[ "$output" == *"started"* ]]
}

@test "restore_main_mailbox: shows account count in completion message on success" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_ZMMAILBOX_FAIL=0
  run restore_main_mailbox "$session" "" ""
  [ "$status" -eq 0 ]
  [[ "$output" == *"1/1 accounts restored"* ]]
}

@test "restore_main_mailbox: returns non-zero when all accounts fail" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_ZMMAILBOX_FAIL=1
  run restore_main_mailbox "$session" "" ""
  [ "$status" -ne 0 ]
  [[ "$output" == *"completed with errors"* ]]
}

@test "restore_main_mailbox: shows failure count in output when accounts fail" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_ZMMAILBOX_FAIL=1
  run restore_main_mailbox "$session" "" ""
  [[ "$output" == *"0/1 accounts restored"* ]]
  [[ "$output" == *"1 failed"* ]]
}

@test "restore_main_mailbox: restores when session exists in SQLITE3" {
  SESSION_TYPE="SQLITE3"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/user@example.com.tgz"
  sqlite3 "${WORKDIR}/sessions.sqlite3" < "${PROJECT_ROOT}/project/lib/sqlite3/database.sql"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_session values('${session}','2024-01-01T12:00:00.000',
     '2024-01-01T12:30:00.000','100M','Full Backup','FINISHED')"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_account(email,sessionID,account_size,initial_date,conclusion_date)
     values('user@example.com','${session}','50M','2024-01-01T12:00:00.000','2024-01-01T12:30:00.000')"
  MOCK_ZMMAILBOX_FAIL=0
  run restore_main_mailbox "$session" "" ""
  [ "$status" -eq 0 ]
  [[ "$output" == *"started"* ]]
}

@test "restore_main_mailbox: restores to different account (restoreOnAccount)" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/src@example.com.tgz"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:src@example.com:01/01/24
EOF
  MOCK_ZMMAILBOX_FAIL=0
  run restore_main_mailbox "$session" "src@example.com" "dst@example.com"
  [ "$status" -eq 0 ]
  [[ "$output" == *"started"* ]]
}

@test "restore_main_mailbox: reports error when zmmailbox fails during restoreOnAccount" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/src@example.com.tgz"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:src@example.com:01/01/24
EOF
  MOCK_ZMMAILBOX_FAIL=1
  run restore_main_mailbox "$session" "src@example.com" "dst@example.com"
  [[ "$output" == *"Error"* ]]
}

@test "restore_main_mailbox: prints skip message when output contains 'No such file or directory'" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  touch "${WORKDIR}/${session}/src@example.com.tgz"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:src@example.com:01/01/24
EOF
  export MOCK_ZMMAILBOX_NOFILE=1
  run restore_main_mailbox "$session" "src@example.com" "dst@example.com"
  [ "$status" -eq 0 ]
  [[ "$output" == *"skipping"* ]]
}

# ---------------------------------------------------------------------------
# restore_main_ldap
# ---------------------------------------------------------------------------

@test "restore_main_ldap: runs restore for found TXT session" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_LDAPDELETE_FAIL=0
  MOCK_LDAPADD_FAIL=0
  run restore_main_ldap "$session" ""
  [[ "$output" == *"started"* ]]
}

@test "restore_main_ldap: shows account count in completion message on success" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_LDAPDELETE_FAIL=0
  MOCK_LDAPADD_FAIL=0
  run restore_main_ldap "$session" ""
  [ "$status" -eq 0 ]
  [[ "$output" == *"1/1 accounts restored"* ]]
}

@test "restore_main_ldap: returns non-zero when all ldap accounts fail" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_LDAPADD_FAIL=1
  run restore_main_ldap "$session" ""
  [ "$status" -ne 0 ]
  [[ "$output" == *"completed with errors"* ]]
}

@test "restore_main_ldap: shows failure count in output when ldap accounts fail" {
  SESSION_TYPE="TXT"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  cat >> "${WORKDIR}/sessions.txt" << EOF
SESSION: ${session} started on Mon Jan 01
${session}:user@example.com:01/01/24
EOF
  MOCK_LDAPADD_FAIL=1
  run restore_main_ldap "$session" ""
  [[ "$output" == *"0/1 accounts restored"* ]]
  [[ "$output" == *"1 failed"* ]]
}

@test "restore_main_ldap: prints nothing-to-do when session not found in TXT" {
  SESSION_TYPE="TXT"
  run restore_main_ldap "nonexistent-session" ""
  [[ "$output" == *"Nothing to do"* ]]
}

@test "restore_main_ldap: prints nothing-to-do when session not found in SQLITE3" {
  SESSION_TYPE="SQLITE3"
  sqlite3 "${WORKDIR}/sessions.sqlite3" < "${PROJECT_ROOT}/project/lib/sqlite3/database.sql"
  run restore_main_ldap "nonexistent-session" ""
  [[ "$output" == *"Nothing to do"* ]]
}

@test "restore_main_ldap: runs restore for found SQLITE3 session" {
  SESSION_TYPE="SQLITE3"
  local session="full-20240101120000"
  mkdir -p "${WORKDIR}/${session}"
  printf "dn: uid=user@example.com,ou=people,dc=example,dc=com\nobjectClass: top\n" \
    > "${WORKDIR}/${session}/user@example.com.ldiff"
  sqlite3 "${WORKDIR}/sessions.sqlite3" < "${PROJECT_ROOT}/project/lib/sqlite3/database.sql"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_session values('${session}','2024-01-01T12:00:00.000',
     '2024-01-01T12:30:00.000','100M','Full Backup','FINISHED')"
  sqlite3 "${WORKDIR}/sessions.sqlite3" \
    "insert into backup_account(email,sessionID,account_size,initial_date,conclusion_date)
     values('user@example.com','${session}','50M','2024-01-01T12:00:00.000','2024-01-01T12:30:00.000')"
  MOCK_LDAPDELETE_FAIL=0
  MOCK_LDAPADD_FAIL=0
  run restore_main_ldap "$session" ""
  [[ "$output" == *"started"* ]]
}
