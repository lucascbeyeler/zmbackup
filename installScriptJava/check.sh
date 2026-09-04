#!/bin/bash

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
