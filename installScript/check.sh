#!/bin/bash
################################################################################
# check_env is reused as-is by install-java.sh (see its top-level source line)
# for root/OS/upgrade detection, which doesn't depend on which language
# zmbackup itself is written in.
################################################################################

################################################################################
# check_env: Check the environment if everything is okay to begin the install
################################################################################
function check_env() {
  printf "  Root Privileges...	          "
  if [ "$(id -u)" -ne 0 ]; then
    printf "[NO ROOT]\n"
  	echo "You need root privileges to install zmbackup"
  	exit "$ERR_NOROOT"
  else
    printf "[ROOT]\n"
  fi
  printf "  Old Zmbackup Install...	  "
  su -s /bin/bash -c "whereis zmbackup" "$OSE_USER" > /dev/null 2>&1
  BASHERRCODE=$?
  if [ $BASHERRCODE != 0 ]; then
    printf "[NEW INSTALL]\n"
    export UPGRADE="N"
    export UNINSTALL="N"
  elif [[ $1 == '--remove' ]] || [[ $1 == '-r' ]]; then
    printf "[UNINSTALL] - EXECUTING UNINSTALL ROUTINE\n"
    export UPGRADE="N"
    export UNINSTALL="Y"
  elif [[ $1 == '--force-upgrade' ]]; then
    VERSION=$(su -s /bin/bash -c "zmbackup -h" "$OSE_USER")
    if [[ "$VERSION" != "$ZMBKP_VERSION" ]]; then
      printf "[OLD VERSION] - EXECUTING UPGRADE ROUTINE\n"
      export UPGRADE="Y"
      export UNINSTALL="N"
    else
      echo "[NEWEST VERSION] - Nothing to do..."
      exit 0
    fi
  fi
  printf "  Checking OS...	          "
  which apt > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    printf "[UBUNTU SERVER]\n"
    SO="ubuntu"
  fi
  which yum > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    printf "[RED HAT ENTERPRISE LINUX]\n"
    SO="redhat"
  elif [[ -z $SO ]]; then
    printf "[UNSUPPORTED]\n"
    exit 1
  fi
}
