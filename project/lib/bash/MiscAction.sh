#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# clear_temp: Clear all the temporary files.
################################################################################
function on_exit(){
  BASHERRCODE=$?
  if [[ -n $STYPE ]]; then
    if [[ $BASHERRCODE -eq 1 ]]; then
      notify_finish "$SESSION" "$STYPE" "FAILURE"
    elif [[ $BASHERRCODE -eq 0 && -n $SESSION ]]; then
      notify_finish "$SESSION" "$STYPE" "SUCCESS"
    fi
  fi
  # shellcheck disable=SC2086
  rm -rf "$TEMPSESSION" "$TEMPACCOUNT" "$TEMPINACCOUNT" "$TEMPDIR" $MESSAGE $TEMPSQL $FAILURE
  logger -i -p local7.info "Zmbackup: Excluding the temporary files before close."
}

#trap the function to be executed if the sript die
trap on_exit TERM INT EXIT

################################################################################
# create_temp: Create the temporary files used by the script.
################################################################################
function create_temp(){
  export readonly TEMPDIR
  export readonly TEMPACCOUNT
  export readonly TEMPINACCOUNT
  export readonly MESSAGE
  export readonly FAILURE
  export readonly TEMPSESSION
  export readonly TEMPSQL

  TEMPDIR=$(mktemp -d "$WORKDIR"/XXXX)
  TEMPACCOUNT=$(mktemp)
  TEMPINACCOUNT=$(mktemp)
  MESSAGE=$(mktemp)
  FAILURE=$(mktemp)
  TEMPSESSION=$(mktemp)
  TEMPSQL=$(mktemp)
}

################################################################################
# load_config: Load the config file and zimbra's bashrc.
################################################################################
function load_config(){
  if [ -f "/etc/zmbackup/zmbackup.conf" ]; then
    source /etc/zmbackup/zmbackup.conf 2> /dev/null
  else
    logger -i -p local7.err "Zmbackup: zmbackup.conf not found."
    echo "ERROR - zmbackup.conf not found. Can't proceed whitout the file."
    exit 1
  fi
  if [ -f "/opt/zimbra/.bashrc" ]; then
    source /opt/zimbra/.bashrc 2> /dev/null
  else
    logger -i -p local7.err "Zmbackup: zimbra user's .bashrc not found."
    echo "ERROR - zimbra user's .bashrc not found. Can't proceed whitout the file."
    exit 1
  fi
}

################################################################################
# constants: Initialize all the constants used by the Zmbackup.
################################################################################
function constant(){
  # LDAP OBJECT
  if [ "$BACKUP_INACTIVE_ACCOUNTS" == "true" ]; then
    export readonly ACOBJECT="(objectclass=zimbraAccount)"
  else
    export readonly ACOBJECT="(&(objectclass=zimbraAccount)(zimbraAccountStatus=active))"
  fi

  # Enabling SSL for ZMBACKUP
   if [ "$SSL_ENABLE" == "true" ]; then
     export readonly WEBPROTO="https"
   else
     export readonly WEBPROTO="http"
   fi

  export readonly DLOBJECT="(objectclass=zimbraDistributionList)"
  export readonly ALOBJECT="(objectclass=zimbraAlias)"
  export readonly SIOBJECT="(objectclass=zimbraSignature)"

  # LDAP FILTER
  export readonly DLFILTER="mail"
  export readonly ACFILTER="zimbraMailDeliveryAddress"
  export readonly ALFILTER="uid"
  export readonly SIFILTER="zimbraSignatureName"

  # PID FILE
  export readonly PID='/opt/zimbra/log/zmbackup.pid'
}

################################################################################
# sessionvars: Initialize all the constants used by the backup action.
# Options:
#    $1 - The type of session that will be executed
#    $2 - OPTIONAL: Enable Incremental Backup
################################################################################
function sessionvars(){
  export readonly SESSION
  export readonly STYPE
  export readonly INC
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
  fi
}

################################################################################
# validate_config: Validate if all the values are informed and set the default if not
################################################################################
function validate_config(){

  ERR="false"

  if [ -z "$BACKUPUSER" ]; then
  	BACKUPUSER="zimbra"
    logger -i -p local7.warn "Zmbackup: BACKUPUSER not informed - setting as user zimbra instead."
  fi

  if [ "$(whoami)" != "$BACKUPUSER" ]; then
    echo "You need to be $BACKUPUSER to run this software."
    logger -i -p local7.err "Zmbackup: You need to be $BACKUPUSER to run this software."
    exit 2
  fi

  if [ -z "$WORKDIR" ]; then
    WORKDIR="/opt/zimbra/backup"
    logger -i -p local7.warn "Zmbackup: WORKDIR not informed - setting as /opt/zimbra/backup/ instead."
  fi

  if [ -z "$ENABLE_EMAIL_NOTIFY" ]; then
    ENABLE_EMAIL_NOTIFY="all"
    logger -i -p local7.warn "Zmbackup: ENABLE_EMAIL_NOTIFY not informed - setting as 'all' instead."
  fi

  if [ -z "$EMAIL_SENDER" ]; then
    EMAIL_SENDER="root@"$(hostname -d)
    logger -i -p local7.warn "Zmbackup: EMAIL_SENDER not informed - setting as $EMAIL_SENDER instead."
  fi

  if [ -z "$EMAIL_NOTIFY" ]; then
    EMAIL_NOTIFY="root@localdomain.com"
    logger -i -p local7.warn "Zmbackup: EMAIL_NOTIFY not informed - setting as root@localdomain.com instead."
  fi

  if [ -z "$ZMMAILBOX" ]; then
    ZMMAILBOX=$(whereis zmmailbox | cut -d" " -f2)
    logger -i -p local7.warn "Zmbackup: ZMMAILBOX not defined informed - setting as $ZMMAILBOX instead"
  fi

  if [ -z "$MAX_PARALLEL_PROCESS" ]; then
    MAX_PARALLEL_PROCESS="1"
    logger -i -p local7.warn "Zmbackup: MAX_PARALLEL_PROCESS not informed - disabling."
  fi

  if [ -z "$LOCK_BACKUP" ]; then
    LOCK_BACKUP=true
    logger -i -p local7.warn "Zmbackup: LOCK_BACKUP not informed - enabling."
  fi

  if ! [ -d "$WORKDIR" ]; then
    echo "The directory $WORKDIR doesn't exist."
    logger -i -p local7.err "Zmbackup: The directory $WORKDIR does not found."
    ERR="true"
  fi

  if [ -z "$LDAPADMIN" ]; then
    echo "You need to define the variable LDAPADMIN."
    logger -i -p local7.err "Zmbackup: You need to define the variable LDAPADMIN."
    ERR="true"
  fi

  if [ -z "$LDAPPASS" ]; then
    echo "You need to define the variable LDAPPASS."
    logger -i -p local7.err "Zmbackup: You need to define the variable LDAPPASS."
    ERR="true"
  fi

  if [ -z "$ROTATE_TIME" ]; then
    echo "You need to define the variable ROTATE_TIME."
    logger -i -p local7.err "Zmbackup: You need to define the variable ROTATE_TIME."
    ERR="true"
  fi

  if [ -z "$SESSION_TYPE" ]; then
    echo "You need to define the variable SESSION_TYPE."
    logger -i -p local7.err "Zmbackup: You need to define the variable SESSION_TYPE."
    ERR="true"
  fi

  if [ -z "$BACKUP_INACTIVE_ACCOUNTS" ]; then
    echo "You need to define the variable BACKUP_INACTIVE_ACCOUNTS."
    logger -i -p local7.err "Zmbackup: You need to define the variable BACKUP_INACTIVE_ACCOUNTS."
    ERR="true"
  fi

  if [ -z "$SSL_ENABLE" ]; then
    echo "No value was found for SSL_ENABLE. Setting 'true' for the value."
    logger -i -p local7.warn "No value was found for SSL_ENABLE. Setting 'true' for the value."
  fi

  if [ "$ERR" == "true" ]; then
    echo "Some errors are found inside the config file. Please fix then and try again later."
    logger -i -p local7.err "Zmbackup: You need to define the variable BACKUP_INACTIVE_ACCOUNTS."
    exit 3
  fi
}

################################################################################
# checkpid: Check if the PID file exist. If exist, exit with status 3 and do nothing
################################################################################
function checkpid(){
  if [[ -f "$PID" ]]; then
    PIDP=$(cat $PID)
    PIDR=$(ps -efa | awk '{print $2}' | grep -c "^$PIDP$")
    if [ "$PIDR" -gt 0 ]; then
      echo "FATAL: could not write lock file '/opt/zimbra/log/zmbackup.pid': File already exist"
      echo "This file exist as a secure measurement to protect your system to run two zmbackup"
      echo "instances at the same time."
      exit 4
    else
      echo 'Found stale PID file. Proceeding'
      echo $$ > $PID
    fi
  else
    echo $$ > $PID
  fi
}

################################################################################
# export_function: Export all the functions used by ParallelAction
################################################################################
function export_function(){
  export -f __backupMailbox
  export -f __backupFullInc
  export -f __backupLdap
  export -f ldap_backup
  export -f ldap_restore
  export -f mailbox_backup
  export -f ldap_filter
  export -f mailbox_restore
}

################################################################################
# export_vars: Export all the variables used by ParallelAction
################################################################################
function export_vars(){
  export LDAPSERVER
  export LDAPADMIN
  export LDAPPASS
  export WORKDIR
  export LOCK_BACKUP
  export SESSION_TYPE
  export MAILPORT
  export ZMMAILBOX
}
