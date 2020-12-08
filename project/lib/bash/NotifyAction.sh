#!/bin/bash
################################################################################
# Mail Notification
################################################################################

################################################################################
# notify_begin: Function to notify when the backup process began through e-mail.
# Options:
#    $1 -> Inform the backup's session name;
#    $2 -> Infomr the type of backup is in execution.
################################################################################
function notify_begin()
{
  if [[ "$ENABLE_EMAIL_NOTIFY" == "all" || "$ENABLE_EMAIL_NOTIFY" == "start" ]]; then
    printf "Subject: Zmbackup - Backup routine for %s start at %s\nGreetings Administrator,
    \n\nThis is an automatic message to inform you that the process for %s BACKUP that you scheduled started right now.
    Depending on the amount of accounts and/or data to be backed up, this process can take some hours before conclude.
    \nDon't worry, we will inform you when the process finish.
    \n\nRegards,
    \nZmbackup Team" "$1" "$(date)" "$2"> "$MESSAGE"
    ERR=$( (sendmail -f "$EMAIL_SENDER" "$EMAIL_NOTIFY" < "$MESSAGE" ) 2>&1)
    BASHERRCODE=$?
    if [[ $BASHERRCODE -eq 0 ]]; then
      logger -i -p local7.info "Zmbackup: Mail sent to $EMAIL_NOTIFY to notify about the backup routine begin."
    else
      logger -i -p local7.info "Zmbackup: Cannot send mail for $EMAIL_NOTIFY - $ERR."
    fi
  fi
}


################################################################################
# notify_finishOK: Function to notify when the backup process finish - SUCCESS.
# Options:
#    $1 -> Inform the backup's session name;
#    $2 -> Inform the type of backup is in execution;
#    $3 -> Inform the status of the bacup. Valid Options:
#        - FAILURE - For some reason Zmbackup can't conclude this session;
#        - SUCCESS - Zmbackup concluded the session with no problem;
#        - CANCELED - The administrator canceled the session for some reason.
################################################################################
function notify_finish()
{
  if [[ "$ENABLE_EMAIL_NOTIFY" == "all" ]] || [[ "$ENABLE_EMAIL_NOTIFY" == "finish" && "$3" == "SUCCESS" ]] || [[ "$ENABLE_EMAIL_NOTIFY" == "error" && "$3" == "FAILURE" ]] ; then

    # Loading the variables
    if [[ "$3" == "SUCCESS" ]]; then
      SIZE=$(du -h "$WORKDIR"/"$1" 2> /dev/null | awk '{print $1}'; exit "${PIPESTATUS[0]}")
      BASHERRCODE=$?
      if [[ $BASHERRCODE -eq 0 ]]; then
        if [[ "$1" == "mbox"* ]]; then
          QTDE=$(find "$WORKDIR"/"$1"/*.tgz | wc -l)
        else
          QTDE=$(find "$WORKDIR"/"$1"/*.ldiff | wc -l)
        fi
      else
        SIZE=0
        QTDE=0
      fi
    else
      SIZE=0
      QTDE=0
    fi

    # The message
    printf "Subject: Zmbackup - Backup routine for %s complete at %s - %s\nGreetings Administrator,
    \n\nThis is an automatic message to inform you that the process for %s BACKUP that you scheduled ended right now.
    \nHere some information about this session:\n\nSize: %s\nAccounts: %s\nStatus: %s\n\nRegards,\nZmbackup Team
    \n\nSummary of files:\n" "$1" "$(date)" "$3" "$2" "$SIZE" "$QTDE" "$3"> "$MESSAGE"
    cat "$TEMPSESSION" >> "$MESSAGE"
    ERR=$( (sendmail -f "$EMAIL_SENDER" "$EMAIL_NOTIFY" < "$MESSAGE" ) 2>&1)
    BASHERRCODE=$?
    if [[ $BASHERRCODE -eq 0 ]]; then
      logger -i -p local7.info "Zmbackup: Mail sent to $EMAIL_NOTIFY to notify about the backup routine conclusion."
    else
      logger -i -p local7.info "Zmbackup: Cannot send mail for $EMAIL_NOTIFY - $ERR."
    fi
  fi
}
