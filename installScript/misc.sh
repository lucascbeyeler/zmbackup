#!/bin/bash
################################################################################

################################################################################
# check_env: Check the environment if everything is okay to begin the install
################################################################################
function check_env() {
  printf "  Root Privileges...	          "
  if [ $(id -u) -ne 0 ]; then
    printf "[NO ROOT]\n"
  	echo "You need root privileges to install zmbackup"
  	exit $ERR_NOROOT
  fi
  printf "  Old Zmbackup Install...	          "
  su - $OSE_USER -c "which zmbackup" > /dev/null 2>&1
  if [ $? = 0 ]; then
    printf "[NEW INSTALL]\n"
  else
    printf "[OLD VERSION] - EXECUTING UPGRADE ROUTINE\n"
  fi
}

################################################################################
# contract: Print the contract and informations about the project to the user
################################################################################
function contract(){
  clear
  echo "##################################################################################"
  echo "#                                                                                #"
  cat << "EOF"
#  M""""""""`M            dP                         dP                          #
#  Mmmmmm   .M            88                         88                          #
#  MMMMP  .MMM 88d8b.d8b. 88d888b. .d8888b. .d8888b. 88  .dP  dP    dP 88d888b.  #
#  MMP  .MMMMM 88'`88'`88 88'  `88 88'  `88 88'  `"" 88888"   88    88 88'  `88  #
#  M' .MMMMMMM 88  88  88 88.  .88 88.  .88 88.  ... 88  `8b. 88.  .88 88.  .88  #
#  M         M dP  dP  dP 88Y8888' `88888P8 `88888P' dP   `YP `88888P' 88Y888P'  #
#  MMMMMMMMMMM                                                         88        #
#                                                                      dP        #
EOF
  echo "#                                                                                #"
  echo "##################################################################################"
  echo "#                                                                                #"
  echo "# Zmbackup is a reliable Bash shell script developed to help you in your daily   #"
  echo "# task to backup and restore mails and accounts from Zimbra Open Source Email    #"
  echo "# Platform. This script is based on another project called Zmbkpose, and         #"
  echo "# completely compatible with the structure if you have plans on migrate from one #"
  echo "# to another.                                                                    #"
  echo "#                                                                                #"
  echo "#         This script was made by the community for the community.               #"
  echo "#                                                                                #"
  echo "##################################################################################"
  echo -e "\n\n"
  echo "##################################################################################"
  echo "#                                                                                #"
  echo "# PLEASE, READ THIS AGREEMENT CAREFULLY BEFORE USING THE SOFTWARE. THIS PROGRAM  #"
  echo "# IS FREE SOFTWARE; YOU CAN REDISTRIBUTE IT AND/OR MODIFY IT UNDER THE TERMS OF  #"
  echo "# VERSION 3 OF THE GNU GENERAL PUBLIC LICENCE AS PUBLISHED BY THE FREE SOFTWARE  #"
  echo "# FOUNDATION.                                                                    #"
  echo "#                                                                                #"
  echo "# THIS PROGRAM IS DISTRIBUTED IN THE HOPE THAT IT WILL BE USEFUL, BUT WITHOUT    #"
  echo "# ANY WARRANT; WITHOUT EVEN THE IMPLIED WARRANTY OF MERCHANTABILITY OR FITNESS   #"
  echo "# FOR A PARTICULAR PURPOSE. SEE THE GNU GENERAL PUBLIC LICENCE FOR MORE DETAILS. #"
  echo "#                                                                                #"
  echo "# LICENSE TERMS FOR THIS ZMBACKUP SOFTWARE:                                      #"
  echo "# https://www.gnu.org/licenses/gpl.md                                            #"
  echo "#                                                                                #"
  echo "##################################################################################"
  echo -e "\n"
  echo "Do you agree with the terms of the software license agreements? [N/y]"
  read OPT
  if [[ $OPT != 'Y' && $OPT != 'y' ]]; then
  	echo "Stoping the installation process..."
  	exit 0
  fi
}
