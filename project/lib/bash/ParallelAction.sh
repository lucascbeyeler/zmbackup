#!/bin/bash
################################################################################
# Repeatable Actions
################################################################################

###############################################################################
# ldap_backup: Backup a LDAP object inside a file.
# Options:
# $1 - The object's mail account that should be backed up;
# $2 - The type of object should be backed up. Valid values:
#     DLOBJECT - Distribution List;
#     ACOBJECT - User Account;
#     ALOBJECT - Alias.
###############################################################################
function ldap_backup()
{
  ERR=$((ldapsearch -x -H $LDAPSERVER -D $LDAPADMIN -w $LDAPPASS -b '' \
             -LLL "(&(|(mail=$1)(uid=$1))$2)" > $TEMPDIR/$1.ldiff)2>&1)
  if [[ $? -eq 0 ]]; then
    logger -i -p local7.info "Zmbackup: LDAP - Backup for account $1 finished."
    export ERRCODE=0
  else
    logger -i -p local7.err "Zmbackup: LDAP - Backup for account $1 failed. Error message below:"
    logger -i -p local7.err "Zmbackup: $1 - $ERR"
    export ERRCODE=1
  fi
}


###############################################################################
# mailbox_backup: Backup user's mailbox in TGZ format.
# Options:
# $1 - The user's account to be backed up;
###############################################################################
function mailbox_backup()
{
  if [[ "$INC" == "TRUE" ]]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      DATE=$(grep $1 $WORKDIR/sessions.txt | tail -1 | awk -F: '{print $3}' | cut -d'-' -f2)
    elif [[ $SESSION_TYPE == 'SQLITE3' ]]; then
      DATE=$(sqlite3 $WORKDIR/sessions.sqlite3 "select MAX(initial_date) \
             from backup_account where email='$1' and \
             (sessionID like 'full%' or sessionID like 'inc%' or sessionID like 'mbox%')")
    fi
    AFTER='&'"start="$(date -d $DATE +%s)"000"
  fi
  ERR=$((wget --timeout=5 --tries=2 -O $TEMPDIR/$1.tgz --user $ADMINUSER --password $ADMINPASS \
        "https://$MAILHOST:7071/home/$1/?fmt=tgz$AFTER" --no-check-certificate) 2>&1)
  if [[ $? -eq 0 || "$ERR" == *"204 No data found"* ]]; then
    if [[ -s $TEMPDIR/$1.tgz ]]; then
      logger -i -p local7.info "Zmbackup: Mailbox - Backup for account $1 finished."
    else
      logger -i -p local7.info "Zmbackup: Mailbox - Backup for account $1 finished, but the file is empty. Removing..."
      rm -rf $TEMPDIR/$1.tgz
    fi 
    export ERRCODE=0
  else
    logger -i -p local7.err "Zmbackup: Mailbox - Backup for account $1 failed. Error message below:"
    logger -i -p local7.err "Zmbackup: $1 - $ERR"
    export ERRCODE=1
  fi
}


###############################################################################
# ldap_restore: Restore a LDAP object inside a file.
# Options:
# $1 - The session file to be restored;
# $2 - The account that should be restored.
###############################################################################
function ldap_restore()
{
  ldapdelete -r -x -H $LDAPSERVER -D $LDAPADMIN -c -w $LDAPPASS \
    $(grep ^dn: $WORKDIR/$1/$2.ldiff | awk '{print $2}') > /dev/null 2>&1
  ERR=$((ldapadd -x -H $LDAPSERVER -D $LDAPADMIN \
           -c -w $LDAPPASS -f $WORKDIR/$1/$2.ldiff) 2>&1)
  if ! [[ $? -eq 0 ]]; then
    printf "\nError during the restore process for account $2. Error message below:"
    printf "\n$2: $ERR"
  fi
}

###############################################################################
# ldap_restore: Restore a LDAP object inside a file.
# Options:
# $1 - The session file to be restored;
# $2 - The account that should be restored.
###############################################################################
function mailbox_restore()
{
  ERR=$((http --check-status --verify=no POST "https://$MAILHOST:7071/home/$2/?fmt=tgz"\
       -a "$ADMINUSER":"$ADMINPASS" < $WORKDIR/$1/$2.tgz) 2>&1)
  if ! [[ $? -eq 0 ]]; then
    printf "\nError during the restore process for account $2. Error message below:"
    printf "\n$2: $ERR"
  elif [[ "$ERR"  == *"No such file or directory" ]]; then
    printf "\nAccount $2 has nothing to restore - skipping..."
  fi
}


###############################################################################
# ldap_filter: Filter the account to see if you should do backup or not for that
#              account.
# Options:
# $1 - The email account to be validated.
###############################################################################
function ldap_filter()
{
  EXIST=$(grep $1 $WORKDIR/sessions.txt 2> /dev/null | tail -1 | awk -F: '{print $3}')
  grep -Fxq $1 /etc/zmbackup/blacklist.conf
  if [[ $? -eq 0 ]]; then
    echo "WARN: $1 found inside blacklist - Nothing to do."
  elif [[ "$EXIST" = "$(date +%m/%d/%y)" && "$LOCK_BACKUP" == "true" ]]; then
    echo "WARN: $1 already has backup today. Nothing to do."
  else
    echo $1 >> $TEMPACCOUNT
  fi
}
