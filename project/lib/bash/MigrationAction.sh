#!/bin/bash
################################################################################
# Database Actions - Sqlite Drive
################################################################################

###############################################################################
# create_session: Migrate the entire sessions.txt to SQLite database
###############################################################################
function create_session(){
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    touch $WORKDIR/sessions.txt
    echo "Session file TXT recreated"
  elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
    sqlite3 $WORKDIR/sessions.sqlite3 < /usr/local/lib/zmbackup/sqlite3/database.sql
    echo "Session file SQLITE3 recreated"
  else
    echo "Invalid File Format - Nothing to do."
  fi
}

###############################################################################
# importsession: Migrate the sessions from the txt file to the sqlite3 database
###############################################################################
function importsession(){
  for i in $(egrep 'SESSION:' $WORKDIR/sessions.txt | egrep 'started' |  awk '{print $2}' | sort | uniq); do
    SESSIONID=$i
    OPT=$(echo $i | cut -d"-" -f1 )
    case $OPT in
      "full")
          OPT="Full Backup"
          YEAR=$(echo $i | cut -c6-9)
          MONTH=$(echo $i | cut -c10-11)
          DAY=$(echo $i | cut -c12-13)
      ;;
      "inc")
          OPT="Incremental Backup"
          YEAR=$(echo $i | cut -c5-8)
          MONTH=$(echo $i | cut -c9-10)
          DAY=$(echo $i | cut -c11-12)
      ;;
      "distlist")
          OPT="Distribution List Backup"
          YEAR=$(echo $i | cut -c10-13)
          MONTH=$(echo $i | cut -c14-15)
          DAY=$(echo $i | cut -c16-17)
      ;;
      "alias")
          OPT="Alias Backup"
          YEAR=$(echo $i | cut -c7-10)
          MONTH=$(echo $i | cut -c11-12)
          DAY=$(echo $i | cut -c13-14)
      ;;
      "ldap")
          OPT="Account Backup - Only LDAP"
          YEAR=$(echo $i | cut -c6-9)
          MONTH=$(echo $i | cut -c10-11)
          DAY=$(echo $i | cut -c12-13)
      ;;
    esac
    INITIAL=$YEAR'-'$MONTH'-'$DAY"T00:00:00.000"
    CONCLUSION=$YEAR'-'$MONTH'-'$DAY"T00:00:00.000"
    SIZE=$(du -h $WORKDIR/$i | awk {'print $1'})
    STATUS="FINISHED"
    sqlite3 sessions.sqlite3 "insert into backup_session values ('$SESSIONID','$INITIAL','$CONCLUSION','$SIZE','$OPT','$STATUS')"
  done
}

###############################################################################
# importaccounts: Migrate the accounts from the txt file to the sqlite3 database
###############################################################################
function importaccounts(){
  for i in $(egrep 'SESSION:' $WORKDIR/sessions.txt | egrep 'started' |  awk '{print $2}' | sort | uniq); do
    SESSIONID=$i
    for j in $(egrep $i $WORKDIR/sessions.txt | grep -v 'SESSION:' | sort | uniq); do
      EMAIL=$(echo $j | cut -d":" -f2)
      SIZE=$(du -h $WORKDIR/$i/$EMAIL.tgz | awk {'print $1'})
      sqlite3 sessions.sqlite3 "insert into backup_account (email) values ('$EMAIL')" > /dev/null
      ID=$(sqlite3 sessions.sqlite3 "select accountID from backup_account where email='$EMAIL'")
      sqlite3 sessions.sqlite3 "insert into session_account (accountID,sessionID,account_size) values ('$ID','$SESSIONID','$SIZE')" > /dev/null
    done
  done
}

###############################################################################
# migration: Execute migration action
###############################################################################
function migration(){
  if [[ $SESSION_TYPE == "SQLITE3" ]] && ! [[ -f $WORKDIR/sessions.sqlite3 ]]; then
    echo "Starting the migration - please wait until the conclusion"
    create_session
    importsession
    importaccounts
    echo "Migration completed"
    rm $WORKDIR/sessions.txt
  elif [[ $SESSION_TYPE == "TXT" ]] && ! [[ -f $WORKDIR/sessions.txt ]]; then
    create_session
    rm $WORKDIR/sessions.sqlite3
  else
    echo "Nothing to do."
  fi
}
