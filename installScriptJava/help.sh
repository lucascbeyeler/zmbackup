#!/bin/bash
################################################################################
# Command Help Option - Java installer
################################################################################

################################################################################
# show_help_java: It will show a quick help about each command from
# install-java.sh
################################################################################
function show_help_java (){
  printf "usage: install-java.sh [options]"

  printf "\n\nThis installs the Java build of zmbackup (2.0). It builds zmbackup.jar with"
  printf "\nthe bundled Gradle wrapper (needs a Java %s JDK - installed automatically if" "$JAVA_MIN_VERSION"
  printf "\nmissing - and internet access on first run), then installs it and a thin"
  printf "\nlauncher, config, blocked list and cron file the same way install.sh does"
  printf "\nfor the bash build."

  printf "\n\nOptions:\n"

  printf "\n -r,  --remove       : Uninstall the Java build of Zmbackup and remove all the files"
  printf "\n --force-upgrade     : Force install-java.sh to rebuild and upgrade your installation - does not remove the configuration files."
  printf "\n -h,  --help         : Show this help"

  printf "\n\n\n"
}
