#!/bin/bash

function blocklist_gen(){
  for ACCOUNT in $(sudo -H -u "$OSE_USER" bash -c "$OSE_INSTALL_DIR/bin/zmprov -l gaa"); do
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
