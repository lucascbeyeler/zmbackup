#!/bin/bash

function yaml_single_quoted() {
  [[ -z "$1" ]] && return
  printf "'%s'" "${1//\'/\'\'}"
}

function sed_replacement() {
  printf '%s' "$1" | sed 's/[\\&|]/\\&/g'
}

function pin_launcher_java() {
  local JAVA_BIN
  JAVA_BIN=$(command -v java) || return 0
  sed -i "s|^ZMBKP_JAVA_BIN=.*|ZMBKP_JAVA_BIN=$(sed_replacement "$(printf '%q' "$JAVA_BIN")")|" "$ZMBKP_SRC"/zmbackup
}

function migrate_legacy_sessions() {
  if [[ -f "$OSE_DEFAULT_BKP_DIR/sessions.txt" ]]; then
    echo "Found an existing sessions.txt - migrating it into the SQLite metadata store..."
    sudo -H -u "$OSE_USER" bash -c "zmbackup migrate"
    echo ""
    echo "IMPORTANT - before your first backup with this Java build:"
    echo "  - Never run the 1.2.x bash tool and this build against the same backup directory at the"
    echo "    same time - there is no cross-tool locking between them. Cut cron over atomically."
    echo "  - This build enforces LDAP/REST TLS certificate validation the bash tool never did. If"
    echo "    your Zimbra install uses a self-signed certificate, set zimbraLdap.caCertificatePath"
    echo "    (and zimbraMailbox.caCertificatePath) in zmbackup.yaml before running a real backup,"
    echo "    or the connection will simply refuse to start."
  fi
}

function deploy_new_java() {
  echo "Installing... Please wait while we make some changes."

  mkdir -p "$OSE_DEFAULT_BKP_DIR" > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -ne 0 ]]; then
    echo "[FAIL] - Can't create the directory"
    echo "For some reason Zmbackup can't create the folder $OSE_DEFAULT_BKP_DIR."
    echo "Maybe you are using a NFS and the permissions are wrong?"
    echo "Please check what happened and try again."
    exit "$ERR_DEPNOTFOUND"
  fi
  chown -R "$OSE_USER"."$OSE_USER" "$OSE_DEFAULT_BKP_DIR" > /dev/null 2>&1

  if [[ -n "$ZMBKP_METADATA_DIR" ]]; then
    if ! mkdir -p "$ZMBKP_METADATA_DIR" > /dev/null 2>&1; then
      echo "[FAIL] - Can't create the directory"
      echo "For some reason Zmbackup can't create the folder $ZMBKP_METADATA_DIR."
      echo "Please check what happened and try again."
      exit "$ERR_DEPNOTFOUND"
    fi
    chown -R "$OSE_USER"."$OSE_USER" "$ZMBKP_METADATA_DIR" > /dev/null 2>&1
  fi

  test -d "$ZMBKP_CONF" || mkdir -p "$ZMBKP_CONF"
  test -d "$ZMBKP_SRC" || mkdir -p "$ZMBKP_SRC"
  test -d "$ZMBKP_LIB" || mkdir -p "$ZMBKP_LIB"

  install -o "$OSE_USER" -m 755 "$MYDIR"/app/src/main/scripts/zmbackup "$ZMBKP_SRC"
  install -o "$OSE_USER" -m 750 "$MYDIR"/app/build/libs/"$ZMBKP_JAR_NAME" "$ZMBKP_LIB"
  pin_launcher_java

  install --backup=numbered -o "$OSE_USER" -m 600 "$MYDIR"/project/config/zmbackup.yaml "$ZMBKP_CONF"
  install --backup=numbered -o "$OSE_USER" -m 600 "$MYDIR"/project/config/blockedlist.conf "$ZMBKP_CONF"
  install --backup=numbered -o "$ZMBKP_CRON_OWNER" -m 644 "$MYDIR"/project/config/zmbackup-java.cron "$ZMBKP_CRON_FILE"

  render_config_java "$ZMBKP_CONF"/zmbackup.yaml

  sed -i "s|{OSE_USER}|$(sed_replacement "$OSE_USER")|g" "$ZMBKP_CRON_FILE"

  chown -R "$OSE_USER". "$ZMBKP_CONF"
  chmod 600 "$ZMBKP_CONF"/zmbackup.yaml

  blocklist_gen

  migrate_legacy_sessions
}

function deploy_upgrade_java() {
  echo "Upgrading... Please wait while we make some changes."

  test -d "$ZMBKP_SRC" || mkdir -p "$ZMBKP_SRC"
  test -d "$ZMBKP_LIB" || mkdir -p "$ZMBKP_LIB"
  install -o "$OSE_USER" -m 755 "$MYDIR"/app/src/main/scripts/zmbackup "$ZMBKP_SRC"
  install -o "$OSE_USER" -m 750 "$MYDIR"/app/build/libs/"$ZMBKP_JAR_NAME" "$ZMBKP_LIB"
  pin_launcher_java

  if [[ -f "$ZMBKP_CRON_FILE" ]]; then
    chown "$ZMBKP_CRON_OWNER" "$ZMBKP_CRON_FILE"
    chmod 644 "$ZMBKP_CRON_FILE"
  fi

  complete_config_java
  migrate_legacy_sessions
  if [[ $UNATTENDED == "Y" ]]; then
    warn_upgrade_without_ca
  fi

  echo "Upgrade completed."
}

function truncate_database() {
  if [[ $UNATTENDED == "Y" ]]; then
    echo "Unattended uninstall - skipping database truncation."
    return
  fi
  printf "Also empty the backup metadata database (sessions.sqlite3)? This PERMANENTLY DELETES every "
  printf "\nbackup session/account record and CANNOT be undone. Backup files under workDir are NOT "
  printf "\ndeleted by this, but without their metadata zmbackup can no longer list or restore them - "
  printf "\nthey become unusable through this tool. This only makes sense for a test/development "
  printf "\ninstall being torn down and must NEVER be used on a production server.\n"
  printf "Type TRUNCATE (all caps) to confirm, or press Enter to skip: "
  read -r OPT
  if [[ $OPT == 'TRUNCATE' ]]; then
    echo "Truncating the backup metadata database..."
    sudo -H -u "$OSE_USER" bash -c "zmbackup truncate --force-clean"
  else
    echo "Skipping database truncation."
  fi
}

function uninstall_java() {
  echo "Removing... Please wait while we make some changes."

  WORKDIR=""
  if [[ -f "$ZMBKP_CONF"/zmbackup.yaml ]]; then
    WORKDIR=$(grep "workDir:" "$ZMBKP_CONF"/zmbackup.yaml | head -1 | awk '{print $2}')
  fi

  if [[ -f "$ZMBKP_LIB/$ZMBKP_JAR_NAME" ]] && [[ -f "$ZMBKP_CONF"/zmbackup.yaml ]]; then
    truncate_database
  fi

  rm -f "$ZMBKP_SRC"/zmbackup
  rm -rf "$ZMBKP_LIB"
  rm -f "$ZMBKP_CRON_FILE"
  rm -rf "$ZMBKP_CONF"

  if [[ $UNATTENDED == "Y" ]]; then
    echo "Unattended uninstall - preserving backup storage."
    return
  fi

  printf "Preserve Backup Storage?[n/Y]"
  read -r OPT
  if [[ $OPT == 'N' || $OPT == 'n' ]] && [[ -n "$WORKDIR" ]]; then
    echo "Removing backup storage..."
    rm -rf "${WORKDIR:?}"/*
  fi
}
