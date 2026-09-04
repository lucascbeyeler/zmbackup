#!/bin/bash
################################################################################
# contract() (the license banner) is reused as-is from installScript/menu.sh -
# see install-java.sh, which sources both files.
################################################################################

################################################################################
# set_values_java: Set all the variables for the Java build of Zmbackup
################################################################################
function set_values_java() {
  echo "##################################################################################"
  echo "#                                                                                #"
  echo "# The follow messages will ask you about some configurations for Zmbackup run in #"
  echo "# your server. Please answer each one of then or press ENTER to assume the       #"
  echo "# default value.                                                                 #"
  echo "#                                                                                #"
  echo "##################################################################################"
  echo -e "\n"

  # Inform Zimbra's default user
  printf "Inform Zimbra's default user - DEFAULT [%s]:" "$OSE_USER"
  read -r TMP
  OSE_USER=${TMP:-$OSE_USER}

  # Inform Zimbra's default install path
  printf "\nInform Zimbra's default install path - DEFAULT [%s]:" "$OSE_INSTALL_DIR"
  read -r TMP
  OSE_INSTALL_DIR=${TMP:-$OSE_INSTALL_DIR}

  # Inform Zmbackup's backup store
  printf "\nInform the path Zmbackup will use to store - DEFAULT [%s]:" "$OSE_DEFAULT_BKP_DIR"
  read -r TMP
  OSE_DEFAULT_BKP_DIR=${TMP:-$OSE_DEFAULT_BKP_DIR}

  # Configure the Zimbra admin account used for mailbox REST auth
  printf "\nInform the Zimbra admin account Zmbackup should use for mailbox REST auth - DEFAULT [%s]:" "$ZMBKP_REST_ADMIN"
  read -r TMP
  ZMBKP_REST_ADMIN=${TMP:-$ZMBKP_REST_ADMIN}

  # Configure that account's own password - this is its Zimbra login password,
  # a separate credential from the LDAP bind password above; Zimbra's REST
  # servlet 401s a mailbox export/restore if the two are conflated.
  printf "\nInform %s's own Zimbra password (not the LDAP password above):" "$ZMBKP_REST_ADMIN"
  read -r TMP
  ZMBKP_REST_ADMIN_PASS=${TMP:-$ZMBKP_REST_ADMIN_PASS}

  # Configure mail alert recipient
  printf "\nInform the account to receive all Zmbackup's alerts - DEFAULT [%s]:" "$ZMBKP_MAIL_ALERT"
  read -r TMP
  ZMBKP_MAIL_ALERT=${TMP:-$ZMBKP_MAIL_ALERT}

  # Configure mail alert sender
  printf "\nInform the account Zmbackup should send alerts from - DEFAULT [%s]:" "$ZMBKP_MAIL_SENDER"
  read -r TMP
  ZMBKP_MAIL_SENDER=${TMP:-$ZMBKP_MAIL_SENDER}

  # Configure parallelism
  printf "\nInform Zmbackup's number of parallel workers - DEFAULT [%s]:" "$MAX_PARALLEL_PROCESS"
  read -r TMP
  MAX_PARALLEL_PROCESS=${TMP:-$MAX_PARALLEL_PROCESS}

  # Configure rotation
  printf "\nInform the number of days Zmbackup should store the backups - DEFAULT [%s]:" "$ROTATE_TIME"
  read -r TMP
  ROTATE_TIME=${TMP:-$ROTATE_TIME}

  # Configure lock
  printf "\nZmbackup should limit backups for one per day? - DEFAULT [%s]:" "$LOCK_BACKUP"
  read -r TMP
  LOCK_BACKUP=${TMP:-$LOCK_BACKUP}

  echo -e "\n\n"
  echo "##################################################################################"
  echo "#                                                                                #"
  echo "#                            CONFIGURATION COMPLETED                             #"
  echo "#                                                                                #"
  echo "##################################################################################"
}

################################################################################
# check_config_java: Check the environment for other configurations
################################################################################
function check_config_java() {
  echo ""
  echo "Here is a Summary of your settings:"
  echo ""
  echo "Zimbra User: $OSE_USER"
  echo "Zimbra IP Address: $OSE_INSTALL_ADDRESS"
  echo "Zimbra LDAP/Admin Password: $OSE_INSTALL_LDAPPASS"
  echo "Zimbra REST Admin Account: $ZMBKP_REST_ADMIN"
  echo "Zimbra REST Admin Password: $ZMBKP_REST_ADMIN_PASS"
  echo "Zimbra Install Directory: $OSE_INSTALL_DIR"
  echo "Zimbra Backup Directory: $OSE_DEFAULT_BKP_DIR"
  echo "Zmbackup Launcher: $ZMBKP_SRC/zmbackup"
  echo "Zmbackup Jar: $ZMBKP_LIB/$ZMBKP_JAR_NAME"
  echo "Zmbackup Settings Directory: $ZMBKP_CONF"
  echo "Zmbackup Backups Days Max: $ROTATE_TIME"
  echo "Zmbackup Number of Parallel Workers: $MAX_PARALLEL_PROCESS"
  echo "Zmbackup Backup Lock: $LOCK_BACKUP"
  echo "Zmbackup Session Storage: SQLite3 ($OSE_DEFAULT_BKP_DIR/sessions.sqlite3)"
  echo ""
  echo "Press ENTER to continue or CTRL+C to cancel."
  read -r
}
