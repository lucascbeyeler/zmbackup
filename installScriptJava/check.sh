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

function check_cron_daemon() {
  printf "  Cron Daemon...	          "
  if [[ $ZMBKP_SKIP_CRON_CHECK == "Y" ]]; then
    printf "[SKIPPED]\n"
    return
  fi
  if pgrep -x cron > /dev/null 2>&1 || pgrep -x crond > /dev/null 2>&1; then
    printf "[RUNNING]\n"
    return
  fi

  if command -v cron > /dev/null 2>&1 || command -v crond > /dev/null 2>&1; then
    printf "[NOT RUNNING]\n"
  else
    printf "[NOT INSTALLED]\n"
  fi
  echo "No cron daemon is running, so the backups scheduled in $ZMBKP_CRON_FILE would never run."
  if [[ $SO == "redhat" ]]; then
    echo "To fix it: yum install -y cronie && systemctl enable --now crond"
  else
    echo "To fix it: apt-get install -y cron && systemctl enable --now cron"
  fi

  if [[ $UNATTENDED == "Y" ]]; then
    echo "[FAIL] - Start a cron daemon, or set ZMBKP_SKIP_CRON_CHECK=Y to install without one."
    exit "$ERR_DEPNOTFOUND"
  fi

  printf "Continue without a cron daemon? [y/N]"
  read -r OPT
  if [[ $OPT != 'y' && $OPT != 'Y' ]]; then
    exit "$ERR_DEPNOTFOUND"
  fi
}
