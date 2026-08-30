#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${INSTALLER_JAVA_DIR}/depDownload.sh"
}

# ---------------------------------------------------------------------------
# install_java_ubuntu
# ---------------------------------------------------------------------------

@test "install_java_ubuntu: succeeds when apt succeeds" {
  export MOCK_APT_FAIL=0
  run install_java_ubuntu
  [ "$status" -eq 0 ]
  [[ "$output" == *"success"* ]]
}

@test "install_java_ubuntu: exits ERR_DEPNOTFOUND when apt fails" {
  export MOCK_APT_FAIL=1
  run install_java_ubuntu
  [ "$status" -eq "$ERR_DEPNOTFOUND" ]
  [[ "$output" == *"wasn't installed"* ]]
}

@test "install_java_ubuntu: prints manual command hint naming the JDK package" {
  export MOCK_APT_FAIL=1
  run install_java_ubuntu
  [[ "$output" == *"openjdk-21-jdk"* ]]
}

# ---------------------------------------------------------------------------
# install_java_redhat
# ---------------------------------------------------------------------------

@test "install_java_redhat: succeeds when yum succeeds" {
  export MOCK_YUM_FAIL=0
  run install_java_redhat
  [ "$status" -eq 0 ]
  [[ "$output" == *"success"* ]]
}

@test "install_java_redhat: exits ERR_DEPNOTFOUND when yum fails" {
  export MOCK_YUM_FAIL=1
  run install_java_redhat
  [ "$status" -eq "$ERR_DEPNOTFOUND" ]
  [[ "$output" == *"wasn't installed"* ]]
}

@test "install_java_redhat: prints manual command hint naming the JDK package" {
  export MOCK_YUM_FAIL=1
  run install_java_redhat
  [[ "$output" == *"java-21-openjdk-devel"* ]]
}
