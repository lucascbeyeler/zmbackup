#!/bin/bash

if [[ $1 == "--help" ]] || [[ $1 == "-h" ]]; then
  source installScriptJava/vars.sh
  source installScriptJava/help.sh
  show_help_java
  exit "$ERR_OK"
fi

echo "Loading installer - PLEASE WAIT"
source installScript/check.sh
source installScript/menu.sh
source installScript/deploy.sh
source installScriptJava/vars.sh
source installScriptJava/check.sh
source installScriptJava/depDownload.sh
source installScriptJava/build.sh
source installScriptJava/deploy.sh
source installScriptJava/menu.sh
source installScriptJava/help.sh

check_env "$1"
check_java_runtime

if [[ $1 == "--remove" ]] || [[ $1 == "-r" ]]; then
  if [[ $UNINSTALL = "Y" ]]; then
    uninstall_java
    echo "Uninstall completed. Thanks for using Zmbackup. Have a nice day!"
    exit "$ERR_OK"
  else
    echo "Zmbackup (Java) is not installed - nothing to do"
    exit "$ERR_OK"
  fi
fi

contract
if [[ $NEED_JAVA == "Y" ]]; then
  if [[ $SO = "ubuntu" ]]; then
    install_java_ubuntu
  else
    install_java_redhat
  fi
fi

if [[ $UPGRADE = "Y" ]]; then
  build_jar
  deploy_upgrade_java
else
  set_values_java
  check_config_java
  build_jar
  deploy_new_java
fi

read -r -p "Install completed. Do you want to display the README file? (Y/n)" tmp
case "$tmp" in
	y|Y|Yes|"") less "$MYDIR"/README.md;;
	*) echo "Done!";;
esac

clear
exit "$ERR_OK"
