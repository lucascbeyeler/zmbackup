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
    if [[ -n $3 && $2 == *"@"* ]]; then
      TEMP_CLI_OUTPUT=$(mktemp)
      $ZMMAILBOX -z -m "$3" postRestURL '//?fmt=tgz&resolve=skip' "$WORKDIR"/"$1"/"$2".tgz > "$TEMP_CLI_OUTPUT" 2>&1
      BASHERRCODE=$?
      if ! [[ $BASHERRCODE -eq 0 ]]; then
        printf "Error during the restore process for account %s. Error message below:" "$2"
        printf "\n%s: " "$2"
        cat "$TEMP_CLI_OUTPUT"
      elif [[ "$ERR"  == *"No such file or directory" ]]; then
        printf "Account %s has nothing to restore - skipping..." "$2"
      fi
      rm -rf "${TEMP_CLI_OUTPUT:?}"
    else
      build_listRST "$1" "$2"
      parallel --jobs "$MAX_PARALLEL_PROCESS" "mailbox_restore '$1' '{}'" < "$TEMPACCOUNT"
    fi
    printf "\nRestore mail process with session %s completed at %s\n" "$1" "$(date)"
  else
    echo "Nothing to do. Closing..."
    rm -rf "$PID"
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
  if ! [ -s "$SESSION" ]; then
    echo "Restore LDAP process with session $1 started at $(date)"
    build_listRST "$1" "$2"
    parallel --jobs "$MAX_PARALLEL_PROCESS" "ldap_restore '$1' '{}'" < "$TEMPACCOUNT"
    echo "Restore LDAP process with session $1 completed at $(date)"
  else
    echo "Nothing to do. Closing..."
  fi
}
