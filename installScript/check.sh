#!/bin/bash
################################################################################

################################################################################
# check_env: Check the environment if everything is okay to begin the install
################################################################################
function check_env() {
  printf "  Root Privileges...	          "
  if [ $(id -u) -ne 0 ]; then
    printf "[NO ROOT]\n"
  	echo "You need root privileges to install zmbackup"
  	exit $ERR_NOROOT
  fi
  printf "  Old Zmbackup Install...	          "
  su - $OSE_USER -c "which zmbackup" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[NEW INSTALL]\n"
    UPGRADE="N"
  else
    printf "[OLD VERSION] - EXECUTING UPGRADE ROUTINE\n"
    UPGRADE="Y"
  fi
}
