#!/bin/bash
################################################################################
# Delete Session
################################################################################

################################################################################
# delete_one: Delete only one session from zmbackup
# Options:
#    $1 - The session name to be excluded
################################################################################
function delete_one(){
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    SESSION=$(grep "$1 started" "$WORKDIR"/sessions.txt -m 1 | awk '{print $2}')
  elif [[ $SESSION_TYPE == 'SQLITE3' ]]; then
    SESSION=$(sqlite3 "$WORKDIR"/sessions.sqlite3 "select sessionID from backup_session where sessionID='$1'")
  fi
  if [ -n "$SESSION" ]; then
    echo "Removing session $1 - please wait."
    __DELETEBACKUP "$1"
  else
    echo "Session $1 not found in database - ignoring."
  fi
  rm -rf "$PID"
  unset SESSION
}

################################################################################
# delete_old: Delete only the oldest session from zmbackup baased on $ROTATE_TIME
################################################################################
function delete_old(){
  echo "Removing old backup folders - please wait."
  logger -i -p local7.info "Zmbhousekeep: Cleaning $WORKDIR from old backup sessions."
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    OLDEST=$(date  +%Y%m%d%H%M%S -d "-$ROTATE_TIME days")
    grep SESS "$WORKDIR"/sessions.txt | awk '{print $2}'| while read -r LINE; do
      if [ "$(echo "$LINE" | cut -d- -f2)" -lt "$OLDEST" ]; then
         __DELETEBACKUP "$LINE"
      fi
    done
  elif [[ $SESSION_TYPE == 'SQLITE3' ]]; then
    sqlite3 "$WORKDIR"/sessions.sqlite3 "select sessionID from backup_session where conclusion_date < datetime('now','-$ROTATE_TIME day')" | while read -r LINE; do
      __DELETEBACKUP "$LINE"
    done
    sqlite3 "$WORKDIR"/sessions.sqlite3 "VACUUM"
  fi
  logger -i -p local7.info "Zmbhousekeep: Clean old backups activity concluded."
}

################################################################################
# leeroy_jenkins: Delete all the backup folders
################################################################################
function leeroy_jenkins(){
  echo "LEEROY JENKINS!!!!!"
  logger -i -p local7.info "Zmbhousekeep: Cleaning $WORKDIR from all the backup sessions."
  if [[ $SESSION_TYPE == 'TXT' ]]; then
    grep SESS "$WORKDIR"/sessions.txt | awk '{print $2}'| while read -r LINE; do
      __DELETEBACKUP "$LINE"
    done
  elif [[ $SESSION_TYPE == 'SQLITE3' ]]; then
    sqlite3 "$WORKDIR"/sessions.sqlite3 "select sessionID from backup_session" | while read -r LINE; do
      __DELETEBACKUP "$LINE"
    done
    sqlite3 "$WORKDIR"/sessions.sqlite3 "VACUUM"
  fi
  logger -i -p local7.info "Zmbhousekeep: Clean old backups activity concluded."
  echo "All the backups are deleted - Have a nice week :)"
}

################################################################################
# __DELETEBACKUP: Private function used by delete_old and delete_one to exclude sessions
# Options:
#    $1 - The session name to be excluded
################################################################################
function __DELETEBACKUP(){
  ERR=$( (rm -rf "${WORKDIR:?}"/"${1:?}") 2>&1)
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    if [[ $SESSION_TYPE == 'TXT' ]]; then
      grep -v "$1" "${WORKDIR:?}"/sessions.txt > "${WORKDIR:?}"/.sessions.txt
      cat "${WORKDIR:?}"/.sessions.txt > "${WORKDIR:?}"/sessions.txt
      rm -rf "${WORKDIR:?}"/.sessions.txt
    elif [[ $SESSION_TYPE == 'SQLITE3' ]]; then
      sqlite3 "${WORKDIR:?}"/sessions.sqlite3 "delete from backup_account where sessionID='$1'"
      sqlite3 "${WORKDIR:?}"/sessions.sqlite3 "delete from backup_session where sessionID='$1'"
    fi
    echo "Backup session $1 removed."
    logger -i -p local7.info "Zmbhousekeep: Backup session $1 removed."
  else
    echo "Can't remove the file $1 - $ERR"
    logger -i -p local7.err "Zmbhousekeep: Backup session $1 can't be excluded - See the error message below:"
    logger -i -p local7.err "Zmbhousekeep: $ERR"
  fi
}

################################################################################
# clean_empty: Remove all the empty files inside $WORKDIR
################################################################################
function clean_empty(){
  echo "Removing empty files - please wait."
  logger -i -p local7.info "Zmbhousekeep: Cleaning $WORKDIR from empty files."
  find "$WORKDIR" -type f -size 0 -delete
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Empty files removed with success."
    logger -i -p local7.info "Zmbhousekeep: Empty files removed with success."
  else
    echo "Can't remove empty files - $ERR"
    logger -i -p local7.err "Zmbhousekeep: Can't remove the empty files - See the error message below:"
    logger -i -p local7.err "Zmbhousekeep: $ERR"
  fi
}
