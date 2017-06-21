#!/bin/bash
################################################################################

################################################################################
# install_ubuntu: Install all the dependencies in Ubuntu Server
################################################################################
function install_ubuntu() {
  echo "Installing dependencies. Please wait..."
  apt-get update > /dev/null 2>&1
  apt-get install -y parallel wget httpie > /dev/null 2>&1
  if [[ $? -eq 0 ]]; then
    echo "Dependencies installed with success! Procceding with the installation"
  else
    echo "Dependencies wasn't installed in your server"
    echo "Please check if you have connection with the internet and apt-get is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "apt-get update && apt-get install -y parallel wget httpie"
    exit $ERR_DEPNOTFOUND
  fi
}

################################################################################
# install_redhat: Install all the dependencies in Red Hat and CentOS
################################################################################
function install_redhat() {
  echo "Installing dependencies. Please wait..."
  yum install -y epel-release  > /dev/null 2>&1
  yum install -y parallel wget httpie  > /dev/null 2>&1
  if [[ $? -eq 0 ]]; then
    echo "Dependencies installed with success! Procceding with the installation"
  else
    echo "Dependencies wasn't installed in your server"
    echo "Please check if you have connection with the internet and yum is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "yum install -y epel-release && yum install -y parallel wget httpie"
    exit $ERR_DEPNOTFOUND
  fi
}
