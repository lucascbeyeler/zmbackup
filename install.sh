#!/bin/bash
################################################################################
# install - Install script to help you install zmbackup in your server. You can
#           simply ignore this file and move the files to the correctly place, but
#           the chance for this goes wrong is big. So, this script made everything
#           for you easy.
#
################################################################################
# LOADING INSTALL LIBRARIES
################################################################################
source installScript/check.sh
source installScript/depDownload.sh
source installScript/deploy.sh
source installScript/menu.sh
source installScript/vars.sh
source installScript/help.sh

################################################################################
# INSTALL MAIN CODE
################################################################################
check_env $1

#
#  Help code
################################################################################
if [[ $1 == "--help" ]] || [[ $1 == "-h" ]]; then
  show_help
  exit 0
fi
#
#  Uninstall code
################################################################################
if [[ $1 == "--remove" ]] || [[ $1 == "-r" ]]; then
  if [[ $SO = "ubuntu" ]]; then
    remove_ubuntu
  else
    remove_redhat
  fi
  uninstall
  echo "Uninstall completed. Thanks for using Zmbackup. Have a nice day!"
  exit 0
fi

#
# Install & Upgrade code
################################################################################
contract
if [[ $UPGRADE = "Y" ]]; then
  if [[ $SO = "ubuntu" ]]; then
    install_ubuntu
  else
    install_redhat
  fi
  deploy_upgrade
else
  set_values
  check_config
  if [[ $SO = "ubuntu" ]]; then
    install_ubuntu
  else
    install_redhat
  fi
  deploy_new
fi

# We're done!
read -p "Install completed. Do you want to display the README file? (Y/n)" tmp
case "$tmp" in
	y|Y|Yes|"") less $MYDIR/README.md;;
	*) echo "Done!";;
esac

clear
exit $ERR_OK
