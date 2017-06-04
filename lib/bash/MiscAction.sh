#!/bin/bash
################################################################################
# Miscellaneous Functions
################################################################################

################################################################################
# clear_temp: Clear all the temporary files
################################################################################
function clear_temp(){
  rm -rf $TEMPSESSION $TEMPACCOUNT $TEMPINCACCOUNT $TEMPDIR $PID $MESSAGE
}

#trap the function to be executed if the sript DIE
trap clear_temp TERM INT

################################################################################
# create_temp: Create the temporary files used by the script
################################################################################
function create_temp(){
  export readonly TEMPDIR=$(mktemp -d $WORKDIR/XXXX)
  export readonly TEMPSESSION=$(mktemp)
  export readonly TEMPACCOUNT=$(mktemp)
  export readonly TEMPINCACCOUNT=$(mktemp)
  export readonly MESSAGE=$(mktemp)
}

################################################################################
# load_config: Load the config file and zimbra's bashrc
################################################################################
function load_config(){
  source /etc/zmbackup/zmbackup.conf
  source /opt/zimbra/.bashrc
}

################################################################################
# constants: Initialize all the constants used i
################################################################################
function constant(){
  # LDAP OBJECT
  export readonly DLOBJECT="(objectclass=zimbraDistributionList)"
  export readonly ACOBJECT="(objectclass=zimbraAccount)"
  export readonly ALOBJECT="(objectclass=zimbraAlias)"

  # LDAP FILTER
  export readonly ACFILTER="zimbraMailDeliveryAddress"
  export readonly DLFILTER="mail"
  export readonly ALFILTER="uid"

  # PID FILE
  export readonly PID='/var/run/zmbackup/zmbackup.pid'
}
