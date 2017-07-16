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
    echo "Dependencies installed with success!"
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
    echo "Dependencies installed with success!"
  else
    echo "Dependencies wasn't installed in your server"
    echo "Please check if you have connection with the internet and yum is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "yum install -y epel-release && yum install -y parallel wget httpie"
    exit $ERR_DEPNOTFOUND
  fi
}

################################################################################
# check_demp: Check if all the dependencies are recognized by the OSE_USER
################################################################################
function check_demp() {
  STATUS=0
  printf "\n\nChecking system for dependencies...\n\n"

  ## Check if the OSE_USER has access to the package wget
  printf "  wget...                 "
  su - $OSE_USER -c "which wget" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  ## Check if the OSE_USER has access to the package parallel
  printf "  parallel...             "
  su - $OSE_USER -c "which parallel" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  ## Check if the OSE_USER has access to the package httpie
  printf "  httpie...                 "
  su - $OSE_USER -c "which httpie" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  ## Check if the OSE_USER has access to the package grep
  printf "  grep...                "
  su - $OSE_USER -c "which grep" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  ## Check if the OSE_USER has access to the package date
  printf "  date...                 "
  su - $OSE_USER -c "which date" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  ## Check if the OSE_USER has access to the package crond
  printf "  cron...                 "
  su - $OSE_USER -c "which crontab" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  ## Check if the OSE_USER has access to the package ldap-utils
  printf "  ldap-utils...	          "
  su - $OSE_USER -c "which ldapsearch" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi
  }

  ## Check if the OSE_USER has access to the package mktemp
  printf "  mktemp...               "
  su - $OSE_USER -c "which mktemp" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[OK]\n"
  else
    printf "[NOT FOUND]\n"
    STATUS=$ERR_DEPNOTFOUND
  fi

  if [[ $STATUS -ne 0 ]]; then
  	echo ""
  	echo "Some dependencies are missing for the $OSE_USER's PATH variable."
  	echo "Please correct the problem and run the installer again."
  	exit $ERR_DEPNOTFOUND
  fi
}
