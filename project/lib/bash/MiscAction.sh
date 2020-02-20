#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# clear_temp: Clear all the temporary files.
################################################################################
function on_exit(){
  ERRCODE=$?
  if [[ ! -z $STYPE ]]; then
    if [[ $ERRCODE -eq 1 ]]; then
      notify_finish $SESSION $STYPE "FAILURE"
    elif [[ $ERRCODE -eq 0 && ! -z $SESSION ]]; then
      notify_finish $SESSION "$STYPE" "SUCCESS"
    fi
  fi
  rm -rf $TEMPSESSION $TEMPACCOUNT $TEMPINCACCOUNT $TEMPDIR $MESSAGE $TEMPSQL $FAILURE
  logger -i -p local7.info "Zmbackup: Excluding the temporary files before close."
}

#trap the function to be executed if the sript die
trap on_exit TERM INT EXIT

################################################################################
# create_temp: Create the temporary files used by the script.
################################################################################
function create_temp(){
  export readonly TEMPDIR=$(mktemp -d $WORKDIR/XXXX)
  export readonly TEMPACCOUNT=$(mktemp)
  export readonly TEMPINACCOUNT=$(mktemp)
  export readonly MESSAGE=$(mktemp)
  export readonly FAILURE=$(mktemp)
  export readonly TEMPSESSION=$(mktemp)
  export readonly TEMPSQL=$(mktemp)
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
  ls $WORKDIR/full* > /dev/null 2>&1
  if [[ $? -ne 0 || $1 == '--full' || $1 == '-f' ]]; then
    export readonly STYPE="Full Account"
    export readonly SESSION="full-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='FALSE'
  elif [[ $1 == '--incremental' || $1 == '-i' ]]; then
    export readonly STYPE="Incremental Account"
    export readonly SESSION="inc-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='TRUE'
  elif [[ $1 == '--alias' || $1 == '-al' ]]; then
    export readonly STYPE="Alias"
    export readonly SESSION="alias-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='FALSE'
  elif [[ $1 == '-dl' || $1 == '--distributionlist' ]]; then
    export readonly STYPE="Distribution List"
    export readonly SESSION="distlist-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='FALSE'
  elif [[ $1 == '-m' || $1 == '--mail' ]]; then
    export readonly STYPE="Mailbox"
    export readonly SESSION="mbox-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='FALSE'
  elif [[ $1 == '--ldap' || $1 == '-ldp' ]]; then
    export readonly STYPE="Account - Only LDAP"
    export readonly SESSION="ldap-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='FALSE'
  elif [[ $1 == '--signature' || $1 == '-sig' ]]; then
    export readonly STYPE="Signature"
    export readonly SESSION="signature-"$(date  +%Y%m%d%H%M%S)
    export readonly INC='FALSE'
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

  if [ $(whoami) != "$BACKUPUSER" ]; then
    echo "You need to be $BACKUPUSER to run this software."
    logger -i -p local7.err "Zmbackup: You need to be $BACKUPUSER to run this software."
    exit 2
  fi

  if [ -z "$WORKDIR" ]; then
    WORKDIR="/opt/zimbra/backup"
    logger -i -p local7.warn "Zmbackup: WORKDIR not informed - setting as /opt/zimbra/backup/ instead."
  fi

  if [ -z "$MAILHOST" ]; then
    MAILHOST="127.0.0.1"
    logger -i -p local7.warn "Zmbackup: MAILHOST not informed - setting as 127.0.0.1 instead."
  fi

  TMP=$((wget --timeout=5 --tries=2 --no-check-certificate -O /dev/null https://$MAILHOST:7071)2>&1)
  if [ $? -ne 0 ]; then
    echo "Mailbox Admin Service is Down or Unavailable - See the logs for more information."
    logger -i -p local7.err "Zmbackup: Mailbox Admin Service is Down or Unavailable."
    logger -i -p local7.err "Zmbackup: $TMP"
    ERR="true"
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

  if [ -z "$ADMINUSER" ]; then
    echo "You need to define the variable ADMINUSER."
    logger -i -p local7.err "Zmbackup: You need to define the variable ADMINUSER."
    ERR="true"
  fi

  if [ -z "$ADMINPASS" ]; then
    echo "You need to define the variable ADMINPASS."
    logger -i -p local7.err "Zmbackup: You need to define the variable ADMINPASS."
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
    PIDP=`cat $PID`
    PIDR=`ps -efa | awk '{print $2}' | grep -c "^$PIDP$"`
    if [ $PIDR -gt 0 ]; then
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
  export ADMINUSER
  export ADMINPASS
  export LDAPSERVER
  export LDAPADMIN
  export LDAPPASS
  export MAILHOST
  export WORKDIR
  export LOCK_BACKUP
  export SESSION_TYPE
}
