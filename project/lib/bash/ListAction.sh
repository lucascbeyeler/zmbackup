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
#        SIOBJECT - Signature;
#    $2 - The filter used by LDAP to search for a type of object. Valid values:
#        DLFILTER - Distribution List (Use together with DLOBJECT);
#        ACFILTER - User Account (Use together with ACOBJECT);
#        ALFILTER - Alias (Use together with ALOBJECT).
#        SOFILTER - Signature (Use together with SIOBJECT).
#    $3 - Enable backup per domain
#    $4 - The list of domains to be backed up
################################################################################
function build_listBKP()
{
  if [ "$3" == "-d" ]; then
    for i in ${4//s/\n/g}; do
      DC=",dc="
      DOMAIN="dc="${i//./$DC}
      ERR=$( (ldapsearch -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" -w "$LDAPPASS" -b "$DOMAIN" -LLL "$1" "$2" >> "$TEMPACCOUNT") 2>&1)
      BASHERRCODE=$?
      if [[ $BASHERRCODE -eq 0 ]]; then
        echo "Domain $i found! - Inserting inside the backup queue."
        logger -i -p local7.info "Domain $i found! - Inserting inside the backup queue."
      else
        logger -i -p local7.err "Zmbackup: LDAP - Can't extract accounts from LDAP - Error below:"
        logger -i -p local7.err "Zmbackup: $ERR"
        echo "ERROR - Can't extract accounts from LDAP - See log for more information"
        exit 1
      fi
    done
  else
    ERR=$( (ldapsearch -Z -x -H "$LDAPSERVER" -D "$LDAPADMIN" -w "$LDAPPASS" -b '' -LLL "$1" "$2" >> "$TEMPACCOUNT") 2>&1)
    BASHERRCODE=$?
    if [[ $BASHERRCODE -ne 0 ]]; then
      logger -i -p local7.err "Zmbackup: LDAP - Can't extract accounts from LDAP - Error below:"
      logger -i -p local7.err "Zmbackup: $ERR"
      echo "ERROR - Can't extract accounts from LDAP - See log for more information"
    fi
  fi
  grep "^$2" "$TEMPACCOUNT" | awk '{print $2}' > "$TEMPINACCOUNT"
  truncate --size 0 "$TEMPACCOUNT"
  parallel --jobs "$MAX_PARALLEL_PROCESS" "ldap_filter '{}'" < "$TEMPINACCOUNT"
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
    for i in ${2//s/\n/g}; do
      echo "$i" >> "$TEMPACCOUNT"
    done
  else
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      grep "$1:" "$WORKDIR"/sessions.txt | grep -v "SESSION" | cut -d: -f2 > "$TEMPACCOUNT"
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      sqlite3 "$WORKDIR"/sessions.sqlite3 "select email from backup_account where sessionID='$1'" > "$TEMPACCOUNT"
    fi
  fi
}
