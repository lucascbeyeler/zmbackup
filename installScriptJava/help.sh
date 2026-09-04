#!/bin/bash

function show_help_java (){
  printf "usage: install-java.sh [options]"

  printf "\n\nThis installs zmbackup. It builds zmbackup.jar with the bundled Gradle"
  printf "\nwrapper (needs a Java %s JDK - installed automatically if missing - and" "$JAVA_MIN_VERSION"
  printf "\ninternet access on first run), then installs it and a thin launcher,"
  printf "\nconfig, blocked list and cron file."

  printf "\n\nOptions:\n"

  printf "\n -r,  --remove       : Uninstall the Java build of Zmbackup and remove all the files"
  printf "\n --force-upgrade     : Force install-java.sh to rebuild and upgrade your installation - does not remove the configuration files."
  printf "\n -h,  --help         : Show this help"

  printf "\n\n\n"
}
