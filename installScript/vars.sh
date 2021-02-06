#!/bin/bash
################################################################################
# SET INTERNAL VARIABLE
################################################################################

# Exit codes
ERR_OK="0"  		         # No error (normal exit)
ERR_NOBKPDIR="1"  	     # No backup directory could be found
ERR_NOROOT="2"  		     # Running without root privileges
ERR_DEPNOTFOUND="3"  	   # Missing dependency
ERR_NO_CONNECTION="4"    # Missing connection to install packages
ERR_CREATE_USER="5"      # Can't create the user for some reason

# ZMBACKUP INSTALLATION PATH
MYDIR=`dirname $0`                       # The directory where the install script is
ZMBKP_SRC="/usr/local/bin"               # The main script stay here
ZMBKP_CONF="/etc/zmbackup"               # The config/blocked list directory
ZMBKP_SHARE="/usr/local/share/zmbackup"  # Keep for upgrade routine
ZMBKP_LIB="/usr/local/lib/zmbackup"      # The new path for the libs

# ZIMBRA DEFAULT INSTALLATION PATH AND INTERNAL CONFIGURATION
OSE_USER="zimbra"                                                                                                                              # Zimbra's unix user
OSE_INSTALL_DIR="/opt/zimbra"                                                                                                                  # The Zimbra's installation path
OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"                                                                                                       # Where you will store your backup
OSE_INSTALL_DOMAIN=`su -s /bin/bash -c "$OSE_INSTALL_DIR/bin/zmprov gad | head -1" $OSE_USER`                                                  # Zimbra's Domain
OSE_INSTALL_HOSTNAME=`hostname --fqdn`
OSE_INSTALL_PORT=`cat /opt/zimbra/conf/zmztozmig.conf | grep SourceAdminPort | cut -d"=" -f2`
OSE_INSTALL_ADDRESS=`ping -c1 $OSE_INSTALL_HOSTNAME | head -1 | cut -d" " -f3|sed 's#(##g'|sed 's#)##g'`                                                                   # Zimbra's Server Address
OSE_INSTALL_LDAPPASS=`su -s /bin/bash -c "$OSE_INSTALL_DIR/bin/zmlocalconfig -s zimbra_ldap_password" $OSE_USER |awk '{print $3}'`             # Zimbra's LDAP Password
ZMBKP_MAIL_ALERT="admin@"$OSE_INSTALL_DOMAIN                                                                                                   # Zmbackup's mail alert account
MAX_PARALLEL_PROCESS="3"                                                                                                                       # Zmbackup's number of threads
ROTATE_TIME="30"                                                                                                                               # Zmbackup's max of days before housekeeper
LOCK_BACKUP=true                                                                                                                               # Zmbackup's backup lock
ZMBKP_VERSION="zmbackup version: 1.2.6"                                                                                                        # Zmbackup's latest version
SESSION_TYPE="TXT"                                                                                                                             # Zmbackup's default session type

# REPOSITORIES FOR PACKAGES
OLE_TANGE="http://download.opensuse.org/repositories/home:/tange/CentOS_CentOS-6/home:tange.repo"

# Force a terminal type - Issue #90
export TERM="linux"
