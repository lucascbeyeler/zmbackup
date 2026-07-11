#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# parse_session_name: Extract YEAR, MONTH, DAY from a session name.
# The session name format is {prefix}-YYYYMMDDHHMMSS regardless of prefix length.
# Sets YEAR, MONTH, DAY in the caller's scope; returns 1 if the name is invalid.
################################################################################
function parse_session_name() {
  local name="$1"
  [[ "$name" =~ -([0-9]{4})([0-9]{2})([0-9]{2}) ]] || return 1
  YEAR="${BASH_REMATCH[1]}"
  MONTH="${BASH_REMATCH[2]}"
  DAY="${BASH_REMATCH[3]}"
}

################################################################################
# validate_email: Return 0 if $1 matches a basic email address pattern.
################################################################################
function validate_email() {
  [[ "$1" =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]]
}

################################################################################
# validate_domain: Return 0 if $1 matches a valid domain name pattern.
################################################################################
function validate_domain() {
  [[ "$1" =~ ^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]]
}

################################################################################
# validate_session_id: Return 0 if $1 matches a zmbackup session ID.
# Format: {prefix}-{14-digit timestamp YYYYMMDDHHMMSS}.
################################################################################
function validate_session_id() {
  [[ "$1" =~ ^(full|inc|ldap|domain|distlist|alias|mbox|signature)-[0-9]{14}$ ]]
}

################################################################################
# validate_account_args: Validate a -a/--account email list or -d/--domain list.
# $1 - flag (-a, --account, -d, --domain, or any other value; no-op for others)
# $2 - comma-separated list to validate (only checked when $1 is -a/-d)
# Prints an error and returns 1 if any value fails validation.
################################################################################
function validate_account_args() {
  local flag="$1" values="$2" item
  if [[ "$flag" == "-a" || "$flag" == "--account" ]] && [[ -n "$values" ]]; then
    for item in $(printf '%s\n' "$values" | tr ',' '\n'); do
      if ! validate_email "$item"; then
        printf "Error! Invalid email address: %s\n" "$item"
        return 1
      fi
    done
  elif [[ "$flag" == "-d" || "$flag" == "--domain" ]] && [[ -n "$values" ]]; then
    for item in $(printf '%s\n' "$values" | tr ',' '\n'); do
      if ! validate_domain "$item"; then
        printf "Error! Invalid domain name: %s\n" "$item"
        return 1
      fi
    done
  fi
}

################################################################################
# zmlog: Write a log entry to both syslog and $LOGFILE.
# Options:
#    $1 - syslog priority (e.g. local7.info, local7.err, local7.warn)
#    $@ - message text; if omitted, reads from stdin
################################################################################
function zmlog(){
  local priority="$1"; shift
  local message
  if [ "$#" -gt 0 ]; then
    message="$*"
  else
    message="$(cat)"
  fi
  logger -i -p "$priority" "$message"
  echo "$(date '+%Y-%m-%d %T') [$priority] $message" >> "$LOGFILE"
}

################################################################################
# safe_sql_value: Escape a value for safe interpolation into a SQLite3 string
# by doubling single-quote characters, preventing SQL injection.
################################################################################
function safe_sql_value() {
  printf '%s' "$1" | sed "s/'/''/g"
}

################################################################################
# ldap_escape_filter: Escape a value for safe embedding in an LDAP filter
# string per RFC 4515. Replaces \, *, (, ) with their \XX hex equivalents.
################################################################################
function ldap_escape_filter() {
  printf '%s' "$1" | sed \
    -e 's/\\/\\5c/g' \
    -e 's/\*/\\2a/g' \
    -e 's/(/\\28/g' \
    -e 's/)/\\29/g'
}

################################################################################
# session_query: Dispatch a session query to TXT or SQLite3 backend.
# Options:
#    $1 - SQL statement(s) for the SQLite3 backend
#    $2 - Shell command string for the TXT backend (passed to eval)
################################################################################
function session_query() {
  local sql="$1" txt_fallback_cmd="$2"
  if [[ $SESSION_TYPE == "SQLITE3" ]]; then
    sqlite3 "$WORKDIR/sessions.sqlite3" "$sql"
  else
    eval "$txt_fallback_cmd"
  fi
}

################################################################################
# clear_temp: Clear all the temporary files.
################################################################################
function on_exit(){
  BASHERRCODE=$?
  if [[ -n $STYPE ]]; then
    if [[ $BASHERRCODE -ne 0 ]]; then
      notify_finish "$SESSION" "$STYPE" "FAILURE"
    elif [[ -n $SESSION ]]; then
      notify_finish "$SESSION" "$STYPE" "SUCCESS"
    fi
  fi
  # shellcheck disable=SC2086
  rm -rf "$TEMPSESSION" "$TEMPACCOUNT" "$TEMPINACCOUNT" "$TEMPDIR" $MESSAGE $FAILURE
  zmlog local7.info "Zmbackup: Excluding the temporary files before close."
}

#trap the function to be executed if the sript die
trap on_exit TERM INT EXIT

################################################################################
# create_temp: Create the temporary files used by the script.
################################################################################
function create_temp(){
  TEMPDIR=$(mktemp -d "$WORKDIR"/XXXX)
  TEMPACCOUNT=$(mktemp)
  TEMPINACCOUNT=$(mktemp)
  MESSAGE=$(mktemp)
  FAILURE=$(mktemp)
  TEMPSESSION=$(mktemp)
  export TEMPDIR TEMPACCOUNT TEMPINACCOUNT MESSAGE FAILURE TEMPSESSION
}

################################################################################
# load_config: Load the config file and zimbra's bashrc.
################################################################################
function load_config(){
  local conf="${ZMBACKUP_CONF:-/etc/zmbackup/zmbackup.conf}"
  local bashrc="${ZIMBRA_BASHRC:-/opt/zimbra/.bashrc}"
  local ldaprc="${ZIMBRA_LDAPRC:-/opt/zimbra/.ldaprc}"
  if [ -f "$conf" ]; then
    source "$conf" 2> /dev/null
    ZMBACKUP_BLOCKEDLIST="${ZMBACKUP_BLOCKEDLIST:-/etc/zmbackup/blockedlist.conf}"
    export ZMBACKUP_BLOCKEDLIST
  else
    zmlog local7.err "Zmbackup: zmbackup.conf not found."
    echo "ERROR - zmbackup.conf not found. Can't proceed without the file."
    exit 1
  fi
  if [ -f "$bashrc" ]; then
    source "$bashrc" 2> /dev/null
  else
    zmlog local7.err "Zmbackup: zimbra user's .bashrc not found."
    echo "ERROR - zimbra user's .bashrc not found. Can't proceed without the file."
    exit 1
  fi
  if [ -f "$ldaprc" ]; then
    export LDAPRC="$ldaprc"
  fi
}

################################################################################
# constants: Initialize all the constants used by the Zmbackup.
################################################################################
function constant(){
  # LDAP OBJECT
  if [ "$BACKUP_INACTIVE_ACCOUNTS" == "true" ]; then
    declare -gxr ACOBJECT="(objectclass=zimbraAccount)"
  else
    declare -gxr ACOBJECT="(&(objectclass=zimbraAccount)(zimbraAccountStatus=active))"
  fi

  # Enabling SSL for ZMBACKUP
   if [ "$SSL_ENABLE" == "true" ]; then
     declare -gxr WEBPROTO="https"
   else
     declare -gxr WEBPROTO="http"
   fi

  declare -gxr DLOBJECT="(objectclass=zimbraDistributionList)"
  declare -gxr ALOBJECT="(objectclass=zimbraAlias)"
  declare -gxr SIOBJECT="(objectclass=zimbraSignature)"
  declare -gxr DOMOBJECT="(objectclass=zimbraDomain)"

  # LDAP FILTER
  declare -gxr DLFILTER="mail"
  declare -gxr ACFILTER="zimbraMailDeliveryAddress"
  declare -gxr ALFILTER="uid"
  declare -gxr SIFILTER="zimbraSignatureName"
  declare -gxr DOMFILTER="zimbraDomainName"

  # PID FILE
  declare -gxr PID='/opt/zimbra/log/zmbackup.pid'
}

################################################################################
# sessionvars: Initialize all the constants used by the backup action.
# Options:
#    $1 - The type of session that will be executed
#    $2 - OPTIONAL: Enable Incremental Backup
################################################################################
function sessionvars(){
  INC='FALSE'
  ls "$WORKDIR"/full* > /dev/null 2>&1
  ERRORCODE=$?
  if [[ $ERRORCODE -ne 0 || $1 == '--full' || $1 == '-f' ]]; then
    STYPE="Full Account"
    SESSION="full-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '--incremental' || $1 == '-i' ]]; then
    STYPE="Incremental Account"
    SESSION="inc-"$(date  +%Y%m%d%H%M%S)
    INC='TRUE'
  elif [[ $1 == '--alias' || $1 == '-al' ]]; then
    STYPE="Alias"
    SESSION="alias-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '-dl' || $1 == '--distributionlist' ]]; then
    STYPE="Distribution List"
    SESSION="distlist-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '-m' || $1 == '--mail' ]]; then
    STYPE="Mailbox"
    SESSION="mbox-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '--ldap' || $1 == '-ldp' ]]; then
    STYPE="Account - Only LDAP"
    SESSION="ldap-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '--signature' || $1 == '-sig' ]]; then
    STYPE="Signature"
    SESSION="signature-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '-dom' || $1 == '--domain-backup' ]]; then
    STYPE="Domain"
    SESSION="domain-"$(date  +%Y%m%d%H%M%S)
  fi
  export SESSION STYPE INC
}

################################################################################
# validate_config: Validate if all the values are informed and set the default if not
################################################################################
function validate_config(){

  ERR="false"

  if [ -z "$BACKUPUSER" ]; then
  	BACKUPUSER="zimbra"
    zmlog local7.warn "Zmbackup: BACKUPUSER not informed - setting as user zimbra instead."
  fi

  if [ "$(whoami)" != "$BACKUPUSER" ]; then
    echo "You need to be $BACKUPUSER to run this software."
    zmlog local7.err "Zmbackup: You need to be $BACKUPUSER to run this software."
    exit 2
  fi

  if [ -z "$WORKDIR" ]; then
    WORKDIR="/opt/zimbra/backup"
    zmlog local7.warn "Zmbackup: WORKDIR not informed - setting as /opt/zimbra/backup/ instead."
  fi

  if [ -z "$ENABLE_EMAIL_NOTIFY" ]; then
    ENABLE_EMAIL_NOTIFY="all"
    zmlog local7.warn "Zmbackup: ENABLE_EMAIL_NOTIFY not informed - setting as 'all' instead."
  fi

  if [ -z "$EMAIL_SENDER" ]; then
    EMAIL_SENDER="root@"$(hostname -d)
    zmlog local7.warn "Zmbackup: EMAIL_SENDER not informed - setting as $EMAIL_SENDER instead."
  fi

  if [ -z "$EMAIL_NOTIFY" ]; then
    EMAIL_NOTIFY="root@localdomain.com"
    zmlog local7.warn "Zmbackup: EMAIL_NOTIFY not informed - setting as root@localdomain.com instead."
  fi

  if [ -z "$ZMMAILBOX" ]; then
    ZMMAILBOX=$(whereis zmmailbox | cut -d" " -f2)
    zmlog local7.warn "Zmbackup: ZMMAILBOX not defined informed - setting as $ZMMAILBOX instead"
  fi

  if [ -z "$MAX_PARALLEL_PROCESS" ]; then
    MAX_PARALLEL_PROCESS="1"
    zmlog local7.warn "Zmbackup: MAX_PARALLEL_PROCESS not informed - disabling."
  fi

  if [ -z "$LOCK_BACKUP" ]; then
    LOCK_BACKUP=true
    zmlog local7.warn "Zmbackup: LOCK_BACKUP not informed - enabling."
  fi

  if ! [ -d "$WORKDIR" ]; then
    echo "The directory $WORKDIR doesn't exist."
    zmlog local7.err "Zmbackup: The directory $WORKDIR does not found."
    ERR="true"
  fi

  if [ -z "$LDAPADMIN" ]; then
    echo "You need to define the variable LDAPADMIN."
    zmlog local7.err "Zmbackup: You need to define the variable LDAPADMIN."
    ERR="true"
  fi

  if [ -z "$LDAPPASS" ]; then
    echo "You need to define the variable LDAPPASS."
    zmlog local7.err "Zmbackup: You need to define the variable LDAPPASS."
    ERR="true"
  fi

  if [ -z "$ROTATE_TIME" ]; then
    echo "You need to define the variable ROTATE_TIME."
    zmlog local7.err "Zmbackup: You need to define the variable ROTATE_TIME."
    ERR="true"
  fi

  if [ -z "$SESSION_TYPE" ]; then
    echo "You need to define the variable SESSION_TYPE."
    zmlog local7.err "Zmbackup: You need to define the variable SESSION_TYPE."
    ERR="true"
  fi

  if [ -z "$BACKUP_INACTIVE_ACCOUNTS" ]; then
    echo "You need to define the variable BACKUP_INACTIVE_ACCOUNTS."
    zmlog local7.err "Zmbackup: You need to define the variable BACKUP_INACTIVE_ACCOUNTS."
    ERR="true"
  fi

  if [ -z "$SSL_ENABLE" ]; then
    SSL_ENABLE="true"
    echo "No value was found for SSL_ENABLE. Setting 'true' for the value."
    zmlog local7.warn "No value was found for SSL_ENABLE. Setting 'true' for the value."
  fi

  check_parallel_version

  if [ "$ERR" == "true" ]; then
    echo "Some errors are found inside the config file. Please fix then and try again later."
    zmlog local7.err "Zmbackup: Configuration validation failed — check the errors above."
    exit 3
  fi
}

################################################################################
# check_parallel_version: Warn if GNU Parallel is too old (version <= 20160222
# has a known "pidtable format" bug that causes backup failures).
################################################################################
function check_parallel_version(){
  local parallel_version
  parallel_version=$(parallel --version 2>/dev/null | head -1 | grep -oE '[0-9]{8}')
  if [[ -n "$parallel_version" ]] && [[ "$parallel_version" -le "20160222" ]]; then
    echo "WARNING: GNU Parallel version $parallel_version has a known bug (pidtable format)"
    echo "         that may cause backup failures. Please upgrade to a version newer than"
    echo "         20160222. On RHEL/CentOS 7 you can run:"
    echo "           wget -O /etc/yum.repos.d/tange.repo \\"
    echo "             http://download.opensuse.org/repositories/home:/tange/CentOS_7/home:tange.repo"
    echo "           yum install -y parallel"
    zmlog local7.warn "Zmbackup: GNU Parallel $parallel_version has a known pidtable bug — please upgrade."
  fi
}

################################################################################
# checkpid: Check if the PID file exist. If exist, exit with status 3 and do nothing
################################################################################
function checkpid(){
  if [[ -f "$PID" ]]; then
    PIDP=$(cat "$PID")
    PIDR=$(ps -efa | awk '{print $2}' | grep -c "^$PIDP$")
    if [ "$PIDR" -gt 0 ]; then
      echo "FATAL: could not write lock file '/opt/zimbra/log/zmbackup.pid': File already exist"
      echo "This file exist as a secure measurement to protect your system to run two zmbackup"
      echo "instances at the same time."
      exit 4
    else
      echo 'Found stale PID file. Proceeding'
      echo $$ > "$PID"
    fi
  else
    echo $$ > "$PID"
  fi
}

################################################################################
# export_function: Export all the functions used by ParallelAction
################################################################################
function export_function(){
  export -f parse_session_name
  export -f validate_email
  export -f validate_domain
  export -f validate_session_id
  export -f validate_account_args
  export -f zmlog
  export -f safe_sql_value
  export -f ldap_escape_filter
  export -f session_query
  export -f get_mailbox_host
  export -f get_mailbox_url
  export -f __backupMailbox
  export -f __backupFullInc
  export -f __backupLdap
  export -f __backupDomain
  export -f ldap_backup
  export -f ldap_restore
  export -f mailbox_backup
  export -f ldap_filter
  export -f mailbox_restore
  export -f domain_backup
  export -f domain_restore
}

################################################################################
# export_vars: Export all the variables used by ParallelAction
################################################################################
function export_vars(){
  export LDAPSERVER
  export LDAPADMIN
  export LDAPPASS
  export LDAPRC
  export WORKDIR
  export LOCK_BACKUP
  export SESSION_TYPE
  export MAILPORT
  export ZMMAILBOX
  export LOGFILE
}
