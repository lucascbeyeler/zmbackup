#!/bin/bash
################################################################################
# blocklist_gen (shared with the bash tool - same accounts, same file format)
# is reused as-is from installScript/deploy.sh; see install-java.sh, which
# sources both files.
################################################################################

################################################################################
# migrate_legacy_sessions: The Java build only ever reads session metadata from
# SQLite, but a server moving to it from the bash tool may have been using the
# bash tool's TXT sessions instead. If a sessions.txt is sitting in the backup
# directory, import it into the SQLite metadata store via "zmbackup migrate"
# (io.zmbackup.app.cli.MigrateCommand) so that history isn't silently lost.
# Safe to call unconditionally: the command itself is a no-op once there is no
# sessions.txt left to import (it renames it to sessions.txt.migrated).
################################################################################
function migrate_legacy_sessions() {
  if [[ -f "$OSE_DEFAULT_BKP_DIR/sessions.txt" ]]; then
    echo "Found an existing sessions.txt - migrating it into the SQLite metadata store..."
    sudo -H -u "$OSE_USER" bash -c "zmbackup migrate"
  fi
}

################################################################################
# deploy_new_java: Deploy a new install of the Java build of Zmbackup
################################################################################
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

  test -d "$ZMBKP_CONF" || mkdir -p "$ZMBKP_CONF"
  test -d "$ZMBKP_SRC" || mkdir -p "$ZMBKP_SRC"
  test -d "$ZMBKP_LIB" || mkdir -p "$ZMBKP_LIB"

  # Thin launcher + jar - paths are fixed by app/src/main/scripts/zmbackup and
  # YamlConfigLoader.DEFAULT_CONFIG_PATH, so they must land exactly here.
  # Owned by OSE_USER rather than root, matching the bash tool's own
  # project/zmbackup and project/lib install (install.sh always runs as
  # root, so this is just about who owns the files afterward, not who can
  # deploy them).
  install -o "$OSE_USER" -m 755 "$MYDIR"/app/src/main/scripts/zmbackup "$ZMBKP_SRC"
  install -o "$OSE_USER" -m 750 "$MYDIR"/app/build/libs/"$ZMBKP_JAR_NAME" "$ZMBKP_LIB"

  # Config + blocked list + cron
  install --backup=numbered -o "$OSE_USER" -m 600 "$MYDIR"/project/config/zmbackup.yaml "$ZMBKP_CONF"
  install --backup=numbered -o "$OSE_USER" -m 600 "$MYDIR"/project/config/blockedlist.conf "$ZMBKP_CONF"
  install --backup=numbered -o "$OSE_USER" -m 600 "$MYDIR"/project/config/zmbackup-java.cron "$ZMBKP_CRON_FILE"

  # Including custom settings
  sed -i "s|{OSE_DEFAULT_BKP_DIR}|${OSE_DEFAULT_BKP_DIR}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{ZMBKP_MAIL_ALERT}|${ZMBKP_MAIL_ALERT}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{ZMBKP_MAIL_SENDER}|${ZMBKP_MAIL_SENDER}|g" "$ZMBKP_CONF"/zmbackup.yaml
  # IPv6 addresses must be wrapped in brackets in URLs (RFC 3986)
  if [[ "$OSE_INSTALL_ADDRESS" == *:* ]]; then
    LDAP_ADDRESS="[$OSE_INSTALL_ADDRESS]"
  else
    LDAP_ADDRESS="$OSE_INSTALL_ADDRESS"
  fi
  sed -i "s|{OSE_INSTALL_ADDRESS}|${LDAP_ADDRESS}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{OSE_INSTALL_LDAPPASS}|${OSE_INSTALL_LDAPPASS}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{OSE_USER}|${OSE_USER}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{OSE_INSTALL_DIR}|${OSE_INSTALL_DIR}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{ZMBKP_CONF}|${ZMBKP_CONF}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{MAX_PARALLEL_PROCESS}|${MAX_PARALLEL_PROCESS}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{ROTATE_TIME}|${ROTATE_TIME}|g" "$ZMBKP_CONF"/zmbackup.yaml
  sed -i "s|{LOCK_BACKUP}|${LOCK_BACKUP}|g" "$ZMBKP_CONF"/zmbackup.yaml

  sed -i "s|{OSE_USER}|${OSE_USER}|g" "$ZMBKP_CRON_FILE"

  chown -R "$OSE_USER". "$ZMBKP_CONF"
  chmod 600 "$ZMBKP_CONF"/zmbackup.yaml

  # Generate Zmbackup's blocked list (shared logic with the bash installer)
  blocklist_gen

  # zmbackup.yaml/the jar are fully in place now, so "zmbackup migrate" can load
  # config and open the SQLite metadata store if there's a legacy sessions.txt.
  migrate_legacy_sessions
}

################################################################################
# deploy_upgrade_java: Rebuild and redeploy the jar + launcher, keeping the
# existing zmbackup.yaml/blockedlist.conf/cron untouched.
################################################################################
function deploy_upgrade_java() {
  echo "Upgrading... Please wait while we make some changes."

  test -d "$ZMBKP_SRC" || mkdir -p "$ZMBKP_SRC"
  test -d "$ZMBKP_LIB" || mkdir -p "$ZMBKP_LIB"
  install -o "$OSE_USER" -m 755 "$MYDIR"/app/src/main/scripts/zmbackup "$ZMBKP_SRC"
  install -o "$OSE_USER" -m 750 "$MYDIR"/app/build/libs/"$ZMBKP_JAR_NAME" "$ZMBKP_LIB"

  # Covers a server that switched to the Java build without ever having its
  # sessions.txt migrated (e.g. a bash-to-Java move immediately followed by
  # --force-upgrade); a no-op otherwise.
  migrate_legacy_sessions

  echo "Upgrade completed."
}

################################################################################
# truncate_database: Ask whether to also empty the SQLite metadata store (via
# "zmbackup truncate --force-clean") before it's torn down along with the rest
# of the install. Irreversible and, per "zmbackup truncate" itself, meant only
# for a test/development install being decommissioned - never run it against
# a production server, since the deleted session/account history cannot be
# recovered. Must run before the jar/config are removed below, since the
# command needs both to load.
################################################################################
function truncate_database() {
  printf "Also empty the backup metadata database (sessions.sqlite3)? This only makes sense for a "
  printf "\ntest/development install being torn down - it is irreversible and must NEVER be used on "
  printf "\na production server. [y/N]"
  read -r OPT
  if [[ $OPT == 'y' || $OPT == 'Y' ]]; then
    echo "Truncating the backup metadata database..."
    sudo -H -u "$OSE_USER" bash -c "zmbackup truncate --force-clean"
  fi
}

################################################################################
# uninstall_java: Remove the Java build of Zmbackup and all files related
################################################################################
function uninstall_java() {
  echo "Removing... Please wait while we make some changes."

  WORKDIR=""
  if [[ -f "$ZMBKP_CONF"/zmbackup.yaml ]]; then
    WORKDIR=$(grep "workDir:" "$ZMBKP_CONF"/zmbackup.yaml | head -1 | awk '{print $2}')
  fi

  # Ask about the database while the jar/config are still in place to run
  # "zmbackup truncate" against - only offer it when there is actually an
  # install left to run it with.
  if [[ -f "$ZMBKP_LIB/$ZMBKP_JAR_NAME" ]] && [[ -f "$ZMBKP_CONF"/zmbackup.yaml ]]; then
    truncate_database
  fi

  rm -f "$ZMBKP_SRC"/zmbackup
  rm -rf "$ZMBKP_LIB"
  rm -f "$ZMBKP_CRON_FILE"
  rm -rf "$ZMBKP_CONF"

  printf "Preserve Backup Storage?[n/Y]"
  read -r OPT
  if [[ $OPT == 'N' || $OPT == 'n' ]] && [[ -n "$WORKDIR" ]]; then
    echo "Removing backup storage..."
    rm -rf "${WORKDIR:?}"/*
  fi
}
