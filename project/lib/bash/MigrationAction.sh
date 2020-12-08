#!/bin/bash
################################################################################
# Database Actions - Sqlite Drive
################################################################################

###############################################################################
# create_session: Migrate the entire sessions.txt to SQLite database
###############################################################################
function create_session(){
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    touch "$WORKDIR"/sessions.txt
    echo "Session file TXT recreated"
  elif [[ $SESSION_TYPE == "SQLITE3" ]]; then
    sqlite3 "$WORKDIR"/sessions.sqlite3 < /usr/local/lib/zmbackup/sqlite3/database.sql
    echo "Session file SQLITE3 recreated"
  else
    echo "Invalid File Format - Nothing to do."
  fi
}

###############################################################################
# importsessionSQL: Migrate the sessions from the txt file to the sqlite3 database
###############################################################################
function importsessionSQL(){
  for i in $(grep -E 'SESSION:' "$WORKDIR"/sessions.txt | grep 'started' |  awk '{print $2}' | sort | uniq); do
    SESSIONID=$i
    OPT=$(echo "$i" | cut -d"-" -f1 )
    case $OPT in
      "full")
          OPT="Full Backup"
          YEAR=$(echo "$i" | cut -c6-9)
          MONTH=$(echo "$i" | cut -c10-11)
          DAY=$(echo "$i" | cut -c12-13)
      ;;
      "inc")
          OPT="Incremental Backup"
          YEAR=$(echo "$i" | cut -c5-8)
          MONTH=$(echo "$i" | cut -c9-10)
          DAY=$(echo "$i" | cut -c11-12)
      ;;
      "distlist")
          OPT="Distribution List Backup"
          YEAR=$(echo "$i" | cut -c10-13)
          MONTH=$(echo "$i" | cut -c14-15)
          DAY=$(echo "$i" | cut -c16-17)
      ;;
      "alias")
          OPT="Alias Backup"
          YEAR=$(echo "$i" | cut -c7-10)
          MONTH=$(echo "$i" | cut -c11-12)
          DAY=$(echo "$i" | cut -c13-14)
      ;;
      "ldap")
          OPT="Account Backup - Only LDAP"
          YEAR=$(echo "$i" | cut -c6-9)
          MONTH=$(echo "$i" | cut -c10-11)
          DAY=$(echo "$i" | cut -c12-13)
      ;;
    esac
    INITIAL=$YEAR'-'$MONTH'-'$DAY"T00:00:00.000"
    CONCLUSION=$YEAR'-'$MONTH'-'$DAY"T00:00:00.000"
    SIZE=$(du -ch "$WORKDIR"/"$i" | grep total | awk '{print $1}')
    STATUS="FINISHED"
    sqlite3 "$WORKDIR"/sessions.sqlite3 "insert into backup_session values ('$SESSIONID',\
                                       '$INITIAL','$CONCLUSION','$SIZE','$OPT','$STATUS')"
  done
}

###############################################################################
# importaccountsSQL: Migrate the accounts from the txt file to the sqlite3 database
###############################################################################
function importaccountsSQL(){
  for i in $(grep -E 'SESSION:' "$WORKDIR"/sessions.txt | grep 'started' |  awk '{print $2}' | sort | uniq); do
    DATE=$(sqlite3 "$WORKDIR"/sessions.sqlite3 "select conclusion_date from backup_session where sessionID='$i'")
    for j in $(grep -E "$i" "$WORKDIR"/sessions.txt | grep -v 'SESSION:' | sort | uniq); do
      EMAIL=$(echo "$j" | cut -d":" -f2)
      SIZE=$(du -ch "$WORKDIR"/"$i"/"$EMAIL"* | grep total | awk '{print $1}')
      sqlite3 "$WORKDIR"/sessions.sqlite3 "insert into backup_account (email,sessionID,\
                                         account_size,initial_date, conclusion_date) \
                                         values ('$EMAIL','$i','$SIZE','$DATE','$DATE')" > /dev/null
    done
  done
}

###############################################################################
# importaccountsTXT: Migrate the accounts from the txt file to the sqlite3 database
###############################################################################
function importsessionTXT(){
  sqlite3 "$WORKDIR"/sessions.sqlite3 "select sessionID,conclusion_date from backup_session" | while read -r SESSION; do
    MONTH=$(echo "$i" | cut -d'|' -f2 | cut -d'-' -f2)
    DAY=$(echo "$i" | cut -d'|' -f2 | cut -d'-' -f3 | cut -d'T' -f1)
    YEAR=$(echo "$i" | cut -d'|' -f2 | cut -d'-' -f1)
    HOUR=$(echo "$i" | cut -d'|' -f2 | cut -d'-' -f3 | cut -d'T' -f2)
    MINUTE=$(echo "$i" | cut -d'|' -f2 | cut -d'-' -f3 | cut -d':' -f2)
    echo "SESSION: $SESSION started on $(date -d "$MONTH/$DAY/$YEAR $HOUR:$MINUTE")" >> "$WORKDIR"/sessions.txt
    sqlite3 "$WORKDIR"/sessions.sqlite3 "select email from backup_account where sessionID='$SESSION'" | while read -r SESSION; do
      echo "$SESSION:$ACCOUNT:$MONTH/$DAY/$YEAR" >> "$WORKDIR"/sessions.txt
    done
  done
}

###############################################################################
# migration: Execute migration action
###############################################################################
function migration(){
  echo "Starting the migration - please wait until the conclusion"
  create_session
  if [[ $SESSION_TYPE == "SQLITE3" ]]; then
    importsessionSQL
    importaccountsSQL
    rm "$WORKDIR"/sessions.txt
  elif [[ $SESSION_TYPE == "TXT" ]]; then
    importsessionTXT
    rm "$WORKDIR"/sessions.sqlite3
  else
    echo "Nothing to do."
  fi
  echo "Migration completed"
}
