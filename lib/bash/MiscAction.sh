#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# clear_temp: Clear all the temporary files.
################################################################################
function on_exit(){
  if [ $? == 1 ]; then
    notify_finish $SESSION $STYPE FAILURE
  else if [ $? == 0 ]; then
    notify_finish $SESSION $STYPE SUCCESS
  fi
  rm -rf $TEMPSESSION $TEMPACCOUNT $TEMPINCACCOUNT $TEMPDIR $PID $MESSAGE
  logger -i --id=$$ -p local7.info "Zmbackup: Excluding the temporary files before close."
}

#trap the function to be executed if the sript DIE
trap clear_env TERM INT

################################################################################
# create_temp: Create the temporary files used by the script.
################################################################################
function create_temp(){
  export readonly TEMPDIR=$(mktemp -d $WORKDIR/XXXX)
  export readonly TEMPSESSION=$(mktemp)
  export readonly TEMPACCOUNT=$(mktemp)
  export readonly TEMPINCACCOUNT=$(mktemp)
  export readonly MESSAGE=$(mktemp)
  export readonly FAILURE=$(mktemp)
}

################################################################################
# load_config: Load the config file and zimbra's bashrc.
################################################################################
function load_config(){
  if [ -f "/etc/zmbackup/zmbackup.conf" ]; then
    source /etc/zmbackup/zmbackup.conf 2> /dev/null
  else
    logger -i --id=$$ -p local7.err "Zmbackup: zmbackup.conf not found."
    echo "ERROR - zmbackup.conf not found. Can't proceed whitout the file."
    exit 1
  fi
  if [ -f "/opt/zimbra/.bashrc" ]; then
    source /opt/zimbra/.bashrc 2> /dev/null
  else
    logger -i --id=$$ -p local7.err "Zmbackup: zimbra user's .bashrc not found."
    echo "ERROR - zimbra user's .bashrc not found. Can't proceed whitout the file."
    exit 1
  fi
}

################################################################################
# constants: Initialize all the constants used by the Zmbackup.
# Options:
#    $1 - The list of accounts to be backed up
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
  export readonly PID='/var/run/zmbackup/zmbackup.pid'

  # SESSION VARS
  export readonly SESSION="$1-"$(date  +%Y%m%d%H%M%S)
  if [ $1 == 'full']; then
    export readonly STYPE="Full Account"
  else if [ $1 == 'inc']; then
      export readonly STYPE="Incremental Account"
  else if [ $1 == 'alias']; then
      export readonly STYPE="Alias"
  else if [ $1 == 'distlist']; then
      export readonly STYPE="Distribution List"
  else if [ $1 == 'ldap']; then
      export readonly STYPE="Account - Only LDAP"
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
    QTDE=$(ls $WORKDIR/$i/*.ldiff | wc -l)
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
    logger -i --id=$$ -p local7.warn "Zmbackup: BACKUPUSER not informed - setting as user zimbra instead."
  fi

  if [ $(whoami) != "$BACKUPUSER" ]; then
    echo "You need to be $BACKUPUSER to run this software."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to be $BACKUPUSER to run this software."
    exit 1
  fi

  if [ -z "$WORKDIR" ]; then
    WORKDIR="/opt/zimbra/backup"
    logger -i --id=$$ -p local7.warn "Zmbackup: WORKDIR not informed - setting as /opt/zimbra/backup/ instead."
  fi

  if ! [ -d "$WORKDIR" ]; then
    echo "The directory $WORKDIR doesn't exist."
    logger -i --id=$$ -p local7.err "Zmbackup: The directory $WORKDIR does not found."
    exit 1
  fi

  if [ -z "$MAILHOST" ]; then
    MAILHOST="127.0.0.1"
    logger -i --id=$$ -p local7.warn "Zmbackup: MAILHOST not informed - setting as 127.0.0.1 instead."
  fi

  if [ -z "$EMAIL_NOTIFY" ]; then
    EMAIL_NOTIFY="root@localdomain.com"
    logger -i --id=$$ -p local7.warn "Zmbackup: EMAIL_NOTIFY not informed - setting as root@localdomain.com instead."
  fi

  if [ -z "$MAX_PARALLEL_PROCESS" ]; then
    MAX_PARALLEL_PROCESS="1"
    logger -i --id=$$ -p local7.warn "Zmbackup: MAX_PARALLEL_PROCESS not informed - disabling."
  fi

  if [ -z "$LOCK_BACKUP" ]; then
    LOCK_BACKUP=true
    logger -i --id=$$ -p local7.warn "Zmbackup: LOCK_BACKUP not informed - enabling."
  fi

  if [ -z "$ADMINUSER" ]; then
    echo "You need to define the variable ADMINUSER."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to define the variable ADMINUSER."
    exit 1
  fi

  if [ -z "$ADMINPASS" ]; then
    echo "You need to define the variable ADMINPASS."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to define the variable ADMINPASS."
    exit 1
  fi

  if [ -z "$LDAPSERVER" ]; then
    echo "You need to define the variable LDAPSERVER."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to define the variable LDAPSERVER."
    exit 1
  fi

  if [ -z "$LDAPADMIN" ]; then
    echo "You need to define the variable LDAPADMIN."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to define the variable LDAPADMIN."
    exit 1
  fi

  if [ -z "$LDAPPASS" ]; then
    echo "You need to define the variable LDAPPASS."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to define the variable LDAPPASS."
    exit 1
  fi

  if [ -z "$ROTATE_TIME" ]; then
    echo "You need to define the variable ROTATE_TIME."
    logger -i --id=$$ -p local7.err "Zmbackup: You need to define the variable ROTATE_TIME."
    exit 1
  fi
}
