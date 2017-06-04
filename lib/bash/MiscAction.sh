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
