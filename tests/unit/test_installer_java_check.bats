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

@test "check_java_runtime: sets NEED_JAVA=N when an OpenJDK 21 runtime is present" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "21.0.5" 2024-10-15
OpenJDK Runtime Environment (build 21.0.5+11)'
  check_java_runtime
  [ "$NEED_JAVA" = "N" ]
}

@test "check_java_runtime: reports OK when an OpenJDK 21 runtime is present" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "21.0.5" 2024-10-15'
  run check_java_runtime
  [[ "$output" == *"OK"* ]]
}

@test "check_java_runtime: sets NEED_JAVA=Y when the runtime is older than 21" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "17.0.9" 2023-10-17'
  check_java_runtime
  [ "$NEED_JAVA" = "Y" ]
}

@test "check_java_runtime: reports TOO OLD when the runtime is older than 21" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "17.0.9" 2023-10-17'
  run check_java_runtime
  [[ "$output" == *"TOO OLD"* ]]
}

@test "check_java_runtime: accepts a newer major version than the minimum" {
  export MOCK_JAVA_VERSION_OUTPUT='openjdk version "23.0.1" 2024-10-15'
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
