#!/bin/bash
################################################################################
# Command Help Option
################################################################################

################################################################################
# show_help: It will show a quick help about each command from zmbackup
################################################################################
function show_help (){
  printf "usage: zmbackup -f [-m,-dl,-al,-ldp] [-d,-a] <mail/domain>"
  printf "\n       zmbackup -i <mail>"
  printf "\n       zmbackup -r [-m,-dl,-al,-ldp] [-d,-a] <session> <mail>"
  printf "\n       zmbackup -r [-ro] <session> <mail_origin> <mail_destination>"
  printf "\n       zmbackup -d <session>"
  printf "\n       zmbackup -m"

  # All the basic options.
  printf "\n\nOptions:\n"

  printf "\n -f,   --full                     : Execute full backup of an account, a list of accounts, or all accounts."
  printf "\n -i,   --incremental              : Execute incremental backup for an account, a list of accounts, or all accounts."
  printf "\n -l,   --list                     : List all backup sessions that still exist in your disk."
  printf "\n -r,   --restore                  : Restore the backup inside the users account."
  printf "\n -d,   --delete                   : Delete a session of backup."
  printf "\n -hp,  --housekeep                : Execute the Housekeep to remove old sessions - Zmbhousekeep"
  printf "\n -t,   --truncate                 : Delete all the backups and empty the database"
  printf "\n -m,   --migrate                  : Migrate the database from TXT to SQLITE3 and vice versa."
  printf "\n -v,   --version                  : Show the zmbackup version."
  printf "\n -h,   --help                     : Show this help"

  # All the options related to Full Backups
  printf "\n\nFull Backup Options:\n"

  printf "\n -m,   --mail                     : Execute a backup of an account, but only the mailbox."
  printf "\n -dl,  --distributionlist         : Execute a backup of a distributionlist instead of an account."
  printf "\n -al,  --alias                    : Execute a backup of an alias instead of an account."
  printf "\n -ldp, --ldap                     : Execute a backup of an account, but only the ldap entry."
  printf "\n -sig, --signature                : Execute a backup of a signature."
  printf "\n -d,   --domain                   : Execute a backup of only a set of domains, comma separated"
  printf "\n -a,   --account                  : Execute a backup of only a set of accounts, comma separated"


  # All the options related to Restore Backups
  printf "\n\nRestore Backup Options:\n"

  printf "\n -m,   --mail                     : Execute a restore of an account,  but only the mailbox."
  printf "\n -dl,  --distributionlist         : Execute a restore of a distributionlist instead of an account."
  printf "\n -al,  --alias                    : Execute a restore of an alias instead of an account."
  printf "\n -ldp, --ldap                     : Execute a restore of an account, but only the ldap entry."
  printf "\n -ro,  --restoreOnAccount         : Execute a restore of an account inside another account."
  printf "\n -sig, --signature                : Execute a restore of a signature."
  printf "\n -d,   --domain                   : Execute a backup of only a set of domains, comma separated"
  printf "\n -a,   --account                  : Execute a backup of only a set of accounts, comma separated"

  printf "\n\n\n"
}
