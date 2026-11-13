#!/bin/bash

function show_help_java (){
  printf "usage: install-java.sh [options]"

  printf "\n\nThis installs zmbackup. It builds zmbackup.jar with the bundled Gradle"
  printf "\nwrapper (needs a Java %s JDK - installed automatically if missing, unless" "$JAVA_MIN_VERSION"
  printf "\n--no-install-deps is given - and internet access on first run), then"
  printf "\ninstalls it and a thin launcher, config, blocked list and cron file."

  printf "\n\nOptions:\n"

  printf "\n -r,  --remove       : Uninstall the Java build of Zmbackup and remove all the files"
  printf "\n --force-upgrade     : Force install-java.sh to rebuild and upgrade your installation - does not remove the configuration files."
  printf "\n                       Adds any setting the configuration is missing, asking for it unless --unattended, after saving a .bak copy."
  printf "\n -u,  --unattended   : Run without any prompt, for automation tools such as Ansible. Passing it accepts the GPLv3"
  printf "\n                       license agreement. Settings are read from environment variables instead of being asked:"
  printf "\n                       OSE_USER, OSE_INSTALL_DIR, OSE_INSTALL_ADDRESS, OSE_INSTALL_LDAPPASS, OSE_DEFAULT_BKP_DIR,"
  printf "\n                       ZMBKP_METADATA_DIR, ZMBKP_REST_ADMIN, ZMBKP_REST_ADMIN_PASS (required), ZMBKP_MAIL_ALERT,"
  printf "\n                       ZMBKP_MAIL_SENDER, MAX_PARALLEL_PROCESS, ROTATE_TIME, LOCK_BACKUP,"
  printf "\n                       ZMBKP_REST_PORT, ZMBKP_SKIP_CRON_CHECK, ZMBKP_CA_CERT. Combine with --force-upgrade or --remove;"
  printf "\n                       an unattended --remove keeps the metadata database and the backup storage."
  printf "\n --no-install-deps   : Only check the dependencies (Java %s JDK) and stop with exit code 3 if one is missing or too old," "$JAVA_MIN_VERSION"
  printf "\n                       instead of installing it with apt or yum. Combines with --unattended and --force-upgrade."
  printf "\n -h,  --help         : Show this help"

  printf "\n\n\n"
}
