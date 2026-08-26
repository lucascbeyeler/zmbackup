#!/usr/bin/env bats
# Unit tests for the thin bash launcher installed at /usr/local/bin/zmbackup.

load '../setup'

LAUNCHER="${PROJECT_ROOT}/app/src/main/scripts/zmbackup"

setup() {
  setup_mock_path
}

@test "launcher: runs java against the installed jar path" {
  run "$LAUNCHER" --version
  [ "$status" -eq 0 ]
  [[ "$output" == *"-jar /usr/local/lib/zmbackup/zmbackup.jar"* ]]
}

@test "launcher: forwards all arguments to java" {
  run "$LAUNCHER" backup full user@example.com
  [ "$status" -eq 0 ]
  [[ "$output" == *"backup full user@example.com"* ]]
}

@test "launcher: exits with java's exit code" {
  export MOCK_JAVA_EXIT=3
  run "$LAUNCHER"
  [ "$status" -eq 3 ]
}
