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
    sqlite3 "$WORKDIR"/sessions.sqlite3 ".read /usr/local/lib/zmbackup/sqlite3/database.sql"
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
    parse_session_name "$i"
    case $OPT in
      "full")      OPT="Full Backup" ;;
      "inc")       OPT="Incremental Backup" ;;
      "distlist")  OPT="Distribution List Backup" ;;
      "alias")     OPT="Alias Backup" ;;
      "ldap")      OPT="Account Backup - Only LDAP" ;;
      "mbox")      OPT="Mailbox Backup" ;;
      "signature") OPT="Signature Backup" ;;
      "domain")    OPT="Domain Backup" ;;
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
  sqlite3 "$WORKDIR"/sessions.sqlite3 "select sessionID,conclusion_date from backup_session" | while read -r ROW; do
    local SESSIONID SESSION_MONTH SESSION_DAY SESSION_YEAR SESSION_HOUR SESSION_MINUTE
    SESSIONID=$(echo "$ROW" | cut -d'|' -f1)
    SESSION_MONTH=$(echo "$ROW" | cut -d'|' -f2 | cut -d'-' -f2)
    SESSION_DAY=$(echo "$ROW" | cut -d'|' -f2 | cut -d'-' -f3 | cut -d'T' -f1)
    SESSION_YEAR=$(echo "$ROW" | cut -d'|' -f2 | cut -d'-' -f1)
    SESSION_HOUR=$(echo "$ROW" | cut -d'|' -f2 | cut -d'T' -f2 | cut -d':' -f1)
    SESSION_MINUTE=$(echo "$ROW" | cut -d'|' -f2 | cut -d'T' -f2 | cut -d':' -f2)
    echo "SESSION: $SESSIONID started on $(date -d "$SESSION_MONTH/$SESSION_DAY/$SESSION_YEAR $SESSION_HOUR:$SESSION_MINUTE")" >> "$WORKDIR"/sessions.txt
    sqlite3 "$WORKDIR"/sessions.sqlite3 "select email from backup_account where sessionID='$SESSIONID'" | while read -r ACCOUNT; do
      echo "$SESSIONID:$ACCOUNT:$SESSION_MONTH/$SESSION_DAY/$SESSION_YEAR" >> "$WORKDIR"/sessions.txt
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
