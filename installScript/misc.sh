#!/bin/bash
################################################################################

################################################################################
# check_env: Check the environment if everything is okay to begin the install
################################################################################
function check_env() {
  printf "  Root privileges...  "
  if [ $(id -u) -ne 0 ]; then
    printf "[NO ROOT]\n"
  	echo "You need root privileges to install zmbackup"
  	exit $ERR_NOROOT
  fi
}

function setvars() {

}
