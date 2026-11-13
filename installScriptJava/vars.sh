#!/bin/bash

ERR_OK="0"
ERR_NOROOT="2"
ERR_DEPNOTFOUND="3"
# shellcheck disable=SC2034
ERR_NO_CONNECTION="4"
ERR_MISSING_VALUE="5"
ERR_BUILD_FAILED="6"

MYDIR=$(dirname "$0")
ZMBKP_SRC="/usr/local/bin"
ZMBKP_CONF="/etc/zmbackup"
ZMBKP_LIB="/usr/local/lib/zmbackup"
ZMBKP_JAR_NAME="zmbackup.jar"
ZMBKP_CRON_FILE="/etc/cron.d/zmbackup"
ZMBKP_CRON_OWNER="root"

JAVA_MIN_VERSION="17"

function suite_defaults() {
  local ROOT="$1"
  if [[ -d "$ROOT/opt/zextras" && ! -d "$ROOT/opt/zimbra" ]]; then
    echo "carbonio zextras /opt/zextras"
  else
    echo "zimbra zimbra /opt/zimbra"
  fi
}

read -r OSE_SUITE SUITE_USER SUITE_DIR <<< "$(suite_defaults "")"
OSE_USER="${OSE_USER:-$SUITE_USER}"
OSE_INSTALL_DIR="${OSE_INSTALL_DIR:-$SUITE_DIR}"
OSE_DEFAULT_BKP_DIR="${OSE_DEFAULT_BKP_DIR:-$SUITE_DIR/backup}"
OSE_INSTALL_DOMAIN="${OSE_INSTALL_DOMAIN:-$(sudo -H -u "$OSE_USER" bash -c "$OSE_INSTALL_DIR/bin/zmprov gad | head -1")}"
OSE_INSTALL_HOSTNAME=$(hostname --fqdn)
OSE_INSTALL_ADDRESS="${OSE_INSTALL_ADDRESS:-$OSE_INSTALL_HOSTNAME}"
OSE_INSTALL_LDAPPASS="${OSE_INSTALL_LDAPPASS:-$(sudo -H -u "$OSE_USER" bash -c "$OSE_INSTALL_DIR/bin/zmlocalconfig -s zimbra_ldap_password" | awk '{print $3}')}"
ZMBKP_REST_ADMIN="${ZMBKP_REST_ADMIN:-admin@$OSE_INSTALL_DOMAIN}"
ZMBKP_REST_ADMIN_PASS="${ZMBKP_REST_ADMIN_PASS:-}"
ZMBKP_METADATA_DIR="${ZMBKP_METADATA_DIR:-}"
ZMBKP_MAIL_ALERT="${ZMBKP_MAIL_ALERT:-admin@$OSE_INSTALL_DOMAIN}"
ZMBKP_MAIL_SENDER="${ZMBKP_MAIL_SENDER:-root@$OSE_INSTALL_DOMAIN}"
MAX_PARALLEL_PROCESS="${MAX_PARALLEL_PROCESS:-3}"
ROTATE_TIME="${ROTATE_TIME:-30}"
LOCK_BACKUP="${LOCK_BACKUP:-true}"
ZMBKP_SKIP_CRON_CHECK="${ZMBKP_SKIP_CRON_CHECK:-N}"
ZMBKP_CA_CERT="${ZMBKP_CA_CERT:-}"
ZMBKP_VERSION="zmbackup version: $(cat "$MYDIR/VERSION")"

export TERM="linux"
