#!/usr/bin/env bats

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

@test "launcher: runs the java the installer pinned instead of the first one on PATH" {
  local launcher="$BATS_TEST_TMPDIR/zmbackup"
  printf '#!/bin/bash\necho "pinned java $*"\n' > "$BATS_TEST_TMPDIR/pinned-java"
  chmod +x "$BATS_TEST_TMPDIR/pinned-java"
  sed "s|^ZMBKP_JAVA_BIN=.*|ZMBKP_JAVA_BIN=$BATS_TEST_TMPDIR/pinned-java|" "$LAUNCHER" > "$launcher"
  chmod +x "$launcher"
  run "$launcher" list
  [ "$status" -eq 0 ]
  [[ "$output" == "pinned java -jar /usr/local/lib/zmbackup/zmbackup.jar list" ]]
}
