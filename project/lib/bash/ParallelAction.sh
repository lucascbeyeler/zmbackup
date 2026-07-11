#!/bin/bash
################################################################################
# Repeatable Actions
################################################################################

###############################################################################
# get_mailbox_host: Query LDAP to get the mailbox server hostname for an account.
# Options:
# $1 - The email account to query.
# Returns: zimbraMailHost value or empty string if none.
###############################################################################
function get_mailbox_host()
{
  local SAFE_ACCOUNT HOST
  SAFE_ACCOUNT=$(ldap_escape_filter "$1")
  HOST=$(ldapsearch -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" -w "$LDAPPASS" -b '' \
    -LLL "(&(|(mail=${SAFE_ACCOUNT})(uid=${SAFE_ACCOUNT})))" zimbraMailHost 2>/dev/null | \
    grep '^zimbraMailHost:' | awk '{print $2}' | head -1)
  printf '%s' "$HOST"
}

###############################################################################
# get_mailbox_url: Build the mailbox server URL for a mailbox account.
# Uses WEBPROTO and zimbraMailHost from LDAP when available.
# Options:
# $1 - The email account to query.
# Returns: e.g. https://mail.example.com or empty if unavailable.
###############################################################################
function get_mailbox_url()
{
  local HOST
  HOST=$(get_mailbox_host "$1")
  if [[ -n "$HOST" ]]; then
    printf '%s://%s' "$WEBPROTO" "$HOST"
  fi
}

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
  local SAFE_ACCOUNT
  SAFE_ACCOUNT=$(ldap_escape_filter "$1")
  if ldapsearch -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" -w "$LDAPPASS" -b '' \
             -LLL "(&(|(mail=${SAFE_ACCOUNT})(uid=${SAFE_ACCOUNT}))$2)" > "$TEMPDIR"/"$1".ldiff 2> "$TEMP_CLI_OUTPUT"; then
    zmlog local7.info "Zmbackup: LDAP - Backup for account $1 finished."
    export ERRCODE=0
  else
    zmlog local7.err "Zmbackup: LDAP - Backup for account $1 failed. Error message below:"
    echo "Zmbackup: $1 " | zmlog local7.err
    zmlog local7.err  < "$TEMP_CLI_OUTPUT"
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
    local SAFE_EMAIL
    SAFE_EMAIL=$(safe_sql_value "$1")
    DATE=$(session_query \
      "select MAX(initial_date) from backup_account where email='${SAFE_EMAIL}' and (sessionID like 'full%' or sessionID like 'inc%' or sessionID like 'mbox%')" \
      "grep \"$1\" \"$WORKDIR\"/sessions.txt | tail -1 | awk -F: '{print \$3}' | cut -d- -f2")
    if [[ -n "$DATE" ]]; then
      YESTERDAY=$(date -d "$DATE" --date='-48 hours' +%m/%d/%Y)
      AFTER='&'"query=after:\"$YESTERDAY\""
    else
      AFTER=''
    fi
  fi
  local MAILBOX_URL
  MAILBOX_URL=$(get_mailbox_url "$1")
  if [[ -n "$MAILBOX_URL" ]]; then
    if $ZMMAILBOX -t0 -z -m "$1" getRestURL -u "$MAILBOX_URL" --output "$TEMPDIR"/"$1".tgz "/?fmt=tgz&resolve=skip$AFTER" > "$TEMP_CLI_OUTPUT" 2>&1; then
      local RESULT_OK=0
    else
      local RESULT_OK=1
    fi
  else
    if $ZMMAILBOX -t0 -z -m "$1" getRestURL --output "$TEMPDIR"/"$1".tgz "/?fmt=tgz&resolve=skip$AFTER" > "$TEMP_CLI_OUTPUT" 2>&1; then
      local RESULT_OK=0
    else
      local RESULT_OK=1
    fi
  fi
  if [[ $RESULT_OK -eq 0 ]]; then
    if [[ -s $TEMPDIR/$1.tgz ]]; then
      zmlog local7.info "Zmbackup: Mailbox - Backup for account $1 finished."
      export ERRCODE=0
    else
      zmlog local7.err "Zmbackup: Mailbox - Backup for account $1 finished, but the file is empty. Removing..."
      echo "Zmbackup: $1 " | zmlog local7.err
      zmlog local7.err < "$TEMP_CLI_OUTPUT"
      rm -rf "$TEMPDIR"/"$1".tgz
      export ERRCODE=1
    fi
  else
    if grep -q "status=204" "$TEMP_CLI_OUTPUT"; then
      zmlog local7.info "Zmbackup: Mailbox - No new content for account $1 since last backup."
      export ERRCODE=0
    else
      zmlog local7.err "Zmbackup: Mailbox - Backup for account $1 failed. Error message below:"
      echo "Zmbackup: $1 " | zmlog local7.err
      zmlog local7.err < "$TEMP_CLI_OUTPUT"
      export ERRCODE=1
    fi
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
  local LDAP_DN
  LDAP_DN=$(grep -m 1 "^dn:" "$WORKDIR"/"$1"/"$2".ldiff | awk '{print $2}')
  if [[ -z "$LDAP_DN" ]]; then
    printf "\nError: Could not extract DN from %s/%s/%s.ldiff - skipping LDAP restore for account %s" \
      "$WORKDIR" "$1" "$2" "$2"
    [[ -n "${LDAP_FAILFILE:-}" ]] && echo "$2" >> "$LDAP_FAILFILE"
    return 1
  fi
  ldapdelete -Z -r -x -H "$LDAPSERVER" -D "$LDAPADMIN" -c -w "$LDAPPASS" \
    "$LDAP_DN" > /dev/null 2>&1
  ERR=$( (ldapadd -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" \
           -c -w "$LDAPPASS" -f "$WORKDIR"/"$1"/"$2".ldiff) 2>&1)
  BASHERRCODE=$?
  if ! [[ $BASHERRCODE -eq 0 ]]; then
    printf "\nError during the restore process for account %s. Error message below:" "$2"
    printf "\n%s: %s" "$2" "$ERR"
    [[ -n "${LDAP_FAILFILE:-}" ]] && echo "$2" >> "$LDAP_FAILFILE"
  fi
  return $BASHERRCODE
}

###############################################################################
# mailbox_restore: Restore a mailbox from a TGZ backup file.
# Options:
# $1 - The session name to be restored;
# $2 - The account that should be restored.
###############################################################################
function mailbox_restore()
{
  TEMP_CLI_OUTPUT=$(mktemp)
  local MAILBOX_URL
  MAILBOX_URL=$(get_mailbox_url "$2")
  if [[ -n "$MAILBOX_URL" ]]; then
    if $ZMMAILBOX -t0 -z -m "$2" postRestURL -u "$MAILBOX_URL" '//?fmt=tgz&resolve=skip' "$WORKDIR"/"$1"/"$2".tgz > "$TEMP_CLI_OUTPUT" 2>&1; then
      BASHERRCODE=0
    else
      BASHERRCODE=$?
    fi
  else
    if $ZMMAILBOX -t0 -z -m "$2" postRestURL '//?fmt=tgz&resolve=skip' "$WORKDIR"/"$1"/"$2".tgz > "$TEMP_CLI_OUTPUT" 2>&1; then
      BASHERRCODE=0
    else
      BASHERRCODE=$?
    fi
  fi
  if [[ $BASHERRCODE -eq 0 ]]; then
    if grep -q "No such file or directory" "$TEMP_CLI_OUTPUT"; then
      printf "Account %s has nothing to restore - skipping..." "$2"
    fi
  else
    printf "Error during the restore process for account %s. Error message below:" "$2"
    printf "\n%s: " "$2"
    cat "$TEMP_CLI_OUTPUT"
    [[ -n "${MAIL_FAILFILE:-}" ]] && echo "$2" >> "$MAIL_FAILFILE"
  fi
  rm -rf "${TEMP_CLI_OUTPUT:?}"
  return $BASHERRCODE
}


###############################################################################
# domain_backup: Backup a Zimbra domain LDAP entry.
# Options:
# $1 - The domain name (e.g., example.com);
# $2 - The LDAP object filter for domains (DOMOBJECT).
###############################################################################
function domain_backup()
{
  DC=",dc="
  DOMAIN_DN="dc=${1//./$DC}"
  TEMP_CLI_OUTPUT=$(mktemp)
  if ldapsearch -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" -w "$LDAPPASS" \
             -b "$DOMAIN_DN" -s base -LLL "$2" > "$TEMPDIR"/"$1".ldiff 2> "$TEMP_CLI_OUTPUT"; then
    zmlog local7.info "Zmbackup: LDAP - Domain backup for $1 finished."
    export ERRCODE=0
  else
    zmlog local7.err "Zmbackup: LDAP - Domain backup for $1 failed. Error message below:"
    zmlog local7.err < "$TEMP_CLI_OUTPUT"
    export ERRCODE=1
  fi
  rm -rf "${TEMP_CLI_OUTPUT:?}"
}


###############################################################################
# domain_restore: Restore a Zimbra domain LDAP entry.
# Options:
# $1 - The session name to be restored;
# $2 - The domain name (e.g., example.com).
###############################################################################
function domain_restore()
{
  local LDAP_DN
  LDAP_DN=$(grep -m 1 "^dn:" "$WORKDIR"/"$1"/"$2".ldiff | awk '{print $2}')
  if [[ -z "$LDAP_DN" ]]; then
    printf "\nError: Could not extract DN from %s/%s/%s.ldiff - skipping domain restore for %s" \
      "$WORKDIR" "$1" "$2" "$2"
    return 1
  fi
  ERR=$( (ldapadd -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" \
           -c -w "$LDAPPASS" -f "$WORKDIR"/"$1"/"$2".ldiff) 2>&1)
  BASHERRCODE=$?
  if ! [[ $BASHERRCODE -eq 0 ]]; then
    if echo "$ERR" | grep -q "Already exists"; then
      zmlog local7.info "Zmbackup: Domain $2 already exists - skipping."
      return 0
    fi
    printf "\nError during the restore process for domain %s. Error message below:" "$2"
    printf "\n%s: %s" "$2" "$ERR"
  fi
  return $BASHERRCODE
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
    TODAY=$(date +%Y-%m-%dT%H:%M:%S.%N)
    YESTERDAY=$(date +%Y-%m-%dT%H:%M:%S.%N -d "yesterday")
    local SAFE_EMAIL
    SAFE_EMAIL=$(safe_sql_value "$1")
    EXIST=$(session_query \
      "select email from backup_account where conclusion_date < '$TODAY' and conclusion_date > '$YESTERDAY' and email='${SAFE_EMAIL}'" \
      "grep \"$1:$(date +%m/%d/%y)\" \"$WORKDIR\"/sessions.txt 2>/dev/null | tail -1")
  fi
  local blockedlist="${ZMBACKUP_BLOCKEDLIST:-/etc/zmbackup/blockedlist.conf}"
  if grep -Fxq "$1" "$blockedlist"; then
    echo "WARN: $1 found inside blocked list - Nothing to do."
  elif [[ $EXIST ]]; then
    echo "WARN: $1 already has backup today. Nothing to do."
  else
    echo "$1" >> "$TEMPACCOUNT"
  fi
}
