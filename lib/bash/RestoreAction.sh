#!/bin/bash
################################################################################
# Restore Session - LDAP/Mailbox/DistList/Alias
################################################################################

################################################################################
# restore_main_mailbox: Manage the restore action for one or all mailbox
# Options:
#    $1 - The session to be restored
#    $2 - The list of accounts to be restored.
#    $3 - The destination of the restored account
################################################################################
function restore_main_mailbox()
{
  SESSION=$(egrep ": $1 started" $WORKDIR/sessions.txt | egrep 'started' | \
                awk '{print $2}' | sort | uniq)
  if ! [ -z $SESSION ]; then
    echo "Restore mail process with session $i started at $(date)"
    if ! [[ -z $3 && $2 == *"@"*"@"* ]]; then
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
      build_listRST $1 $2
      cat $TEMPACCOUNT | parallel --no-notice --jobs $MAX_PARALLEL_PROCESS \
               "mailbox_restore $1 {}"
    fi
    echo "Restore mail process with session $i completed at $(date)"
  else
    echo "Nothing to do. Closing..."
    exit 2
  fi
}

################################################################################
# restore_main_ldap: Manage the restore action for one or all ldap accounts
# Options:
#    $1 - The session to be restored
#    $2 - The list of accounts to be restored.
################################################################################
function restore_main_ldap()
{
  SESSION=$(egrep ': $1 started' $WORKDIR/sessions.txt | egrep 'started' | \
                awk '{print $2}' | sort | uniq)
  if [ -s $SESSION ]; then
    echo "Restore LDAP process with session $i started at $(date)"
    build_listRST $1 $2
    cat $TEMPACCOUNT | parallel --no-notice --jobs $MAX_PARALLEL_PROCESS \
                              "ldap_restore $i {}"
    echo "Restore LDAP process with session $i completed at $(date)"
  else
    echo "Nothing to do. Closing..."
    exit 2
  fi
}
