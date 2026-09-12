#!/usr/bin/env bats

load '../setup'

setup() {
  setup_mock_path
  source "${PROJECT_ROOT}/installScriptJava/vars.sh" 2>/dev/null || true
  source "${PROJECT_ROOT}/installScript/check.sh"

  CHECK_ROOT="$(mktemp -d)"
  ZMBKP_CONF="${CHECK_ROOT}/etc/zmbackup"
  ZMBKP_LIB="${CHECK_ROOT}/usr/local/lib/zmbackup"
  ZMBKP_JAR_NAME="zmbackup.jar"
  ZMBKP_VERSION="zmbackup version: 2.0.0"
  OSE_USER="$(/usr/bin/whoami)"
  export ZMBKP_CONF ZMBKP_LIB ZMBKP_JAR_NAME ZMBKP_VERSION OSE_USER
  export MOCK_ID_UID=0
  export MOCK_SU_FAIL=0
  export MOCK_SU_OUTPUT=""
}

teardown() {
  rm -rf "${CHECK_ROOT:-}"
}

markJavaInstallPresent() {
  mkdir -p "$ZMBKP_CONF" "$ZMBKP_LIB"
  touch "${ZMBKP_CONF}/zmbackup.yaml" "${ZMBKP_LIB}/${ZMBKP_JAR_NAME}"
}

@test "check_env: reports NEW INSTALL when no prior zmbackup is found" {
  export MOCK_SU_FAIL=1
  run check_env
  [[ "$output" == *"NEW INSTALL"* ]]
}

@test "check_env: --remove on an existing install executes the uninstall routine" {
  check_env --remove > "$BATS_TEST_TMPDIR/out.txt" 2>&1
  [ "$UPGRADE" = "N" ]
  [ "$UNINSTALL" = "Y" ]
  grep -q "UNINSTALL" "$BATS_TEST_TMPDIR/out.txt"
}

@test "check_env: --force-upgrade against a bash-tool-only install routes to a fresh Java install instead of the upgrade routine" {
  check_env --force-upgrade > "$BATS_TEST_TMPDIR/out.txt" 2>&1
  [ "$UPGRADE" = "N" ]
  [ "$UNINSTALL" = "N" ]
  grep -q "BASH-TOOL INSTALL FOUND" "$BATS_TEST_TMPDIR/out.txt"
}

@test "check_env: --force-upgrade with only the jar present (no zmbackup.yaml) still routes to a fresh install" {
  mkdir -p "$ZMBKP_LIB"
  touch "${ZMBKP_LIB}/${ZMBKP_JAR_NAME}"
  check_env --force-upgrade > "$BATS_TEST_TMPDIR/out.txt" 2>&1
  [ "$UPGRADE" = "N" ]
}

@test "check_env: --force-upgrade against a real 2.0 jar install on an old version executes the upgrade routine" {
  markJavaInstallPresent
  export MOCK_SU_OUTPUT="zmbackup version: 1.9.0"
  check_env --force-upgrade > "$BATS_TEST_TMPDIR/out.txt" 2>&1
  [ "$UPGRADE" = "Y" ]
  [ "$UNINSTALL" = "N" ]
  grep -q "OLD VERSION" "$BATS_TEST_TMPDIR/out.txt"
}

@test "check_env: --force-upgrade against a real 2.0 jar install already at the newest version exits with nothing to do" {
  markJavaInstallPresent
  export MOCK_SU_OUTPUT="$ZMBKP_VERSION"
  run check_env --force-upgrade
  [ "$status" -eq 0 ]
  [[ "$output" == *"Nothing to do"* ]]
}
