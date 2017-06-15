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
  SESSION=$(grep "$1 started" $WORKDIR/sessions.txt -m 1 | awk '{print $2}')
  if [ "$SESSION" == "$1" ]; then
    echo "Removing session $1 - please wait."
    __DELETEBACKUP $1
    echo "Backup session $1 removed."
  else
    echo "Session $1 not found in database - ignoring."
    exit 1
  fi
}

################################################################################
# delete_old: Delete only the oldest session from zmbackup baased on $ROTATE_TIME
################################################################################
function delete_old(){
  OLDEST=$(date  +%Y%m%d%H%M%S -d "-$ROTATE_TIME days")
  echo "Removing old backup folders - please wait."
  logger -i -p local7.info "Zmbhousekeep: Cleaning $WORKDIR from old backup sessions."
  grep SESS $WORKDIR/sessions.txt | awk '{print $2}'| while read LINE; do
    if [ "$(echo $LINE | cut -d- -f2)" -lt "$OLDEST" ]; then
       __DELETEBACKUP $LINE
    fi
  done
  logger -i -p local7.info "Zmbhousekeep: Clean old backups activity concluded."
}

################################################################################
# __DELETEBACKUP: Private function used by delete_old and delete_one to exclude sessions
# Options:
#    $1 - The session name to be excluded
################################################################################
function __DELETEBACKUP(){
  ERR=$((rm -rf $WORKDIR/"$1") 2>&1)
  if [[ $? -eq 0 ]]; then
    grep -v "$1" $WORKDIR/sessions.txt > $WORKDIR/sessions.txt
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
  find $WORKDIR -type f -size 0 -delete
  if [[ $? -eq 0 ]]; then
    echo "Empty files removed with success."
    logger -i -p local7.info "Zmbhousekeep: Empty files removed with success."
  else
    echo "Can't remove empty files - $ERR"
    logger -i -p local7.err "Zmbhousekeep: Can't remove the empty files - See the error message below:"
    logger -i -p local7.err "Zmbhousekeep: $ERR"
  fi
}
