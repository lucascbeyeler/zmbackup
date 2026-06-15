#!/bin/bash
################################################################################
# Restore Session - LDAP/Mailbox/DistList/Alias
################################################################################

################################################################################
# restore_main_mailbox: Manage the restore action for one or all mailbox
# Options:
#    $1 - The session to be restored
#    $2 - The list of accounts to be restored.
#    $3 - The destination of the restored account
################################################################################
function restore_main_mailbox()
{
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    SESSION=$(grep -E ": $1 started" "$WORKDIR"/sessions.txt | grep 'started' | \
                  awk '{print $2}' | sort | uniq)
  elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
    SESSION=$(sqlite3 "$WORKDIR"/sessions.sqlite3 "select * from backup_session where sessionID='$1'")
  fi
  if [ -n "$SESSION" ]; then
    printf "Restore mail process with session %s started at %s" "$1" "$(date)"
    TOTAL_COUNT=0
    FAIL_COUNT=0
    SUCCESS_COUNT=0
    BASHERRCODE=0
    if [[ -n $3 && $2 == *"@"* ]]; then
      TOTAL_COUNT=1
      TEMP_CLI_OUTPUT=$(mktemp)
      if $ZMMAILBOX -t0 -z -m "$3" postRestURL '//?fmt=tgz&resolve=skip' "$WORKDIR"/"$1"/"$2".tgz > "$TEMP_CLI_OUTPUT" 2>&1; then
        BASHERRCODE=0
        SUCCESS_COUNT=1
        if grep -q "No such file or directory" "$TEMP_CLI_OUTPUT"; then
          printf "Account %s has nothing to restore - skipping..." "$2"
        fi
      else
        BASHERRCODE=$?
        FAIL_COUNT=1
        printf "Error during the restore process for account %s. Error message below:" "$2"
        printf "\n%s: " "$2"
        cat "$TEMP_CLI_OUTPUT"
      fi
      rm -rf "${TEMP_CLI_OUTPUT:?}"
    else
      MAIL_FAILDIR=$(mktemp -d)
      export MAIL_FAILDIR
      build_listRST "$1" "$2"
      TOTAL_COUNT=$(wc -l < "$TEMPACCOUNT")
      parallel --jobs "$MAX_PARALLEL_PROCESS" "mailbox_restore '$1' '{}'" < "$TEMPACCOUNT"
      BASHERRCODE=$?
      FAIL_COUNT=$(find "$MAIL_FAILDIR" -maxdepth 1 -type f 2>/dev/null | wc -l)
      [[ $FAIL_COUNT -gt 0 ]] && BASHERRCODE=1
      SUCCESS_COUNT=$((TOTAL_COUNT - FAIL_COUNT))
      rm -rf "$MAIL_FAILDIR"
      unset MAIL_FAILDIR
    fi
    if [[ $BASHERRCODE -eq 0 ]]; then
      printf "\nRestore mail process with session %s completed at %s (%d/%d accounts restored)\n" \
        "$1" "$(date)" "$SUCCESS_COUNT" "$TOTAL_COUNT"
    else
      printf "\nRestore mail process with session %s completed with errors at %s (%d/%d accounts restored, %d failed)\n" \
        "$1" "$(date)" "$SUCCESS_COUNT" "$TOTAL_COUNT" "$FAIL_COUNT"
    fi
    return $BASHERRCODE
  else
    echo "Nothing to do. Closing..."
    rm -rf "$PID"
    return 0
  fi
}

################################################################################
# restore_main_ldap: Manage the restore action for one or all ldap accounts
# Options:
#    $1 - The session to be restored
#    $2 - The list of accounts to be restored.
################################################################################
function restore_main_ldap()
{
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    SESSION=$(grep -E ": $1 started" "$WORKDIR"/sessions.txt | grep 'started' | \
                  awk '{print $2}' | sort | uniq)
  elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
    SESSION=$(sqlite3 "$WORKDIR"/sessions.sqlite3 "select * from backup_session where sessionID='$1'")
  fi
  if [ -n "$SESSION" ]; then
    echo "Restore LDAP process with session $1 started at $(date)"
    LDAP_FAILDIR=$(mktemp -d)
    export LDAP_FAILDIR
    build_listRST "$1" "$2"
    TOTAL_COUNT=$(wc -l < "$TEMPACCOUNT")
    parallel --jobs "$MAX_PARALLEL_PROCESS" "ldap_restore '$1' '{}'" < "$TEMPACCOUNT"
    BASHERRCODE=$?
    FAIL_COUNT=$(find "$LDAP_FAILDIR" -maxdepth 1 -type f 2>/dev/null | wc -l)
    [[ $FAIL_COUNT -gt 0 ]] && BASHERRCODE=1
    SUCCESS_COUNT=$((TOTAL_COUNT - FAIL_COUNT))
    rm -rf "$LDAP_FAILDIR"
    unset LDAP_FAILDIR
    if [[ $BASHERRCODE -eq 0 ]]; then
      echo "Restore LDAP process with session $1 completed at $(date) ($SUCCESS_COUNT/$TOTAL_COUNT accounts restored)"
    else
      echo "Restore LDAP process with session $1 completed with errors at $(date) ($SUCCESS_COUNT/$TOTAL_COUNT accounts restored, $FAIL_COUNT failed)"
    fi
    return $BASHERRCODE
  else
    echo "Nothing to do. Closing..."
    return 0
  fi
}
