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

# ZMBACKUP INSTALLATION PATH
MYDIR=`dirname $0`                       # The directory where the install script is
ZMBKP_SRC="/usr/local/bin"               # The main script stay here
ZMBKP_CONF="/etc/zmbackup"               # The config/blacklist directory
ZMBKP_SHARE="/usr/local/share/zmbackup"  # Keep for upgrade routine
ZMBKP_LIB="/usr/local/lib/zmbackup"      # The new path for the libs

# ZIMBRA DEFAULT INSTALLATION PATH AND INTERNAL CONFIGURATION
OSE_USER="zimbra"                                                                 # Zimbra's unix user
OSE_INSTALL_DIR="/opt/zimbra"                                                     # The Zimbra's installation path
OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"                                          # Where you will store your backup
ZMBKP_MAIL_ALERT="admin@"$(hostname -d)                                           # Zmbackup's mail alert account
ZMBKP_ACCOUNT="zmbackup@"$(hostname -d)                                           # Zmbackup's backup account
ZMBKP_PASSWORD=$(date +%s | sha256sum | base64 | head -c 32 ; echo)               # Zmbackup's backup password
MAX_PARALLEL_PROCESS="3"                                                          # Zmbackup's number of threads
ROTATE_TIME="30"                                                                  # Zmbackup's max of days before housekeeper
LOCK_BACKUP=true                                                                  # Zmbackup's backup lock
ZMBKP_VERSION="zmbackup version: 1.2.1"                                           # Zmbackup's latest version
SESSION_TYPE="TXT"                                                                # Zmbackup's default session type

# REPOSITORIES FOR PACKAGES
OLE_TANGE="http://download.opensuse.org/repositories/home:/tange/CentOS_CentOS-6/home:tange.repo"
