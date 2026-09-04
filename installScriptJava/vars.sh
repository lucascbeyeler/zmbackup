#!/bin/bash
################################################################################
# SET INTERNAL VARIABLES - Java installer
################################################################################

# Exit codes - shared with the bash installer's numbering where they overlap
ERR_OK="0"                  # No error (normal exit)
ERR_NOROOT="2"               # Running without root privileges
ERR_DEPNOTFOUND="3"          # Missing dependency
# shellcheck disable=SC2034  # reserved, not thrown yet
ERR_NO_CONNECTION="4"        # Missing connection to install packages
ERR_BUILD_FAILED="6"         # Gradle build of the jar failed

# JAVA ZMBACKUP INSTALLATION PATH
# These match the paths already hard coded in app/src/main/scripts/zmbackup (the thin
# launcher) and YamlConfigLoader.DEFAULT_CONFIG_PATH - they are not free to change here.
MYDIR=$(dirname "$0")                    # The directory where the install script is
ZMBKP_SRC="/usr/local/bin"               # The thin "zmbackup" launcher goes here
ZMBKP_CONF="/etc/zmbackup"               # The zmbackup.yaml/blockedlist directory
ZMBKP_LIB="/usr/local/lib/zmbackup"      # Where zmbackup.jar is installed
ZMBKP_JAR_NAME="zmbackup.jar"            # Must match app/build.gradle.kts shadowJar name
ZMBKP_CRON_FILE="/etc/cron.d/zmbackup"   # Same schedule file the bash tool uses

# JAVA RUNTIME REQUIREMENTS
JAVA_MIN_VERSION="21"

# ZIMBRA DEFAULT INSTALLATION PATH AND INTERNAL CONFIGURATION
OSE_USER="zimbra"                                                                                                                              # Zimbra's unix user
OSE_INSTALL_DIR="/opt/zimbra"                                                                                                                  # The Zimbra's installation path
OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"                                                                                                       # Where you will store your backup
OSE_INSTALL_DOMAIN=$(su -s /bin/bash -c "$OSE_INSTALL_DIR/bin/zmprov gad | head -1" "$OSE_USER")                                               # Zimbra's Domain
OSE_INSTALL_HOSTNAME=$(hostname --fqdn)
OSE_INSTALL_ADDRESS=$(ping -c1 "$OSE_INSTALL_HOSTNAME" | head -1 | cut -d" " -f3|sed 's#(##g'|sed 's#)##g')                                                                   # Zimbra's Server Address
OSE_INSTALL_LDAPPASS=$(su -s /bin/bash -c "$OSE_INSTALL_DIR/bin/zmlocalconfig -s zimbra_ldap_password" "$OSE_USER" |awk '{print $3}')          # Zimbra's LDAP/admin Password
ZMBKP_MAIL_ALERT="admin@"$OSE_INSTALL_DOMAIN                                                                                                   # Zmbackup's mail alert recipient
ZMBKP_MAIL_SENDER="root@"$OSE_INSTALL_DOMAIN                                                                                                   # Zmbackup's mail alert sender
MAX_PARALLEL_PROCESS="3"                                                                                                                       # Zmbackup's number of parallel workers
ROTATE_TIME="30"                                                                                                                               # Zmbackup's max of days before housekeeper
LOCK_BACKUP=true                                                                                                                               # Zmbackup's backup lock
ZMBKP_VERSION="zmbackup version: $(cat "$MYDIR/VERSION")"                                                                                      # Zmbackup's latest version

# Force a terminal type - Issue #90
export TERM="linux"
