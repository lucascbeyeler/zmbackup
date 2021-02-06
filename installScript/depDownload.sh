#!/bin/bash
################################################################################

################################################################################
# install_ubuntu: Install all the dependencies in Ubuntu Server
################################################################################
function install_ubuntu() {
  echo "Installing dependencies. Please wait..."
  apt update > /dev/null 2>&1
  apt install -y parallel > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Dependencies installed with success!"
  else
    echo "Dependencies wasn't installed in your server"
    echo "Please check if you have connection with the internet and apt is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "apt update && apt install -y parallel"
    exit "$ERR_DEPNOTFOUND"
  fi
}

################################################################################
# install_redhat: Install all the dependencies in Red Hat and CentOS
################################################################################
function install_redhat() {
  echo "Installing dependencies. Please wait..."
  grep 6 /etc/redhat-release > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    wget -O "/etc/yum.repos.d/tange.repo" "$OLE_TANGE" > /dev/null 2>&1
    BASHERRCODE=$?
    if [[ $BASHERRCODE -ne 0 ]]; then
      echo "Failure - Can't install Tange's repository for Parallel"
      exit "$ERR_NO_CONNECTION"
    fi
  fi
  yum install -y epel-release  > /dev/null 2>&1
  yum install -y parallel  > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Dependencies installed with success!"
  else
    echo "Dependencies wasn't installed in your server"
    echo "Please check if you have connection with the internet and yum is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "yum install -y epel-release && yum install -y parallel"
    exit "$ERR_DEPNOTFOUND"
  fi
}

################################################################################
# remove_ubuntu: Remove all the dependencies in Ubuntu Server
################################################################################
function remove_ubuntu() {
  echo "Removing dependencies. Please wait..."
  apt --purge remove -y parallel > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Dependencies removed with success!"
  else
    echo "Dependencies wasn't removed in your server"
    echo "Please check if you have connection with the internet and apt is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "apt remove -y parallel"
  fi
}

################################################################################
# remove_redhat: Install all the dependencies in Red Hat and CentOS
################################################################################
function remove_redhat() {
  echo "Removing dependencies. Please wait..."
  grep 6 /etc/redhat-release > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    pip uninstall -y curl > /dev/null 2>&1
  fi
  yum remove -y parallel > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Dependencies removed with success!"
  else
    echo "Dependencies wasn't removed in your server"
    echo "Please check if you have connection with the internet and yum is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "yum install -y epel-release && yum install -y parallel"
  fi
}
