#!/bin/bash
################################################################################
# Repeatable Actions
################################################################################

###############################################################################
# ldap_backup: Backup a LDAP object inside a file
# Options:
# $1 - The object's mail account that should be backed up;
# $2 - The type of object should be backed up. Valid values:
#     DLOBJECT - Distribution List
#     ACOBJECT - User Account
#     ALOBJECT - Alias
###############################################################################
ldap_backup()
{
  ERR=$((ldapsearch -x -H $LDAPSERVER -D $LDAPADMIN -w $LDAPPASS -b '' \
             -LLL "(&(|(mail=$1)(uid=$1))$2)" > $TEMPDIR/$1.ldiff)2>&1)
  if [[ $? -eq 0 ]]; then
    echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
    logger -i --id=$$ -p local7.info "Zmbackup: LDAP - Backup for account $1 finished."
  else
    logger -i --id=$$ -p local7.err "Zmbackup: LDAP - Backup for account $1 failed. Error message below:"
    logger -i --id=$$ -p local7.err "Zmbackup: $ERR"
  fi
}


###############################################################################
# mailbox_backup: Backup user's mailbox in TGZ format
# Options:
# $1 - The user's account to be backed up
# $2 - OPTIONAL: Inform that this session is a incremental backup
###############################################################################
mailbox_backup()
{
  if [ "$2" == "INC" ]; then
    AFTER="\&query=after:"$(grep $1 $WORKDIR/sessions.txt | tail -1 | awk -F: '{print $3}')
  fi
  ERR=$((wget --quiet -O $TEMPDIR/$1.tgz --http-user $ADMINUSER --http-passwd $ADMINPASS \
        "https://$MAILHOST:7071/home/$1/?fmt=tgz"$AFTER --no-check-certificate) 2>&1)
  if [[ $? -eq 0 ]]; then
    if [[ -z $TEMPDIR/$1.tgz ]]; then
      echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
      logger -i --id=$$ -p local7.info "Zmbackup: Mailbox - Backup for account $1 finished."
    else
      rm -rf $TEMPDIR/$1.tgz
      logger -i --id=$$ -p local7.info "Zmbackup: Mailbox - Backup for account $1 finished, but the file is empty. Removing..."
    fi
  else
    logger -i --id=$$ -p local7.err "Zmbackup: Mailbox - Backup for account $1 failed. Error message below:"
    logger -i --id=$$ -p local7.err "Zmbackup: $ERR"
  fi
}


###############################################################################
# ldap_restore: Restore a LDAP object inside a file
# Options:
# $1 - The session file to be restored
# $2 - The account that should be restored
###############################################################################
ldap_restore()
{
  ERR=$(ldapdelete -r -x -H $LDAPSERVER -D $LDAPADMIN -c -w $LDAPPASS \
    $(grep ^dn: $WORKDIR/$1/$2.ldiff | awk '{print $2}') 2>&1)
  if [[ $? -eq 0 ]]; then
    ldapadd -x -H $LDAPSERVER -D $LDAPADMIN \
             -c -w $LDAPPASS -f $WORKDIR/$1/$2.ldiff > /dev/null 2>&1
    echo "Account $2 restored with success"
  else
    echo "Error during the restore process for account $2. Error message below:"
    echo $ERR
    echo "======================================================================"
  fi
}
