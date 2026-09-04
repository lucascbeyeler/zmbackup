#!/bin/bash

ERR_OK="0"
ERR_NOROOT="2"
ERR_DEPNOTFOUND="3"
# shellcheck disable=SC2034
ERR_NO_CONNECTION="4"
ERR_BUILD_FAILED="6"

MYDIR=$(dirname "$0")
ZMBKP_SRC="/usr/local/bin"
ZMBKP_CONF="/etc/zmbackup"
ZMBKP_LIB="/usr/local/lib/zmbackup"
ZMBKP_JAR_NAME="zmbackup.jar"
ZMBKP_CRON_FILE="/etc/cron.d/zmbackup"

JAVA_MIN_VERSION="21"

OSE_USER="zimbra"
OSE_INSTALL_DIR="/opt/zimbra"
OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"
OSE_INSTALL_DOMAIN=$(su -s /bin/bash -c "$OSE_INSTALL_DIR/bin/zmprov gad | head -1" "$OSE_USER")
OSE_INSTALL_HOSTNAME=$(hostname --fqdn)
OSE_INSTALL_ADDRESS=$(ping -c1 "$OSE_INSTALL_HOSTNAME" | head -1 | cut -d" " -f3|sed 's#(##g'|sed 's#)##g')
OSE_INSTALL_LDAPPASS=$(su -s /bin/bash -c "$OSE_INSTALL_DIR/bin/zmlocalconfig -s zimbra_ldap_password" "$OSE_USER" |awk '{print $3}')
ZMBKP_REST_ADMIN="admin@"$OSE_INSTALL_DOMAIN
ZMBKP_REST_ADMIN_PASS=""
ZMBKP_MAIL_ALERT="admin@"$OSE_INSTALL_DOMAIN
ZMBKP_MAIL_SENDER="root@"$OSE_INSTALL_DOMAIN
MAX_PARALLEL_PROCESS="3"
ROTATE_TIME="30"
LOCK_BACKUP=true
ZMBKP_VERSION="zmbackup version: $(cat "$MYDIR/VERSION")"

export TERM="linux"
