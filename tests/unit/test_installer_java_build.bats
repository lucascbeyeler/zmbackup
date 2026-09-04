#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${INSTALLER_JAVA_DIR}/build.sh"

  BUILD_ROOT="$(mktemp -d)"
  MYDIR="$BUILD_ROOT"
  mkdir -p "${BUILD_ROOT}/app/build/libs"
}

teardown() {
  rm -rf "${BUILD_ROOT:-}"
}

write_gradlew() {
  cat > "${BUILD_ROOT}/gradlew" <<EOF
#!/bin/bash
$1
EOF
  chmod +x "${BUILD_ROOT}/gradlew"
}

@test "build_jar: succeeds when gradlew produces the jar" {
  write_gradlew "mkdir -p app/build/libs && touch app/build/libs/${ZMBKP_JAR_NAME}; exit 0"
  run build_jar
  [ "$status" -eq 0 ]
  [ -f "${BUILD_ROOT}/app/build/libs/${ZMBKP_JAR_NAME}" ]
}

@test "build_jar: reports the produced jar path on success" {
  write_gradlew "mkdir -p app/build/libs && touch app/build/libs/${ZMBKP_JAR_NAME}; exit 0"
  run build_jar
  [[ "$output" == *"Build completed"* ]]
}

@test "build_jar: exits ERR_BUILD_FAILED when gradlew fails" {
  write_gradlew "exit 1"
  run build_jar
  [ "$status" -eq "$ERR_BUILD_FAILED" ]
  [[ "$output" == *"Gradle build failed"* ]]
}

@test "build_jar: exits ERR_BUILD_FAILED when gradlew succeeds but the jar is missing" {
  write_gradlew "exit 0"
  run build_jar
  [ "$status" -eq "$ERR_BUILD_FAILED" ]
  [[ "$output" == *"was not produced"* ]]
}
