#!/bin/bash
################################################################################
# SET INTERNAL VARIABLE
################################################################################

# Exit codes
ERR_OK="0"  		         # No error (normal exit)
ERR_NOBKPDIR="1"  	     # No backup directory could be found
ERR_NOROOT="2"  		     # Running without root privileges
ERR_DEPNOTFOUND="3"  	   # Missing dependency

# ZMBACKUP INSTALLATION PATH
ZMBKP_SRC="/usr/local/bin"               # The main script stay here
ZMBKP_CONF="/etc/zmbackup"               # The config/blacklist directory
ZMBKP_SHARE="/usr/local/share/zmbackup"  # Keep for upgrade routine
ZMBKP_LIB="/usr/local/lib/zmbackup"      # The new path for the libs

# ZIMBRA DEFAULT INSTALLATION PATH AND INTERNAL CONFIGURATION
OSE_USER="zimbra"                               # Zimbra's unix user
OSE_INSTALL_DIR="/opt/zimbra"                   # The Zimbra's installation path
OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"        # Where you will store your backup
