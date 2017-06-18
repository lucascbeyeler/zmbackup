#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# clear_temp: Clear all the temporary files.
################################################################################
function on_exit(){
  if [ $? == 1 ]; then
    notify_finish $SESSION $STYPE "FAILURE"
  elif [[ $? == 0 && ! -z $SESSION ]]; then
    notify_finish $SESSION $STYPE "SUCCESS"
  fi
  rm -rf $TEMPSESSION $TEMPACCOUNT $TEMPINCACCOUNT $TEMPDIR $PID $MESSAGE
  logger -i -p local7.info "Zmbackup: Excluding the temporary files before close."
}

#trap the function to be executed if the sript DIE
trap on_exit TERM INT EXIT

################################################################################
# create_temp: Create the temporary files used by the script.
################################################################################
function create_temp(){
  export readonly TEMPDIR=$(mktemp -d $WORKDIR/XXXX)
  export readonly TEMPACCOUNT=$(mktemp)
  export readonly TEMPINCACCOUNT=$(mktemp)
  export readonly MESSAGE=$(mktemp)
  export readonly FAILURE=$(mktemp)
  export readonly TEMPSESSION=$(mktemp)
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
# Options:
#    $1 - The type of session that will be executed
#    $2 - OPTIONAL: Enable Incremental Backup
################################################################################
function constant(){
  # LDAP OBJECT
  export readonly DLOBJECT="(objectclass=zimbraDistributionList)"
  export readonly ACOBJECT="(objectclass=zimbraAccount)"
  export readonly ALOBJECT="(objectclass=zimbraAlias)"

  # LDAP FILTER
  export readonly DLFILTER="mail"
  export readonly ACFILTER="zimbraMailDeliveryAddress"
  export readonly ALFILTER="uid"

  # PID FILE
  export readonly PID='/opt/zimbra/log/zmbackup.pid'

  # SESSION VARS
  if [[ $1 == '--full' || $1 == '-f' ]]; then
    export readonly STYPE="Full Account"
    export readonly SESSION="full-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '--incremental' || $1 == '-i' ]]; then
    export readonly STYPE="Incremental Account"
    export readonly SESSION="inc-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '--alias' || $1 == '-al' ]]; then
    export readonly STYPE="Alias"
    export readonly SESSION="alias-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '-dl' || $1 == '--distributionlist' ]]; then
    export readonly STYPE="Distribution List"
    export readonly SESSION="distlist-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '-m' || $1 == '--mail' ]]; then
    export readonly STYPE="Mailbox"
    export readonly SESSION="mbox-"$(date  +%Y%m%d%H%M%S)
  elif [[ $1 == '--ldap' || $1 == '-ldp' ]]; then
    export readonly STYPE="Account - Only LDAP"
    export readonly SESSION="ldap-"$(date  +%Y%m%d%H%M%S)
  else
    export readonly STYPE=""
  fi

  if [[ $2 == 'TRUE' ]]; then
    export readonly INC='TRUE'
  else
    export readonly INC='FALSE'
  fi
}

################################################################################
# list_sessions: List all the sessions stored inside the server
################################################################################
function list_sessions ()
{
  printf "+---------------------------+------------+----------+----------------------------+\n"
  printf "|       Session Name        |    Date    |   Size   |        Description         |\n"
  printf "+---------------------------+------------+----------+----------------------------+\n"
  for i in $(egrep 'SESSION:' $WORKDIR/sessions.txt | egrep 'started' |  awk '{print $2}' | sort | uniq); do

    # Load variables
    SIZE=$(du -h $WORKDIR/$i | awk {'print $1'})
    if [[ $i == "mbox"* ]]; then
      QTDE=$(ls $WORKDIR/$i/*.tgz | wc -l)
    else
      QTDE=$(ls $WORKDIR/$i/*.ldiff | wc -l)
    fi
    OPT=$(echo $i | cut -d"-" -f1 )
    case $OPT in
      "full")
          OPT="Full Backup"
          YEAR=$(echo $i | cut -c6-9)
          MONTH=$(echo $i | cut -c10-11)
          DAY=$(echo $i | cut -c12-13)
      ;;
      "inc")
          OPT="Incremental Backup"
          YEAR=$(echo $i | cut -c5-8)
          MONTH=$(echo $i | cut -c9-10)
          DAY=$(echo $i | cut -c11-12)
      ;;
      "distlist")
          OPT="Distribution List Backup"
          YEAR=$(echo $i | cut -c10-13)
          MONTH=$(echo $i | cut -c14-15)
          DAY=$(echo $i | cut -c16-17)
      ;;
      "alias")
          OPT="Alias Backup"
          YEAR=$(echo $i | cut -c7-10)
          MONTH=$(echo $i | cut -c11-12)
          DAY=$(echo $i | cut -c13-14)
      ;;
      "ldap")
          OPT="Account Backup - Only LDAP"
          YEAR=$(echo $i | cut -c6-9)
          MONTH=$(echo $i | cut -c10-11)
          DAY=$(echo $i | cut -c12-13)
      ;;
    esac

    # Printing the information as a table
    printf "| %-25s | %s/%s/%s | %-8s | %-26s |\n" $i $MONTH $DAY $YEAR $SIZE "$OPT"
  done
  printf "+---------------------------+------------+----------+----------------------------+\n"
}

################################################################################
# validate_config: Validate if all the values are informed and set the default if not
################################################################################
function validate_config(){

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

  if ! [ -d "$WORKDIR" ]; then
    echo "The directory $WORKDIR doesn't exist."
    logger -i -p local7.err "Zmbackup: The directory $WORKDIR does not found."
    exit 2
  fi

  if [ -z "$MAILHOST" ]; then
    MAILHOST="127.0.0.1"
    logger -i -p local7.warn "Zmbackup: MAILHOST not informed - setting as 127.0.0.1 instead."
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

  if [ -z "$ADMINUSER" ]; then
    echo "You need to define the variable ADMINUSER."
    logger -i -p local7.err "Zmbackup: You need to define the variable ADMINUSER."
    exit 2
  fi

  if [ -z "$ADMINPASS" ]; then
    echo "You need to define the variable ADMINPASS."
    logger -i -p local7.err "Zmbackup: You need to define the variable ADMINPASS."
    exit 2
  fi

  if [ -z "$LDAPSERVER" ]; then
    echo "You need to define the variable LDAPSERVER."
    logger -i -p local7.err "Zmbackup: You need to define the variable LDAPSERVER."
    exit 2
  fi

  if [ -z "$LDAPADMIN" ]; then
    echo "You need to define the variable LDAPADMIN."
    logger -i -p local7.err "Zmbackup: You need to define the variable LDAPADMIN."
    exit 2
  fi

  if [ -z "$LDAPPASS" ]; then
    echo "You need to define the variable LDAPPASS."
    logger -i -p local7.err "Zmbackup: You need to define the variable LDAPPASS."
    exit 2
  fi

  if [ -z "$ROTATE_TIME" ]; then
    echo "You need to define the variable ROTATE_TIME."
    logger -i -p local7.err "Zmbackup: You need to define the variable ROTATE_TIME."
    exit 2
  fi
}

################################################################################
# checkpid: Check if the PID file exist. If exist, exit with status 3 and do nothing
################################################################################
function checkpid(){
  if [[ -f "$PID" ]]; then
    echo "FATAL: could not write lock file '/opt/zimbra/log/zmbackup.pid': File already exist"
    echo "This file exist as a secure measurement to protect your system to run two zmbackup"
    echo "instances at the same time."
    exit 3
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
}
