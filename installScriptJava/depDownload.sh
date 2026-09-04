#!/bin/bash

function install_java_ubuntu() {
  echo "Installing Java $JAVA_MIN_VERSION JDK. Please wait..."
  apt update > /dev/null 2>&1
  apt install -y "openjdk-${JAVA_MIN_VERSION}-jdk" > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Java $JAVA_MIN_VERSION JDK installed with success!"
  else
    echo "Java $JAVA_MIN_VERSION JDK wasn't installed in your server"
    echo "Please check if you have connection with the internet and apt is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "apt update && apt install -y openjdk-${JAVA_MIN_VERSION}-jdk"
    exit "$ERR_DEPNOTFOUND"
  fi
}

function install_java_redhat() {
  echo "Installing Java $JAVA_MIN_VERSION JDK. Please wait..."
  yum install -y "java-${JAVA_MIN_VERSION}-openjdk-devel" > /dev/null 2>&1
  BASHERRCODE=$?
  if [[ $BASHERRCODE -eq 0 ]]; then
    echo "Java $JAVA_MIN_VERSION JDK installed with success!"
  else
    echo "Java $JAVA_MIN_VERSION JDK wasn't installed in your server"
    echo "Please check if you have connection with the internet and yum is"
    echo "working and try again."
    echo "Or you can try manual execute the command:"
    echo "yum install -y java-${JAVA_MIN_VERSION}-openjdk-devel"
    exit "$ERR_DEPNOTFOUND"
  fi
}
