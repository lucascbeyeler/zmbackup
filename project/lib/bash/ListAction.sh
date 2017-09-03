#!/bin/bash
################################################################################
# Ldap Build List - No ldapadd or ldapdelete here
################################################################################

################################################################################
# build_listBKP: Build the list of accounts to be extracted via LDAP &/or Mailbox
# Options:
#    $1 - The type of object should be backed up. Valid values:
#        DLOBJECT - Distribution List;
#        ACOBJECT - User Account;
#        ALOBJECT - Alias;
#    $2 - The filter used by LDAP to search for a type of object. Valid values:
#        DLFILTER - Distribution List (Use together with DLOBJECT);
#        ACFILTER - User Account (Use together with ACOBJECT);
#        ALFILTER - Alias (Use together with ALOBJECT).
################################################################################
function build_listBKP()
{
  ERR=$((ldapsearch -x -H $LDAPSERVER -D $LDAPADMIN -w $LDAPPASS -b '' \
                -LLL "$1" $2 | grep "^$2" | awk '{print $2}' > $TEMPINCACCOUNT) 2>&1)
  if [[ $? -eq 0 ]]; then
    cat $TEMPINCACCOUNT | parallel --jobs $MAX_PARALLEL_PROCESS 'ldap_filter {}'
  else
    logger -i -p local7.err "Zmbackup: LDAP - Can't extract accounts from LDAP - Error below:"
    logger -i -p local7.err "Zmbackup: $ERR"
    echo "ERROR - Can't extract accounts from LDAP - See log for more information"
    exit 1
  fi
}


################################################################################
# build_listRST: Build the list of accounts to be restored via LDAP &/or Mailbox
# Options:
#    $1 - The session to be restored;
#    $2 - The list of accounts to be restored.
################################################################################
function build_listRST()
{
  if [[ $2 == *"@"* ]]; then
    for i in $(echo "$2" | sed 's/,/\n/g'); do
      echo $i >> $TEMPACCOUNT
    done
  else
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      grep "$1:" $WORKDIR/sessions.txt | grep -v "SESSION" | cut -d: -f2 > $TEMPACCOUNT
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      sqlite3 $WORKDIR/sessions.sqlite3 "select email from backup_account where sessionID='$1'" > $TEMPACCOUNT
    fi
  fi
}
