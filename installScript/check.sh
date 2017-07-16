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

################################################################################
# check_config: Check the environment for other configurations
################################################################################
function check_config() {
  echo "Recovering all the configuration... Please wait"
  OSE_INSTALL_HOSTNAME=`su - $OSE_USER -c "zmhostname"`
  OSE_INSTALL_ADDRESS=`grep $OSE_INSTALL_HOSTNAME /etc/hosts|awk '{print $1}'`
  OSE_INSTALL_LDAPPASS=`su - $OSE_USER -c "zmlocalconfig -s zimbra_ldap_password"|awk '{print $3}'`

  echo ""
  echo "Here is a Summary of your settings:"
  echo ""
  echo "Zimbra User: $OSE_USER"
  echo "Zimbra Hostname: $OSE_INSTALL_HOSTNAME"
  echo "Zimbra IP Address: $OSE_INSTALL_ADDRESS"
  echo "Zimbra LDAP Password: $OSE_INSTALL_LDAPPASS"
  echo "Zimbra Zmbackup Account: $ZMBKP_ACCOUNT"
  echo "Zimbra Zmbackup Password: $ZMBKP_PASSWORD"
  echo "Zimbra Install Directory: $OSE_INSTALL_DIR"
  echo "Zimbra Backup Directory: $ZMBKP_BKPDIR"
  echo "Zmbackup Install Directory: $ZMBKP_SRC"
  echo "Zmbackup Settings Directory: $ZMBKP_CONF"
  echo "Zmbackup Backups Days Max: $ZMBKP_BKPTIME"
  echo ""
  echo "Press ENTER to continue or CTRL+C to cancel."
  read
}
