#!/bin/bash
################################################################################
# install - Install script to help you install zmbackup in your server. You can
#           simply ignore this file and move the files to the correctly place, but
#           the chance for this goes wrong is big. So, this script made everything
#           for you easy.
#
################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
################################################################################
# SET INTERNAL VARIABLE
################################################################################

# Exit codes
ERR_OK="0"  		# No error (normal exit)
ERR_NOBKPDIR="1"  	# No backup directory could be found
ERR_NOROOT="2"  		# Script was run without root privileges
ERR_DEPNOTFOUND="3"  	# Missing dependency

# ZMBACKUP INSTALLATION PATH
ZMBKP_SRC="/usr/local/bin"
ZMBKP_CONF="/etc/zmbackup"

# ZIMBRA DEFAULT INSTALLATION PATH AND INTERNAL CONFIGURATION
OSE_USER="zimbra"
OSE_INSTALL_DIR="/opt/zimbra"

################################################################################
# CHECKS BEFORE BEGIN THE INSTALL PROCESS
################################################################################

# Check if the script is running as root user
if [ $(id -u) -ne 0 ]; then
	echo "You need root privileges to install zmbackup"
	exit $ERR_NOROOT
fi

# Check for missing installer files
printf "Checking installer integrity...	"
STATUS=0
MYDIR=`dirname $0`
test -f $MYDIR/src/zmbackup      || STATUS=$ERR_MISSINGFILES
test -f $MYDIR/etc/zmbackup.conf || STATUS=$ERR_MISSINGFILES
if ! [ $STATUS = 0 ]; then
	printf '[ERROR]\n'
	echo "Some files are missing. Please re-download the zmbackup installer."
	exit $STATUS
else
	printf '[OK]\n'
fi

################################################################################
# INSTALL PROCESS
################################################################################

clear
echo "################################################################################"
echo "#                                                                              #"
echo "#                     ZMBACKUP INSTALLATION SCRIPT                             #"
echo "#                                                                              #"
echo "################################################################################"
echo ""
echo "zmbackup is a Open Source software for hot backup and hot restore of the Zimbra."
echo "This is not part of the Zimbra Open Source Community Edition or the Zimbra Plus,"
echo "this is made by the community for the community, so this has NO WARRANTY.       "
echo ""
echo "#################################################################################"
echo ""
echo "WARNING: This is a pre-release and does not supposed to be used in production in"
echo "any way."
echo ""
echo "If you are okay with this and still want to install the zmbackup, press Y."
printf "Are you sure? [N]: "
read OPT
if [[ $OPT != 'Y' && $OPT != 'y' ]]; then
	echo "Stoping the installation process..."
	exit 0
fi
printf "\n\n\n\n"

echo "First, we need that you inform the user used to run Zimbra in this server. Usually"
echo "the account is '$OSE_USER', but, if you did a custom install, there is a high chance"
echo "you changed this information."

printf "Should I set the default values? [Y]"
read OPT
if [[ $OPT != 'Y' && $OPT != 'y' ]]; then
	printf "\n Please inform the zimbra's system account[$OSE_USER]: "
	read OSE_USER || OSE_USER=$OSE_USER

	printf "\n Please inform the zimbra's default folder[$OSE_INSTALL_DIR]: "
	read OSE_INSTALL_DIR || OSE_INSTALL_DIR=$OSE_INSTALL_DIR
fi

printf "\n\n"

echo "Configuring the Admin User for zmbackup. This user will be used to zmbackup access"
echo "the e-mail of all accounts and should have only this kind of access. Please do not"
echo "use the admin@domain.com account for this activity."
echo ""

DOMAIN=$(sudo -H -u zimbra bash -c '/opt/zimbra/bin/zmprov gad' | head -n 1)
ZMBKP_PASSWORD=$(date +%s | sha256sum | base64 | head -c 32 ; echo)
sudo -H -u $OSE_USER bash -c "/opt/zimbra/bin/zmprov ca zmbackup@$DOMAIN '$ZMBKP_PASSWORD' zimbraIsAdminAccount TRUE" > /dev/null 2>&1

if ! [ $? -eq 0 ]; then
	echo "User zmbackup already exist! Changing the password to get access..."
	sudo -H -u $OSE_USER bash -c "/opt/zimbra/bin/zmprov sp zmbackup@$DOMAIN '$ZMBKP_PASSWORD'"  > /dev/null 2>&1
fi

ZMBKP_ACCOUNT="zmbackup@$DOMAIN"

echo "Account configured!"

echo "Configuring mail alert when the zmbackup is executed or finish a backup process."
printf "Please inform the account or distribuition list that will receive this messages.: "
read ZMBKP_MAIL_ALERT

echo ""
echo "Recovering all the configuration... Please wait"

OSE_INSTALL_HOSTNAME=`su - zimbra -c "zmhostname"`
OSE_INSTALL_ADDRESS=`grep $OSE_INSTALL_HOSTNAME /etc/hosts|awk '{print $1}'`
OSE_INSTALL_LDAPPASS=`su - zimbra -c "zmlocalconfig -s zimbra_ldap_password"|awk '{print $3}'`

printf "\nPlease, inform the folder where the backup will be stored: "
read ZMBKP_BKPDIR || ZMBKP_BKPDIR="$OSE_INSTALL_DIR/backup"

mkdir $ZMBKP_BKPDIR > /dev/null 2>&1 && chown $OSE_USER.$OSE_USER $ZMBKP_BKPDIR > /dev/null 2>&1

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
echo ""
echo "Press ENTER to continue or CTRL+C to cancel."
read

# Check for missing dependencies
STATUS=0
printf "\n\nChecking system for dependencies...\n\n"

## Zimbra Mailbox
printf "  ZCS Mailbox Control...  "
su - $ZIMBRA_USER -c "which zmmailboxdctl" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## LDAP utils
printf "  ldapsearch...	          "
su - $ZIMBRA_USER -c "which ldapsearch" > /dev/null 2>&1
if [ $? = 0 ]; then
	printf "[OK]\n"
else
	printf "[NOT FOUND]\n"
	STATUS=$ERR_DEPNOTFOUND
fi

## Curl
printf "  curl...                 "
su - $ZIMBRA_USER -c "which curl" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## mktemp
printf "  mktemp...               "
su - $ZIMBRA_USER -c "which mktemp" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## date
printf "  date...                 "
su - $ZIMBRA_USER -c "which date" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## egrep
printf "  egrep...                "
su - $ZIMBRA_USER -c "which egrep" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## egrep
printf "  wget...                 "
su - $ZIMBRA_USER -c "which wget" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## egrep
printf "  parallel...             "
su - $ZIMBRA_USER -c "which parallel" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

if ! [ $STATUS = 0 ]; then
	echo ""
	echo "You're missing some dependencies OR they are not on $ZIMBRA_USER's PATH."
	echo "Please correct the problem and run the installer again."
	exit $STATUS
fi
# Done checking deps

echo "Installing... Please wait while we made some changes."

# Create directories if needed
test -d $OSE_CONF || mkdir -p $OSE_CONF
test -d $OSE_SRC  || mkdir -p $OSE_SRC

# Copy files
install -o $ZIMBRA_USER -m 700 $MYDIR/src/zmbackup $OSE_SRC
install --backup=numbered -o $ZIMBRA_USER -m 600 $MYDIR/etc/zmbackup.conf $OSE_CONF

# Add custom settings
sed -i "s|{ZMBKP_BKPDIR}|${ZMBKP_BKPDIR}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{ZMBKP_ACCOUNT}|${ZMBKP_ACCOUNT}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{ZMBKP_PASSWORD}|${ZMBKP_PASSWORD}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{ZMBKP_MAIL_ALERT}|${ZMBKP_MAIL_ALERT}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{OSE_INSTALL_ADDRESS}|${OSE_INSTALL_ADDRESS}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{OSE_LDAPPASS}|${OSE_LDAPPASS}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{OSE_USER}|${OSE_USER}|g" $OSE_CONF/zmbackup.conf

# Fix backup dir permissions (owner MUST be $ZIMBRA_USER)
chown $ZIMBRA_USER $ZIMBRA_BKPDIR

# We're done!
read -p "Install completed. Do you want to display the README file? (Y/n)" tmp
case "$tmp" in
	y|Y|Yes|"") less $MYDIR/README.md;;
	*) echo "Done!";;
esac

exit $ERR_OK
