#!/bin/bash
################################################################################
# check_env_java, check_config_java live in check.sh/menu.sh of the bash
# installer and are reused as-is (see install-java.sh) - root privileges, OS
# detection, and upgrade/uninstall detection do not depend on which language
# zmbackup itself is written in.
################################################################################

################################################################################
# check_java_runtime: Check if a Java 21+ runtime is available. Sets NEED_JAVA
# to Y or N so depDownload.sh knows whether to install one.
################################################################################
function check_java_runtime() {
  printf "  Java %s Runtime...	          " "$JAVA_MIN_VERSION"
  if ! command -v java > /dev/null 2>&1; then
    printf "[NOT FOUND]\n"
    NEED_JAVA="Y"
    return
  fi

  JAVA_VER_OUTPUT=$(java -version 2>&1)
  JAVA_MAJOR=$(echo "$JAVA_VER_OUTPUT" | grep -oE '"[0-9]+' | head -1 | tr -d '"')

  if [[ -z "$JAVA_MAJOR" ]]; then
    printf "[UNKNOWN VERSION]\n"
    NEED_JAVA="Y"
  elif [[ "$JAVA_MAJOR" -lt "$JAVA_MIN_VERSION" ]]; then
    printf "[TOO OLD - %s]\n" "$JAVA_MAJOR"
    NEED_JAVA="Y"
  else
    printf "[OK - %s]\n" "$JAVA_MAJOR"
    NEED_JAVA="N"
  fi
}
