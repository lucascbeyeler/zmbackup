#!/bin/bash
# This script installs zmbackup on a Zimbra Collaboration Suite Opensource.
# It also makes sure the script's dependencies are present.
#
# LIMITATIONS: This script assumes you're doing a local install and requires the
# user zimbra to exist on your server. While not strictly necessary this is
# enforced due to the way the current zmbackup script works.
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

# zmbackup Defaults - Where the script will be placed and look for its settings
OSE_SRC="/usr/local/bin"
OSE_CONF="/etc/zmbackup"

# Zimbra Defaults - Change these if you compiled zimbra yourself with different
# settings
ZIMBRA_USER="zimbra"
ZIMBRA_DIR="/opt/zimbra"
ZIMBRA_BKPDIR=""		# Leave empty to autodetect
ZIMBRA_HOSTNAME=""		# Leave empty to autodetect
ZIMBRA_ADDRESS=""		# Leave empty to autodetect
ZIMBRA_LDAPPASS=""		# Leave empty to autodetect


# Exit codes
ERR_OK="0"			# No error (normal exit)
ERR_NOBKPDIR="1"		# No backup directory could be found
ERR_NOROOT="2"			# Script was run without root privileges
ERR_DEPNOTFOUND="3"		# Missing dependency

# Try to guess missing settings as best as we can
test -z $ZIMBRA_HOSTNAME && ZIMBRA_HOSTNAME=`su - zimbra -c zmhostname`
test -z $ZIMBRA_ADDRESS  && ZIMBRA_ADDRESS=`grep $ZIMBRA_HOSTNAME /etc/hosts|awk '{print $1}'`
test -z $ZIMBRA_LDAPPASS && ZIMBRA_LDAPPASS=`su - zimbra -c "zmlocalconfig -s zimbra_ldap_password"|awk '{print $3}'`
if [ -z $ZIMBRA_BKPDIR ]; then
	test -d $ZIMBRA_DIR/backup && ZIMBRA_BKPDIR=$ZIMBRA_DIR/backup
	test -d /backup && ZIMBRA_BKPDIR=/backup
	test -d /opt/backup && ZIMBRA_BKPDIR=/opt/backup
fi

if [ -z $ZIMBRA_BKPDIR ]; then
	echo "No backup directory could be found! Please edit this script and declare it manually."
	exit $ERR_NOBKPDIR
fi

clear
echo "This will install zmbackup, a script aimed at creating backups for ZCS Community Edition."
read -p "What is the password for Zimbra's \"admin\" user? " ZIMBRA_ADMPASS
echo ""
echo "Here is a Summary of your settings:"
echo ""
echo "Zimbra User: $ZIMBRA_USER"
echo "Zimbra Hostname: $ZIMBRA_HOSTNAME"
echo "Zimbra IP Address: $ZIMBRA_ADDRESS"
echo "Zimbra LDAP Password: $ZIMBRA_LDAPPASS"
echo "Zimbra Admin Password: $ZIMBRA_ADMPASS"
echo "Zimbra Install Directory: $ZIMBRA_DIR"
echo "Zimbra Backup Directory: $ZIMBRA_BKPDIR"
echo "zmbackup Install Directory: $OSE_SRC"
echo "zmbackup Settings Directory: $OSE_CONF"
echo ""
echo "Press ENTER to continue or CTRL+C to cancel."
read tmp

# Check if we have root before doing anything
if [ $(id -u) -ne 0 ]; then
	echo "You need root privileges to install zmbackup"
	exit $ERR_NOROOT
fi

# Check for missing installer files
# TODO: MD5 check of the files
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

# Check for missing dependencies
STATUS=0
echo "Checking system for dependencies..."

## Zimbra Mailbox
printf "	ZCS Mailbox Control...	"
su - $ZIMBRA_USER -c "which zmmailboxdctl" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## LDAP utils
printf "	ldapsearch...	"
su - $ZIMBRA_USER -c "which ldapsearch" > /dev/null 2>&1
if [ $? = 0 ]; then
	printf "[OK]\n"
else
	printf "[NOT FOUND]\n"
	STATUS=$ERR_DEPNOTFOUND
fi

## Curl
printf "	curl...		"
su - $ZIMBRA_USER -c "which curl" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## mktemp
printf "	mktemp...	"
su - $ZIMBRA_USER -c "which mktemp" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## date
printf "	date...		"
su - $ZIMBRA_USER -c "which date" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## egrep
printf "	egrep...	"
su - $ZIMBRA_USER -c "which egrep" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## egrep
printf "	wget...	"
su - $ZIMBRA_USER -c "which wget" > /dev/null 2>&1
if [ $? = 0 ]; then
        printf "[OK]\n"
else
        printf "[NOT FOUND]\n"
        STATUS=$ERR_DEPNOTFOUND
fi

## egrep
printf "	parallel...	"
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

echo "Installing..."

# Create directories if needed
test -d $OSE_CONF || mkdir -p $OSE_CONF
test -d $OSE_SRC  || mkdir -p $OSE_SRC

# Copy files
install -o $ZIMBRA_USER -m 700 $MYDIR/src/zmbackup $OSE_SRC
install --backup=numbered -o $ZIMBRA_USER -m 600 $MYDIR/etc/zmbackup.conf $OSE_CONF

# Add custom settings
sed -i "s|{ZIMBRA_BKPDIR}|${ZIMBRA_BKPDIR}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{ZIMBRA_ADDRESS}|${ZIMBRA_ADDRESS}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{ZIMBRA_ADMINPASS}|${ZIMBRA_ADMPASS}|g" $OSE_CONF/zmbackup.conf
sed -i "s|{ZIMBRA_LDAPPASS}|${ZIMBRA_LDAPPASS}|g" $OSE_CONF/zmbackup.conf

# Fix backup dir permissions (owner MUST be $ZIMBRA_USER)
chown $ZIMBRA_USER $ZIMBRA_BKPDIR

# We're done!
read -p "Install completed. Do you want to display the README file? (Y/n)" tmp
case "$tmp" in
	y|Y|Yes|"") less $MYDIR/README;;
	*) echo "Done!";;
esac

exit $ERR_OK
