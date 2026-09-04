#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${PROJECT_ROOT}/installScript/deploy.sh"
  source "${INSTALLER_JAVA_DIR}/deploy.sh"

  DEPLOY_ROOT="$(mktemp -d)"
  OSE_DEFAULT_BKP_DIR="${DEPLOY_ROOT}/backup"
  ZMBKP_CONF="${DEPLOY_ROOT}/etc/zmbackup"
  ZMBKP_SRC="${DEPLOY_ROOT}/usr/local/bin"
  ZMBKP_LIB="${DEPLOY_ROOT}/usr/local/lib/zmbackup"
  ZMBKP_CRON_FILE="${DEPLOY_ROOT}/etc/cron.d/zmbackup"
  OSE_USER="$(/usr/bin/whoami)"
  OSE_INSTALL_ADDRESS="192.168.1.1"
  OSE_INSTALL_LDAPPASS="testpassword"
  OSE_INSTALL_DIR="/opt/zimbra"
  ZMBKP_REST_ADMIN="admin@example.com"
  ZMBKP_REST_ADMIN_PASS="restsecret"
  ZMBKP_MAIL_ALERT="admin@example.com"
  ZMBKP_MAIL_SENDER="zmbackup@example.com"
  MAX_PARALLEL_PROCESS="3"
  ROTATE_TIME="30"
  LOCK_BACKUP="true"
  export DEPLOY_ROOT OSE_DEFAULT_BKP_DIR ZMBKP_CONF ZMBKP_SRC ZMBKP_LIB ZMBKP_CRON_FILE
  export OSE_USER OSE_INSTALL_ADDRESS OSE_INSTALL_LDAPPASS OSE_INSTALL_DIR
  export ZMBKP_REST_ADMIN ZMBKP_REST_ADMIN_PASS ZMBKP_MAIL_ALERT ZMBKP_MAIL_SENDER MAX_PARALLEL_PROCESS ROTATE_TIME LOCK_BACKUP

  mkdir -p "${DEPLOY_ROOT}/etc/cron.d"

  MYDIR="${DEPLOY_ROOT}/src"
  mkdir -p "${MYDIR}/app/src/main/scripts" "${MYDIR}/app/build/libs" "${MYDIR}/project/config"
  cp "${PROJECT_ROOT}/app/src/main/scripts/zmbackup" "${MYDIR}/app/src/main/scripts/zmbackup"
  cp "${PROJECT_ROOT}/project/config/zmbackup.yaml" "${MYDIR}/project/config/zmbackup.yaml"
  cp "${PROJECT_ROOT}/project/config/blockedlist.conf" "${MYDIR}/project/config/blockedlist.conf"
  cp "${PROJECT_ROOT}/project/config/zmbackup-java.cron" "${MYDIR}/project/config/zmbackup-java.cron"
  echo "fake jar contents" > "${MYDIR}/app/build/libs/${ZMBKP_JAR_NAME}"
  export MYDIR
}

teardown() {
  rm -rf "${DEPLOY_ROOT:-}"
}

@test "deploy_new_java: creates backup directory" {
  MOCK_SU_OUTPUT=""
  run deploy_new_java
  [ -d "$OSE_DEFAULT_BKP_DIR" ]
}

@test "deploy_new_java: creates zmbackup conf directory" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  [ -d "$ZMBKP_CONF" ]
}

@test "deploy_new_java: installs the thin launcher" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  [ -f "${ZMBKP_SRC}/zmbackup" ]
}

@test "deploy_new_java: installs the jar" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  [ -f "${ZMBKP_LIB}/${ZMBKP_JAR_NAME}" ]
}

@test "deploy_new_java: installs the cron file" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  [ -f "$ZMBKP_CRON_FILE" ]
}

@test "deploy_new_java: substitutes the configured user into the cron file" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  ! grep -q "{OSE_USER}" "$ZMBKP_CRON_FILE"
  grep -q "$OSE_USER" "$ZMBKP_CRON_FILE"
}

@test "deploy_new_java: installs the blocked list" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  [ -f "${ZMBKP_CONF}/blockedlist.conf" ]
}

@test "deploy_new_java: substitutes OSE_DEFAULT_BKP_DIR into zmbackup.yaml" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  grep -q "workDir: ${OSE_DEFAULT_BKP_DIR}" "${ZMBKP_CONF}/zmbackup.yaml"
}

@test "deploy_new_java: substitutes OSE_INSTALL_ADDRESS into zmbackup.yaml" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  grep -q "192.168.1.1" "${ZMBKP_CONF}/zmbackup.yaml"
}

@test "deploy_new_java: wraps IPv6 address in brackets in zmbackup.yaml" {
  OSE_INSTALL_ADDRESS="2001:db8::1"
  MOCK_SU_OUTPUT=""
  deploy_new_java
  grep -q "\[2001:db8::1\]" "${ZMBKP_CONF}/zmbackup.yaml"
}

@test "deploy_new_java: leaves no unsubstituted {PLACEHOLDER} tokens in zmbackup.yaml" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  run grep -oE '\{[A-Z_]+\}' "${ZMBKP_CONF}/zmbackup.yaml"
  [ "$status" -ne 0 ]
}

@test "deploy_new_java: exits ERR_DEPNOTFOUND when the backup directory cannot be created" {
  OSE_DEFAULT_BKP_DIR="/proc/invalid_dir_xyz"
  run deploy_new_java
  [ "$status" -eq "$ERR_DEPNOTFOUND" ]
}

@test "deploy_new_java: generates the blocked list" {
  export MOCK_SU_OUTPUT="galsync@example.com
user@example.com"
  deploy_new_java
  grep -q "galsync@example.com" "${ZMBKP_CONF}/blockedlist.conf"
}

@test "deploy_new_java: migrates an existing sessions.txt via zmbackup migrate" {
  mkdir -p "$OSE_DEFAULT_BKP_DIR"
  touch "${OSE_DEFAULT_BKP_DIR}/sessions.txt"
  MOCK_SUDO_LOG="$(mktemp)"
  MOCK_SU_OUTPUT=""
  export MOCK_SUDO_LOG
  deploy_new_java
  grep -q "zmbackup migrate" "$MOCK_SUDO_LOG"
}

@test "deploy_new_java: does not invoke migrate when there is no sessions.txt" {
  MOCK_SUDO_LOG="$(mktemp)"
  MOCK_SU_OUTPUT=""
  export MOCK_SUDO_LOG
  deploy_new_java
  ! grep -q "zmbackup migrate" "$MOCK_SUDO_LOG"
}

@test "deploy_upgrade_java: reinstalls the launcher" {
  run deploy_upgrade_java
  [ -f "${ZMBKP_SRC}/zmbackup" ]
}

@test "deploy_upgrade_java: reinstalls the jar" {
  run deploy_upgrade_java
  [ -f "${ZMBKP_LIB}/${ZMBKP_JAR_NAME}" ]
}

@test "deploy_upgrade_java: migrates an existing sessions.txt via zmbackup migrate" {
  mkdir -p "$OSE_DEFAULT_BKP_DIR"
  touch "${OSE_DEFAULT_BKP_DIR}/sessions.txt"
  MOCK_SUDO_LOG="$(mktemp)"
  export MOCK_SUDO_LOG
  deploy_upgrade_java
  grep -q "zmbackup migrate" "$MOCK_SUDO_LOG"
}

@test "deploy_upgrade_java: does not invoke migrate when there is no sessions.txt" {
  MOCK_SUDO_LOG="$(mktemp)"
  export MOCK_SUDO_LOG
  deploy_upgrade_java
  ! grep -q "zmbackup migrate" "$MOCK_SUDO_LOG"
}

@test "uninstall_java: removes the launcher" {
  mkdir -p "$ZMBKP_SRC" "$ZMBKP_CONF" "$ZMBKP_LIB"
  touch "${ZMBKP_SRC}/zmbackup"
  echo "backup:" > "${ZMBKP_CONF}/zmbackup.yaml"
  echo "  workDir: ${DEPLOY_ROOT}/backup" >> "${ZMBKP_CONF}/zmbackup.yaml"
  mkdir -p "${DEPLOY_ROOT}/backup"
  echo "N" | uninstall_java
  [ ! -f "${ZMBKP_SRC}/zmbackup" ]
}

@test "uninstall_java: removes the jar directory" {
  mkdir -p "$ZMBKP_SRC" "$ZMBKP_CONF" "$ZMBKP_LIB"
  echo "backup:" > "${ZMBKP_CONF}/zmbackup.yaml"
  echo "  workDir: ${DEPLOY_ROOT}/backup" >> "${ZMBKP_CONF}/zmbackup.yaml"
  mkdir -p "${DEPLOY_ROOT}/backup"
  echo "N" | uninstall_java
  [ ! -d "$ZMBKP_LIB" ]
}

@test "uninstall_java: removes the cron file" {
  mkdir -p "$ZMBKP_SRC" "$ZMBKP_CONF" "$ZMBKP_LIB"
  touch "$ZMBKP_CRON_FILE"
  echo "backup:" > "${ZMBKP_CONF}/zmbackup.yaml"
  echo "  workDir: ${DEPLOY_ROOT}/backup" >> "${ZMBKP_CONF}/zmbackup.yaml"
  mkdir -p "${DEPLOY_ROOT}/backup"
  echo "N" | uninstall_java
  [ ! -f "$ZMBKP_CRON_FILE" ]
}

@test "uninstall_java: removes backup storage when the user answers N" {
  mkdir -p "$ZMBKP_CONF" "${DEPLOY_ROOT}/backup"
  echo "backup:" > "${ZMBKP_CONF}/zmbackup.yaml"
  echo "  workDir: ${DEPLOY_ROOT}/backup" >> "${ZMBKP_CONF}/zmbackup.yaml"
  touch "${DEPLOY_ROOT}/backup/sessions.sqlite3"
  echo "N" | uninstall_java
  [ ! -f "${DEPLOY_ROOT}/backup/sessions.sqlite3" ]
}

@test "uninstall_java: preserves backup storage when the user answers Y" {
  mkdir -p "$ZMBKP_CONF" "${DEPLOY_ROOT}/backup"
  echo "backup:" > "${ZMBKP_CONF}/zmbackup.yaml"
  echo "  workDir: ${DEPLOY_ROOT}/backup" >> "${ZMBKP_CONF}/zmbackup.yaml"
  touch "${DEPLOY_ROOT}/backup/sessions.sqlite3"
  echo "Y" | uninstall_java
  [ -f "${DEPLOY_ROOT}/backup/sessions.sqlite3" ]
}

@test "uninstall_java: parses workDir out of the real (commented) zmbackup.yaml template" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  touch "${OSE_DEFAULT_BKP_DIR}/sessions.sqlite3"
  printf 'N\nN\n' | uninstall_java
  [ ! -f "${OSE_DEFAULT_BKP_DIR}/sessions.sqlite3" ]
}

@test "uninstall_java: does not offer to truncate the database when there is no jar/config to run it against" {
  mkdir -p "$ZMBKP_SRC" "$ZMBKP_CONF" "$ZMBKP_LIB"
  echo "backup:" > "${ZMBKP_CONF}/zmbackup.yaml"
  echo "  workDir: ${DEPLOY_ROOT}/backup" >> "${ZMBKP_CONF}/zmbackup.yaml"
  mkdir -p "${DEPLOY_ROOT}/backup"
  MOCK_SUDO_LOG="$(mktemp)"
  export MOCK_SUDO_LOG
  echo "N" | uninstall_java
  [ ! -s "$MOCK_SUDO_LOG" ]
}

@test "uninstall_java: invokes zmbackup truncate --force-clean when the user opts in" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  MOCK_SUDO_LOG="$(mktemp)"
  export MOCK_SUDO_LOG
  printf 'Y\nY\n' | uninstall_java
  grep -q "zmbackup truncate --force-clean" "$MOCK_SUDO_LOG"
}

@test "uninstall_java: does not invoke zmbackup truncate when the user declines" {
  MOCK_SU_OUTPUT=""
  deploy_new_java
  MOCK_SUDO_LOG="$(mktemp)"
  export MOCK_SUDO_LOG
  printf 'N\nN\n' | uninstall_java
  ! grep -q "zmbackup truncate" "$MOCK_SUDO_LOG"
}
