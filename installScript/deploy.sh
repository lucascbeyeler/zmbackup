#!/bin/bash
################################################################################
# blocklist_gen is reused as-is by install-java.sh (see its top-level source
# line) to generate the blocked list: same accounts, same file format,
# regardless of which language zmbackup itself is written in.
################################################################################

################################################################################
# blocklist_gen: Generate a blocked list of all accounts Zmbackup should ignore
################################################################################
function blocklist_gen(){
  for ACCOUNT in $(sudo -H -u "$OSE_USER" bash -c "/opt/zimbra/bin/zmprov -l gaa"); do
    if  [[ "$ACCOUNT" = "galsync"* ]] || \
    [[ "$ACCOUNT" = "virus"* ]] || \
    [[ "$ACCOUNT" = "ham"* ]] || \
    [[ "$ACCOUNT" = "admin"* ]] || \
    [[ "$ACCOUNT" = "spam"* ]] || \
    [[ "$ACCOUNT" = "zmbackup"* ]] || \
    [[ "$ACCOUNT" = "postmaster"* ]] || \
    [[ "$ACCOUNT" = "root"* ]]; then
      echo "$ACCOUNT" >> "$ZMBKP_CONF"/blockedlist.conf
    fi
  done
}
