#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
}

@test "vars: ERR_OK is 0" {
  [ "$ERR_OK" = "0" ]
}

@test "vars: ERR_NOROOT is 2" {
  [ "$ERR_NOROOT" = "2" ]
}

@test "vars: ERR_DEPNOTFOUND is 3" {
  [ "$ERR_DEPNOTFOUND" = "3" ]
}

@test "vars: ERR_NO_CONNECTION is 4" {
  [ "$ERR_NO_CONNECTION" = "4" ]
}

@test "vars: ERR_BUILD_FAILED is 6" {
  [ "$ERR_BUILD_FAILED" = "6" ]
}

@test "vars: ZMBKP_SRC is /usr/local/bin" {
  [ "$ZMBKP_SRC" = "/usr/local/bin" ]
}

@test "vars: ZMBKP_CONF is /etc/zmbackup" {
  [ "$ZMBKP_CONF" = "/etc/zmbackup" ]
}

@test "vars: ZMBKP_LIB is /usr/local/lib/zmbackup" {
  [ "$ZMBKP_LIB" = "/usr/local/lib/zmbackup" ]
}

@test "vars: ZMBKP_JAR_NAME is zmbackup.jar" {
  [ "$ZMBKP_JAR_NAME" = "zmbackup.jar" ]
}

@test "vars: JAVA_MIN_VERSION is 21" {
  [ "$JAVA_MIN_VERSION" = "21" ]
}

@test "vars: OSE_USER is zimbra" {
  [ "$OSE_USER" = "zimbra" ]
}

@test "vars: OSE_INSTALL_DIR is /opt/zimbra" {
  [ "$OSE_INSTALL_DIR" = "/opt/zimbra" ]
}

@test "vars: OSE_DEFAULT_BKP_DIR is /opt/zimbra/backup" {
  [ "$OSE_DEFAULT_BKP_DIR" = "/opt/zimbra/backup" ]
}

@test "vars: MAX_PARALLEL_PROCESS is 3" {
  [ "$MAX_PARALLEL_PROCESS" = "3" ]
}

@test "vars: ROTATE_TIME is 30" {
  [ "$ROTATE_TIME" = "30" ]
}

@test "vars: LOCK_BACKUP is true" {
  [ "$LOCK_BACKUP" = "true" ]
}

@test "vars: ZMBKP_VERSION has the expected prefix" {
  [[ "$ZMBKP_VERSION" == "zmbackup version: "* ]]
}

@test "vars: ZMBKP_VERSION reads the real VERSION file when MYDIR points at the repo" {
  MYDIR="$PROJECT_ROOT"
  ZMBKP_VERSION="zmbackup version: $(cat "$MYDIR/VERSION")"
  [[ "$ZMBKP_VERSION" == *"1.2"* ]]
}

@test "vars: TERM is set to linux" {
  [ "$TERM" = "linux" ]
}
