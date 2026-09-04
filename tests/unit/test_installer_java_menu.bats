#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${INSTALLER_JAVA_DIR}/menu.sh"
}

@test "set_values_java: uses defaults when all inputs are empty" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n\n')
    echo \"OSE_USER=\$OSE_USER\"
    echo \"MAX_PARALLEL_PROCESS=\$MAX_PARALLEL_PROCESS\"
  "
  [[ "$output" == *"OSE_USER=zimbra"* ]]
  [[ "$output" == *"MAX_PARALLEL_PROCESS=3"* ]]
}

@test "set_values_java: overrides OSE_USER when provided" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf 'myuser\n\n\n\n\n\n\n\n\n\n')
    echo \"OSE_USER=\$OSE_USER\"
  "
  [[ "$output" == *"OSE_USER=myuser"* ]]
}

@test "set_values_java: overrides MAX_PARALLEL_PROCESS when provided" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n8\n\n\n')
    echo \"MAX_PARALLEL_PROCESS=\$MAX_PARALLEL_PROCESS\"
  "
  [[ "$output" == *"MAX_PARALLEL_PROCESS=8"* ]]
}

@test "set_values_java: displays CONFIGURATION COMPLETED message" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n\n')
  "
  [[ "$output" == *"CONFIGURATION COMPLETED"* ]]
}

@test "check_config_java: displays configuration summary" {
  OSE_USER="zimbra"
  OSE_INSTALL_ADDRESS="192.168.1.1"
  OSE_INSTALL_LDAPPASS="secret"
  ZMBKP_REST_ADMIN="admin@example.com"
  ZMBKP_REST_ADMIN_PASS="secret2"
  OSE_INSTALL_DIR="/opt/zimbra"
  OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"
  ZMBKP_SRC="/usr/local/bin"
  ZMBKP_LIB="/usr/local/lib/zmbackup"
  ZMBKP_JAR_NAME="zmbackup.jar"
  ZMBKP_CONF="/etc/zmbackup"
  ROTATE_TIME="30"
  MAX_PARALLEL_PROCESS="3"
  LOCK_BACKUP="true"
  output=$(echo "" | check_config_java)
  [[ "$output" == *"Summary"* ]]
  [[ "$output" == *"zimbra"* ]]
  [[ "$output" == *"SQLite3"* ]]
}
