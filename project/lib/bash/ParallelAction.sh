#!/bin/bash
################################################################################
# Repeatable Actions
################################################################################

###############################################################################
# ldap_backup: Backup a LDAP object inside a file.
# Options:
# $1 - The object's mail account that should be backed up;
# $2 - The type of object should be backed up. Valid values:
#     DLOBJECT - Distribution List;
#     ACOBJECT - User Account;
#     ALOBJECT - Alias;
#     SIOBJECT - Signature.
###############################################################################
function ldap_backup()
{
  TEMP_CLI_OUTPUT=$(mktemp)
  ldapsearch -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" -w "$LDAPPASS" -b '' \
             -LLL "(&(|(mail=$1)(uid=$1))$2)" > "$TEMPDIR"/"$1".ldiff 2> "$TEMP_CLI_OUTPUT"
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    logger -i -p local7.info "Zmbackup: LDAP - Backup for account $1 finished."
    export ERRCODE=0
  else
    logger -i -p local7.err "Zmbackup: LDAP - Backup for account $1 failed. Error message below:"
    echo "Zmbackup: $1 " | logger -i -p local7.err
    logger -i -p local7.err  < "$TEMP_CLI_OUTPUT"
    export ERRCODE=1
  fi
  rm -rf "${TEMP_CLI_OUTPUT:?}"
}


###############################################################################
# mailbox_backup: Backup user's mailbox in TGZ format.
# Options:
# $1 - The user's account to be backed up;
###############################################################################
function mailbox_backup()
{
  TEMP_CLI_OUTPUT=$(mktemp)
  if [[ "$INC" == "TRUE" ]]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      DATE=$(grep "$1" "$WORKDIR"/sessions.txt | tail -1 | awk -F: '{print $3}' | cut -d'-' -f2)
    elif [[ $SESSION_TYPE == 'SQLITE3' ]]; then
      DATE=$(sqlite3 "$WORKDIR"/sessions.sqlite3 "select MAX(initial_date) \
             from backup_account where email='$1' and \
             (sessionID like 'full%' or sessionID like 'inc%' or sessionID like 'mbox%')")
    fi
    AFTER='&'"query=after:\"$(date -d "$DATE" --date='-1 day' +%m/%d/%Y)\""
  fi
  $ZMMAILBOX -t0 -z -m "$1" getRestURL --output "$TEMPDIR"/"$1".tgz "/?fmt=tgz&resolve=skip$AFTER" > "$TEMP_CLI_OUTPUT" 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    if [[ -s $TEMPDIR/$1.tgz ]]; then
      logger -i -p local7.info "Zmbackup: Mailbox - Backup for account $1 finished."
    else
      logger -i -p local7.info "Zmbackup: Mailbox - Backup for account $1 finished, but the file is empty. Removing..."
      rm -rf "$TEMPDIR"/"$1".tgz
    fi
    export ERRCODE=0
  else
    logger -i -p local7.err "Zmbackup: Mailbox - Backup for account $1 failed. Error message below:"
    echo "Zmbackup: $1 " | logger -i -p local7.err
    logger -i -p local7.err < "$TEMP_CLI_OUTPUT"
    export ERRCODE=1
  fi
  rm -rf "${TEMP_CLI_OUTPUT:?}"
}


###############################################################################
# ldap_restore: Restore a LDAP object inside a file.
# Options:
# $1 - The session file to be restored;
# $2 - The account that should be restored.
###############################################################################
function ldap_restore()
{
  ldapdelete -Z -r -x -H "$LDAPSERVER" -D "$LDAPADMIN" -c -w "$LDAPPASS" \
    "$(grep ^dn: "$WORKDIR"/"$1"/"$2".ldiff | awk '{print $2}')" > /dev/null 2>&1
  ERR=$( (ldapadd -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" \
           -c -w "$LDAPPASS" -f "$WORKDIR"/"$1"/"$2".ldiff) 2>&1)
  BASHERRCODE=$?
  if ! [[ $BASHERRCODE -eq 0 ]]; then
    printf "\nError during the restore process for account %s. Error message below:" "$2"
    printf "\n%s: %s" "$2" "$ERR"
  fi
}

###############################################################################
# ldap_restore: Restore a LDAP object inside a file.
# Options:
# $1 - The session file to be restored;
# $2 - The account that should be restored.
###############################################################################
function mailbox_restore()
{
  TEMP_CLI_OUTPUT=$(mktemp)
  $ZMMAILBOX -t0 -z -m "$2" postRestURL '//?fmt=tgz&resolve=skip' "$WORKDIR"/"$1"/"$2".tgz > "$TEMP_CLI_OUTPUT" 2>&1
  BASHERRCODE=$?
  if ! [[ $BASHERRCODE -eq 0 ]]; then
    printf "Error during the restore process for account %s. Error message below:" "$2"
    printf "\n%s: " "$2"
    cat "$TEMP_CLI_OUTPUT"
  elif [[ "$ERR"  == *"No such file or directory" ]]; then
    printf "Account %s has nothing to restore - skipping..." "$2"
  fi
  rm -rf "${TEMP_CLI_OUTPUT:?}"
}


###############################################################################
# ldap_filter: Filter the account to see if you should do backup or not for that
#              account.
# Options:
# $1 - The email account to be validated.
###############################################################################
function ldap_filter()
{
  EXIST=
  if [[ "$LOCK_BACKUP" == "true" ]]; then
    if [[ "$SESSION_TYPE" == "TXT" ]]; then
      EXIST=$(grep "$1:$(date +%m/%d/%y)" "$WORKDIR"/sessions.txt 2> /dev/null | tail -1)
    else
      TODAY=$(date +%Y-%m-%dT%H:%M:%S.%N)
      YESTERDAY=$(date +%Y-%m-%dT%H:%M:%S.%N -d "yesterday")
      EXIST=$(sqlite3 "$WORKDIR"/sessions.sqlite3 "select email from backup_account where conclusion_date < '$TODAY' and conclusion_date > '$YESTERDAY' and email='$1'")
    fi
  fi
  grep -Fxq "$1" /etc/zmbackup/blockedlist.conf
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "WARN: $1 found inside blocked list - Nothing to do."
  elif [[ $EXIST ]]; then
    echo "WARN: $1 already has backup today. Nothing to do."
  else
    echo "$1" >> "$TEMPACCOUNT"
  fi
}
