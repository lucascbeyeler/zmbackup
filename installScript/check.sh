#!/bin/bash

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
    if [[ ! -f "$ZMBKP_CONF"/zmbackup.yaml ]] || [[ ! -f "$ZMBKP_LIB/$ZMBKP_JAR_NAME" ]]; then
      printf "[BASH-TOOL INSTALL FOUND] - EXECUTING FRESH JAVA INSTALL ROUTINE\n"
      export UPGRADE="N"
      export UNINSTALL="N"
    else
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
