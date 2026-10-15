#!/bin/bash

UNATTENDED="N"
ACTION=""
for ARG in "$@"; do
  case "$ARG" in
    --unattended|-u) UNATTENDED="Y";;
    *) ACTION=${ACTION:-$ARG};;
  esac
done

if [[ $ACTION == "--help" ]] || [[ $ACTION == "-h" ]]; then
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

check_env "$ACTION"
check_java_runtime

if [[ $ACTION == "--remove" ]] || [[ $ACTION == "-r" ]]; then
  if [[ $UNINSTALL = "Y" ]]; then
    uninstall_java
    echo "Uninstall completed. Thanks for using Zmbackup. Have a nice day!"
    exit "$ERR_OK"
  else
    echo "Zmbackup (Java) is not installed - nothing to do"
    exit "$ERR_OK"
  fi
fi

if [[ $UNATTENDED == "Y" ]]; then
  if [[ $UPGRADE != "Y" ]]; then
    check_required_java
  fi
else
  contract
fi

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
  if [[ $UNATTENDED != "Y" ]]; then
    set_values_java
  fi
  check_config_java
  build_jar
  deploy_new_java
fi

if [[ $UNATTENDED == "Y" ]]; then
  echo "Install completed."
  exit "$ERR_OK"
fi

read -r -p "Install completed. Do you want to display the README file? (Y/n)" tmp
case "$tmp" in
	y|Y|Yes|"") less "$MYDIR"/README.md;;
	*) echo "Done!";;
esac

clear
exit "$ERR_OK"
