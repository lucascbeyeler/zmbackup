#!/bin/bash
################################################################################
# Backup Session - LDAP/Mailbox/DistList/Alias
################################################################################

################################################################################
# backup_ldp: Backup only LDAP entries (LDAP/DISTLIST/ALIAS
# Options:
#    $1 - The list of accounts to be backed up
#    $2 - The type of object should be backed up. Valid values:
#        DLOBJECT - Distribution List;
#        ACOBJECT - User Account;
#        ALOBJECT - Alias;
#    $3 - The filter used by LDAP to search for a type of object. Valid values:
#        DLFILTER - Distribution List (Use together with DLOBJECT);
#        ACFILTER - User Account (Use together with ACOBJECT);
#        ALFILTER - Alias (Use together with ALOBJECT).
################################################################################
backup_ldp()
{
  # Create a list of all accounts to be backed up
  if [ -z $1 ]; then
    build_listBKP $2 $3
  else
    for i in $(echo "$1" | sed 's/,/\n/g'); do
      echo $i >> $TEMPACCOUNT
    done
  fi

  # If $TEMPACCOUNT is not empty, do a backup, if not do nothing
  if [ -s $TEMPACCOUNT ]; then
    export ldap_backup
    notify_begin $SESSION $STYPE
    logger -i --id=$$ -p local7.info "Zmbackup: Backup session $SESSION started on $(date)"
    echo "SESSION: $SESSION started on $(date)" >> $TEMPSESSION
    cat $TEMPACCOUNT | parallel --no-notice --env $2 --jobs $MAX_PARALLEL_PROCESS \
                                'ldap_backup {} "$2"'
    echo "SESSION: $SESSION completed in $(date)" >> $TEMPSESSION
    mv "$TEMPDIR" "$WORKDIR/$SESSION" && rm -rf "$TEMPDIR"
    cat $TEMPSESSION >> $WORKDIR/sessions.txt
    logger -i --id=$$ -p local7.info "Zmbackup: Backup session $SESSION finished on $(date)"
  else
    echo "Nothing to do. Closing..."
    exit 2
  fi
}
