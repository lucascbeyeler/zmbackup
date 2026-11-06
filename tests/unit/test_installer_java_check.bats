#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${INSTALLER_JAVA_DIR}/check.sh"
}

@test "check_java_runtime: sets NEED_JAVA=Y and reports NOT FOUND when java is missing" {
  local old_path="$PATH"
  PATH="/nonexistent"
  run check_java_runtime
  PATH="$old_path"
  [ "$status" -eq 0 ]
  [[ "$output" == *"NOT FOUND"* ]]
}

@test "check_java_runtime: sets NEED_JAVA=N when an OpenJDK 17 runtime is present" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "17.0.9" 2023-10-17
OpenJDK Runtime Environment (build 17.0.9+9)'
  check_java_runtime
  [ "$NEED_JAVA" = "N" ]
}

@test "check_java_runtime: reports OK when an OpenJDK 17 runtime is present" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "17.0.9" 2023-10-17'
  run check_java_runtime
  [[ "$output" == *"OK"* ]]
}

@test "check_java_runtime: sets NEED_JAVA=Y when the runtime is older than 17" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "11.0.20" 2023-07-18'
  check_java_runtime
  [ "$NEED_JAVA" = "Y" ]
}

@test "check_java_runtime: reports TOO OLD when the runtime is older than 17" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "11.0.20" 2023-07-18'
  run check_java_runtime
  [[ "$output" == *"TOO OLD"* ]]
}

@test "check_java_runtime: accepts a newer major version than the minimum" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "21.0.5" 2024-10-15'
  check_java_runtime
  [ "$NEED_JAVA" = "N" ]
}

@test "check_java_runtime: sets NEED_JAVA=Y when the version output cannot be parsed" {
  export MOCK_JAVA_VERSION_OUTPUT='not a recognizable version banner'
  check_java_runtime
  [ "$NEED_JAVA" = "Y" ]
}

@test "check_java_runtime: reports UNKNOWN VERSION when the version output cannot be parsed" {
  export MOCK_JAVA_VERSION_OUTPUT='not a recognizable version banner'
  run check_java_runtime
  [[ "$output" == *"UNKNOWN VERSION"* ]]
}

@test "check_cron_daemon: passes when cron is running" {
  export MOCK_PGREP_RUNNING=cron
  run check_cron_daemon
  [ "$status" -eq 0 ]
  [[ "$output" == *"[RUNNING]"* ]]
}

@test "check_cron_daemon: passes when crond is running" {
  export MOCK_PGREP_RUNNING=crond
  run check_cron_daemon
  [ "$status" -eq 0 ]
  [[ "$output" == *"[RUNNING]"* ]]
}

@test "check_cron_daemon: unattended exits 3 when no daemon is running" {
  UNATTENDED=Y
  run check_cron_daemon
  [ "$status" -eq 3 ]
  [[ "$output" == *"would never run"* ]]
  [[ "$output" == *"ZMBKP_SKIP_CRON_CHECK"* ]]
}

@test "check_cron_daemon: reports NOT INSTALLED when no cron binary exists" {
  UNATTENDED=Y
  local old_path="$PATH"
  PATH="$MOCKS_DIR"
  run check_cron_daemon
  PATH="$old_path"
  [ "$status" -eq 3 ]
  [[ "$output" == *"[NOT INSTALLED]"* ]]
}

@test "check_cron_daemon: reports NOT RUNNING when a cron binary exists" {
  UNATTENDED=Y
  local old_path="$PATH"
  local fake_bin="$BATS_TEST_TMPDIR/bin"
  mkdir -p "$fake_bin"
  printf '#!/bin/bash\n' > "$fake_bin/crond"
  chmod +x "$fake_bin/crond"
  PATH="$fake_bin:$MOCKS_DIR"
  run check_cron_daemon
  PATH="$old_path"
  [ "$status" -eq 3 ]
  [[ "$output" == *"[NOT RUNNING]"* ]]
}

@test "check_cron_daemon: suggests the package for the detected OS" {
  UNATTENDED=Y
  SO=redhat
  run check_cron_daemon
  [[ "$output" == *"yum install -y cronie"* ]]
  SO=ubuntu
  run check_cron_daemon
  [[ "$output" == *"apt-get install -y cron"* ]]
}

@test "check_cron_daemon: interactive defaults to stopping" {
  UNATTENDED=N
  run check_cron_daemon <<< ""
  [ "$status" -eq 3 ]
}

@test "check_cron_daemon: interactive continues when the operator answers y" {
  UNATTENDED=N
  run check_cron_daemon <<< "y"
  [ "$status" -eq 0 ]
}

@test "check_cron_daemon: ZMBKP_SKIP_CRON_CHECK bypasses the check" {
  UNATTENDED=Y
  ZMBKP_SKIP_CRON_CHECK=Y
  run check_cron_daemon
  [ "$status" -eq 0 ]
  [[ "$output" == *"[SKIPPED]"* ]]
}

@test "check_cron_daemon: an upgrade only warns when no daemon is running" {
  UNATTENDED=Y
  UPGRADE=Y
  run check_cron_daemon
  [ "$status" -eq 0 ]
  [[ "$output" == *"Upgrading anyway"* ]]
}

@test "cron_daemon_running: finds cron in /proc when pgrep is not installed" {
  local old_path="$PATH"
  local fake_bin="$BATS_TEST_TMPDIR/nopgrep"
  local fake_proc="$BATS_TEST_TMPDIR/proc"
  mkdir -p "$fake_bin" "$fake_proc/1" "$fake_proc/42"
  ln -s "$(command -v grep)" "$fake_bin/grep"
  echo systemd > "$fake_proc/1/comm"
  echo crond > "$fake_proc/42/comm"
  PATH="$fake_bin"
  run cron_daemon_running "$fake_proc"
  PATH="$old_path"
  [ "$status" -eq 0 ]
}

@test "cron_daemon_running: reports no cron in /proc when pgrep is not installed" {
  local old_path="$PATH"
  local fake_bin="$BATS_TEST_TMPDIR/nopgrep"
  local fake_proc="$BATS_TEST_TMPDIR/proc"
  mkdir -p "$fake_bin" "$fake_proc/1" "$fake_proc/42"
  ln -s "$(command -v grep)" "$fake_bin/grep"
  echo systemd > "$fake_proc/1/comm"
  echo crontab > "$fake_proc/42/comm"
  PATH="$fake_bin"
  run cron_daemon_running "$fake_proc"
  PATH="$old_path"
  [ "$status" -eq 1 ]
}
