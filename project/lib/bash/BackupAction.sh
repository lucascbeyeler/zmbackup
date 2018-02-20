#!/bin/bash
################################################################################
# Backup Session - LDAP/Mailbox/DistList/Alias
################################################################################

################################################################################
# __backupFullInc: All the functions used by backup Full and Incremental
# Options:
#    $1 - The account to be backed up
#    $2 - The type of object should be backed up. Valid values:
#        ACOBJECT - User Account;
################################################################################
function __backupFullInc(){
  SDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
  ldap_backup $1 $2
  if [ $ERRCODE -eq 0 ]; then
    mailbox_backup $1
    if [ $ERRCODE -eq 0 ]; then
      if [[ $SESSION_TYPE == 'TXT' ]]; then
        echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
      elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
        EDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
        SIZE=$(du -ch $WORKDIR/$i* | grep total | cut -f1)
        echo "insert into backup_account (email,sessionID,account_size, initial_date, \
              conclusion_date) values ('$1','$SESSION','$SIZE','$SDATE','$EDATE');"  >> $TEMPSQL
      fi
    fi
  fi
  exit $ERRCODE
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
  SDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
  ldap_backup $1 $2
  if [ $ERRCODE -eq 0 ]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      EDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      SIZE=$(du -ch $WORKDIR/$i* | grep total | cut -f1)
      echo "insert into backup_account (email,sessionID,account_size, initial_date, \
            conclusion_date) values ('$1','$SESSION','$SIZE','$SDATE','$EDATE');"  >> $TEMPSQL
    fi
  fi
  exit $ERRCODE
}

################################################################################
# __backupMailbox: All the functions used by mailbox backup
# Options:
#    $1 - The list of accounts to be backed up
#    $2 - The type of object should be backed up. Valid values:
#        ACOBJECT - User Account;
################################################################################
function __backupMailbox(){
  SDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
  mailbox_backup $1 $2
  if [ $ERRCODE -eq 0 ]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo $SESSION:$1:$(date +%m/%d/%y) >> $TEMPSESSION
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      EDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      SIZE=$(du -ch $WORKDIR/$i* | grep total | cut -f1)
      echo "insert into backup_account (email,sessionID,account_size, initial_date, \
            conclusion_date) values ('$1','$SESSION','$SIZE','$SDATE','$EDATE');"  >> $TEMPSQL
    fi
  fi
  exit $ERRCODE
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

  # If $TEMPACCOUNT is not empty, do a backup, if is do nothing
  if [ -s $TEMPACCOUNT ]; then
    notify_begin $SESSION $STYPE
    logger -i -p local7.info "Zmbackup: Backup session $SESSION started on $(date)"
    echo "Backup session $SESSION started on $(date)"
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo "SESSION: $SESSION started on $(date)" >> $TEMPSESSION
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      DATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      sqlite3 $WORKDIR/sessions.sqlite3 "insert into backup_session(sessionID,\
                                         initial_date,type,status) values \
                                         ('$SESSION','$DATE','$STYPE','IN PROGRESS')" > /dev/null 2>&1
    fi
    cat $TEMPACCOUNT
    if [[ "$SESSION" == "full"* ]] || [[ "$SESSION" == "inc"* ]]; then
      cat $TEMPACCOUNT | parallel --jobs $MAX_PARALLEL_PROCESS \
                         '__backupFullInc {} $1'
      ERRCODE=$?
      if [ $ERRCODE -gt 0 ]; then
        exit_code=$ERRCODE
      fi
    elif [[ "$SESSION" == "mbox"* ]]; then
      cat $TEMPACCOUNT | parallel --jobs $MAX_PARALLEL_PROCESS \
                         '__backupMailbox {} $1'
      ERRCODE=$?
      if [ $ERRCODE -gt 0 ]; then
        exit_code=$ERRCODE
      fi
    else
      cat $TEMPACCOUNT | parallel --jobs $MAX_PARALLEL_PROCESS \
                         '__backupLdap {} $1'
      ERRCODE=$?
      if [ $ERRCODE -gt 0 ]; then
        exit_code=$ERRCODE
      fi
    fi
    mv "$TEMPDIR" "$WORKDIR/$SESSION" && rm -rf "$TEMPDIR"
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo "SESSION: $SESSION completed in $(date)" >> $TEMPSESSION
      cat $TEMPSESSION >> $WORKDIR/sessions.txt
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      DATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      SIZE=$(du -sh $WORKDIR/$SESSION | awk {'print $1'})
      sqlite3 $WORKDIR/sessions.sqlite3 < $TEMPSQL > /dev/null 2>&1
      sqlite3 $WORKDIR/sessions.sqlite3 "update backup_session set conclusion_date='$DATE',\
                                         size='$SIZE',status='FINISHED' where \
                                         sessionID='$SESSION'" > /dev/null 2>&1
    fi
    logger -i -p local7.info "Zmbackup: Backup session $SESSION finished on $(date)"
    echo "Backup session $SESSION finished on $(date)"
    export exit_code=$exit_code
  else
    echo "Nothing to do. Closing..."
    rm -rf $PID
    exit 4
  fi
}
