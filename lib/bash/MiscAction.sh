#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# clear_temp: Clear all the temporary files.
################################################################################
function clear_temp(){
  rm -rf $TEMPSESSION $TEMPACCOUNT $TEMPINCACCOUNT $TEMPDIR $PID $MESSAGE
  logger -i --id=$$ -p local7.info "Zmbackup: Excluding the temporary files before close."
}

#trap the function to be executed if the sript DIE
trap clear_temp TERM INT

################################################################################
# create_temp: Create the temporary files used by the script.
################################################################################
function create_temp(){
  export readonly TEMPDIR=$(mktemp -d $WORKDIR/XXXX)
  export readonly TEMPSESSION=$(mktemp)
  export readonly TEMPACCOUNT=$(mktemp)
  export readonly TEMPINCACCOUNT=$(mktemp)
  export readonly MESSAGE=$(mktemp)
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
  fi
  if [ -f "/opt/zimbra/.bashrc" ]; then
    source /opt/zimbra/.bashrc 2> /dev/null
  else
    logger -i --id=$$ -p local7.err "Zmbackup: zimbra user's .bashrc not found."
    echo "ERROR - zimbra user's .bashrc not found. Can't proceed whitout the file."
  fi
}

################################################################################
# constants: Initialize all the constants used by the Zmbackup.
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
}

################################################################################
# list_sessions: List all the sessions and bring information about it to the user
################################################################################
list_sessions ()
{
  printf "+---------------------------+------------+----------+-------------------------+\n"
  printf "|       Session Name        |    Date    |   Size   |       Description       |\n"
  printf "+---------------------------+------------+----------+-------------------------+\n"
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
    printf "| %25s | %s/%s/%s | %8s | %23s |\n" $i $MONTH $DAY $YEAR $SIZE $OPT
  done
  printf "+---------------------------+------------+----------+-------------------------+\n"
}
