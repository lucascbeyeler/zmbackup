#!/bin/bash
################################################################################

################################################################################
# blacklist_gen: Generate a blacklist of all accounts Zmbackup should ignore
################################################################################
function blacklist_gen(){
  for ACCOUNT in $(sudo -H -u $OSE_USER bash -c "/opt/zimbra/bin/zmprov -l gaa"); do
    if  [[ "$ACCOUNT" == "galsync."* ]] && \
    [[ "$ACCOUNT" == "virus-"* ]] && \
    [[ "$ACCOUNT" == "ham."* ]] && \
    [[ "$ACCOUNT" == "admin@"* ]] && \
    [[ "$ACCOUNT" == "spam."* ]] && \
    [[ "$ACCOUNT" == "zmbackup@"* ]] && \
    [[ "$ACCOUNT" == "postmaster@"* ]] && \
    [[ "$ACCOUNT" == "root@"* ]]; then
      echo $ACCOUNT >> /etc/zmbackup.conf
    fi
  done
}

################################################################################
# deploy_new: Deploy a new version of Zmbackup
################################################################################
function deploy_new() {
  echo "Installing... Please wait while we made some changes."
  echo -ne '                      (0%)\r'
  mkdir $ZMBKP_BKPDIR > /dev/null 2>&1 && chown $OSE_USER.$OSE_USER $ZMBKP_BKPDIR > /dev/null 2>&1
  echo -ne '#                     (5%)\r'
  test -d $ZMBKP_CONF || mkdir -p $ZMBKP_CONF
  echo -ne '##                    (10%)\r'
  test -d $ZMBKP_SRC  || mkdir -p $ZMBKP_SRC
  echo -ne '###                   (15%)\r'
  test -d $ZMBKP_SHARE || mkdir -p $ZMBKP_SHARE
  echo -ne '####                  (20%)\r'

  # Copy files
  install -o $OSE_USER -m 700 $MYDIR/zmbackup $ZMBKP_SRC
  echo -ne '#####                 (25%)\r'
  install -o $OSE_USER -m 600 $MYDIR/lib/* $ZMBKP_LIB
  echo -ne '######                (30%)\r'
  install --backup=numbered -o root -m 600 /etc/zmbackup.cron /etc/cron.d
  echo -ne '#######               (35%)\r'
  install --backup=numbered -o $OSE_USER -m 600 /etc/zmbackup.conf $ZMBKP_CONF
  echo -ne '########              (40%)\r'
  install --backup=numbered -o $OSE_USER -m 600 /etc/blacklist.conf $ZMBKP_CONF
  echo -ne '#########             (45%)\r'

  # Including custom settings
  sed -i "s|{ZMBKP_BKPDIR}|${ZMBKP_BKPDIR}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '##########            (50%)\r'
  sed -i "s|{ZMBKP_ACCOUNT}|${ZMBKP_ACCOUNT}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '###########           (55%)\r'
  sed -i "s|{ZMBKP_PASSWORD}|${ZMBKP_PASSWORD}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '############          (60%)\r'
  sed -i "s|{ZMBKP_MAIL_ALERT}|${ZMBKP_MAIL_ALERT}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '#############         (65%)\r'
  sed -i "s|{OSE_INSTALL_ADDRESS}|${OSE_INSTALL_ADDRESS}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '##############        (70%)\r'
  sed -i "s|{OSE_INSTALL_LDAPPASS}|${OSE_INSTALL_LDAPPASS}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '###############       (75%)\r'
  sed -i "s|{OSE_USER}|${OSE_USER}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '################      (80%)\r'
  sed -i "s|{ROTATE_TIME}|${ZMBKP_BKPTIME}|g" $ZMBKP_CONF/zmbackup.conf
  echo -ne '#################     (85%)\r'

  # Fix backup dir permissions (owner MUST be $OSE_USER)
  chown $OSE_USER $ZMBKP_BKPDIR
  echo -ne '##################    (90%)\r'

  # Generate Zmbackup's blacklist
  blacklist_gen
  echo -ne '###################   (95%)\r'

  # Creating Zmbackup backup user
  sudo -H -u $OSE_USER bash -c "/opt/zimbra/bin/zmprov ca zmbackup@$DOMAIN '$ZMBKP_PASSWORD' zimbraIsAdminAccount TRUE zimbraAdminAuthTokenLifetime 1" > /dev/null 2>&1
  echo -ne '####################  (100%)\r'
}

################################################################################
# deploy_upgrade: Upgrade the old version to the new one
################################################################################
function deploy_upgrade(){
  # Removing old version
  echo -ne '                     (0%)\r'
  rm -rf $ZMBKP_SHARE $ZMBKP_SRC/zmbhousekeep > /dev/null 2>&1
  echo -ne '##########            (50%)\r'

  # Copy files
  install -o $OSE_USER -m 700 $MYDIR/zmbackup $ZMBKP_SRC
  echo -ne '###############       (75%)\r'
  install -o $OSE_USER -m 600 $MYDIR/lib/* $ZMBKP_LIB
  echo -ne '####################  (100%)\r'
}
