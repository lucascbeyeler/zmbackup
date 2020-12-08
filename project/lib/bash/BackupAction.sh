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
  ldap_backup "$1" "$2"
  if [ "$ERRCODE" -eq 0 ]; then
    mailbox_backup "$1"
    if [ "$ERRCODE" -eq 0 ]; then
      if [[ $SESSION_TYPE == 'TXT' ]]; then
        echo "$SESSION":"$1":"$(date +%m/%d/%y)" >> "$TEMPSESSION"
      elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
        EDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
        SIZE=$(du -ch "$TEMPDIR"/"$1"* | grep total | cut -f1)
        echo "insert into backup_account (email,sessionID,account_size, initial_date, \
              conclusion_date) values ('$1','$SESSION','$SIZE','$SDATE','$EDATE');"  >> "$TEMPSQL"
      fi
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
#        SIOBJECT - Signature;
################################################################################
function __backupLdap(){
  SDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
  ldap_backup "$1" "$2"
  if [ "$ERRCODE" -eq 0 ]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo "$SESSION":"$1":"$(date +%m/%d/%y)" >> "$TEMPSESSION"
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      EDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      SIZE=$(du -ch "$TEMPDIR"/"$1"* | grep total | cut -f1)
      echo "insert into backup_account (email,sessionID,account_size, initial_date, \
            conclusion_date) values ('$1','$SESSION','$SIZE','$SDATE','$EDATE');"  >> "$TEMPSQL"
    fi
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
  SDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
  mailbox_backup "$1" "$2"
  if [ "$ERRCODE" -eq 0 ]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo "$SESSION":"$1":"$(date +%m/%d/%y)" >> "$TEMPSESSION"
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      EDATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      SIZE=$(du -ch "$TEMPDIR"/"$1"* | grep total | cut -f1)
      echo "insert into backup_account (email,sessionID,account_size, initial_date, \
            conclusion_date) values ('$1','$SESSION','$SIZE','$SDATE','$EDATE');"  >> "$TEMPSQL"
    fi
  fi
}

################################################################################
# backup_main: Backup accounts based on SESSION and STYPE
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
#        SIFILTER - Alias (Use together with SIOBJECT).
#    $3 - Enable backup per account/domain
#    $4 - The list of accounts/domains to be backed up
################################################################################
function backup_main()
{
  # Create a list of all accounts to be backed up
  if [[ -z $4 ]] || [[ "$3" == "-d" ]] || [[ "$3" == "--domain" ]]; then
    build_listBKP "$1" "$2" "$3" "$4"
  elif  [[ "$3" == "-a" ]] || [[ "$3" == "--account" ]]; then
    for i in $("$4//,/\n/g"); do
      echo "$i" >> "$TEMPACCOUNT"
    done
  else
    echo "ERROR - Option $3 is not valid"
    rm -rf "$PID"
    exit 5
  fi

  # If $TEMPACCOUNT is not empty, do a backup, if is do nothing
  if [ -s "$TEMPACCOUNT" ]; then
    notify_begin "$SESSION" "$STYPE"
    logger -i -p local7.info "Zmbackup: Backup session $SESSION started on $(date)"
    echo "Backup session $SESSION started on $(date)"
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo "SESSION: $SESSION started on $(date)" >> "$TEMPSESSION"
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      DATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      sqlite3 "$WORKDIR"/sessions.sqlite3 "insert into backup_session(sessionID,\
                                         initial_date,type,status) values \
                                         ('$SESSION','$DATE','$STYPE','IN PROGRESS')" > /dev/null 2>&1
    fi
    if [[ "$SESSION" == "full"* ]] || [[ "$SESSION" == "inc"* ]]; then
      parallel --jobs "$MAX_PARALLEL_PROCESS" "__backupFullInc '{}' '$1'" < "$TEMPACCOUNT"
    elif [[ "$SESSION" == "mbox"* ]]; then
      parallel --jobs "$MAX_PARALLEL_PROCESS" "__backupMailbox '{}' '$1'" < "$TEMPACCOUNT"
    else
      parallel --jobs "$MAX_PARALLEL_PROCESS" "__backupLdap '{}' '$1'" < "$TEMPACCOUNT"
    fi
    mv "$TEMPDIR" "$WORKDIR/$SESSION" && rm -rf "$TEMPDIR"
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      echo "SESSION: $SESSION completed in $(date)" >> "$TEMPSESSION"
      cat "$TEMPSESSION" >> "$WORKDIR"/sessions.txt
    elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
      chmod -R 775 "$WORKDIR"/"$SESSION"
      DATE=$(date +%Y-%m-%dT%H:%M:%S.%N)
      SIZE=$(du -sh "$WORKDIR"/"$SESSION" | awk '{print $1}')
      sqlite3 "$WORKDIR"/sessions.sqlite3 < "$TEMPSQL" > /dev/null 2>&1
      sqlite3 "$WORKDIR"/sessions.sqlite3 "update backup_session set conclusion_date='$DATE',\
                                         size='$SIZE',status='FINISHED' where \
                                         sessionID='$SESSION'" > /dev/null 2>&1
    fi
    logger -i -p local7.info "Zmbackup: Backup session $SESSION finished on $(date)"
    echo "Backup session $SESSION finished on $(date)"
  else
    echo "Nothing to do. Closing..."
    rm -rf "$PID"
  fi
}
