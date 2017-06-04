#!/bin/bash
################################################################################
# Restore Session - LDAP/Mailbox/DistList/Alias
################################################################################

################################################################################
# restore_mbox:
# Options:
#    $1 - The session to be restored
#    $2 - The list of accounts to be restored.
#    $3 - The destination of the restored account
################################################################################
restore_main_mailbox ()
{
  TEMPSESSION=$(egrep ': $1 started' $WORKDIR/sessions.txt | egrep 'started' | \
                awk '{print $2}' | sort | uniq)
  if ![ -z $TEMPSESSION ]; then
    echo "Restore mail process with session $i started at $(date)"
    if ! [ -z $3 && $2 == *"@"*"@"* ]; then
      ERR=$((http --check-status --verify=no POST 'https://$MAILHOST:7071/home/$3/?fmt=tgz'\
           -a "$ADMINUSER":"$ADMINPASS" < $WORKDIR/$1/$3.tgz) 2>&1)
      if [[ $? -eq 0 ]]; then
        echo "Account $2 restored with success"
      else
        echo "Error during the restore process for account $2. Error message below:"
        echo $ERR
      fi
      printf "======================================================================\n\n"
    else
      build_restore_list $1 $2
      cat $TEMPACCOUNT | parallel --no-notice --jobs $MAX_PARALLEL_PROCESS \
               "mailbox_restore $1 {}"
    fi
    echo "Restore mail process with session $i completed at $(date)"
  else
    echo "Nothing to do. Closing..."
    exit 2
  fi
}
