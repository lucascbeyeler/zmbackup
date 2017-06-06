#!/bin/bash
################################################################################
# Backup Session - LDAP/Mailbox/DistList/Alias
################################################################################

################################################################################
# __backupFullInc: All the functions used by backup Full and Incremental
# Options:
#    $1 - The list of accounts to be backed up
#    $2 - The type of object should be backed up. Valid values:
#        ACOBJECT - User Account;
################################################################################
function __backupFullInc(){
  ldap_backup $1 $2
  if [ $ERROR_CODE -eq 0 ]; then
    mailbox_backup $1 $2
    if [ $ERROR_CODE -eq 0 ]; then
      echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
    fi
  fi
}

################################################################################
# __backupLdap: All the functions used by LDAP, distribution list, and alias backup
# Options:
#    $1 - The list of accounts to be backed up
#    $2 - The type of object should be backed up. Valid values:
#        DLOBJECT - Distribution List;
#        ACOBJECT - User Account;
#        ALOBJECT - Alias;
################################################################################
function __backupLdap(){
  ldap_backup $1 $2
  if [ $ERROR_CODE -eq 0 ]; then
    echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
  fi
}

################################################################################
# __backupMailbox: All the functions used by mailbox backup
# Options:
#    $1 - The list of accounts to be backed up
#    $2 - The type of object should be backed up. Valid values:
#        ACOBJECT - User Account;
################################################################################
function __backupMailbox(){
  mailbox_backup $1 $2
  if [ $ERROR_CODE -eq 0 ]; then
    echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
  fi
}

################################################################################
# backup_main: Backup accounts based on SESSION and STYPE
# Options:
#    $1 - The type of object should be backed up. Valid values:
#        DLOBJECT - Distribution List;
#        ACOBJECT - User Account;
#        ALOBJECT - Alias;
#    $2 - The filter used by LDAP to search for a type of object. Valid values:
#        DLFILTER - Distribution List (Use together with DLOBJECT);
#        ACFILTER - User Account (Use together with ACOBJECT);
#        ALFILTER - Alias (Use together with ALOBJECT).
#    $3 - The list of accounts to be backed up
################################################################################
function backup_main()
{
  # Create a list of all accounts to be backed up
  if [ -z $3 ]; then
    build_listBKP $1 $2
  else
    for i in $(echo "$3" | sed 's/,/\n/g'); do
      echo $i >> $TEMPACCOUNT
    done
  fi

  # If $TEMPACCOUNT is not empty, do a backup, if not do nothing
  if [ -s $TEMPACCOUNT ]; then
    notify_begin $SESSION $STYPE
    logger -i --id=$$ -p local7.info "Zmbackup: Backup session $SESSION started on $(date)"
    echo "SESSION: $SESSION started on $(date)" >> $TEMPSESSION
    if [ $SESSION == "full*" ] || [ $SESSION == "inc*" ]; then
      export -f __backupFullInc
      cat $TEMPACCOUNT | parallel --no-notice --jobs $MAX_PARALLEL_PROCESS \
                         '__backupFullInc {} $1'
    elif [ $SESSION == "mbox*" ]; then
      export -f __backupMailbox
      cat $TEMPACCOUNT | parallel --no-notice --jobs $MAX_PARALLEL_PROCESS \
                         '__backupMailbox {} $1'
    else
      export -f __backupLdap
      cat $TEMPACCOUNT | parallel --no-notice --jobs $MAX_PARALLEL_PROCESS \
                         '__backupLdap {} $1'
    fi
    echo "SESSION: $SESSION completed in $(date)" >> $TEMPSESSION
    mv "$TEMPDIR" "$WORKDIR/$SESSION" && rm -rf "$TEMPDIR"
    cat $TEMPSESSION >> $WORKDIR/sessions.txt
    logger -i --id=$$ -p local7.info "Zmbackup: Backup session $SESSION finished on $(date)"
  else
    echo "Nothing to do. Closing..."
    exit 2
  fi
}
