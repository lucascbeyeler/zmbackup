# Installation

## Requirements
Zmbackup is a very powerful software with an easy install process. Before you install the script in your environment, you need to check if your distribution and Zimbra version is compatible.

The following distros are tested:
- Ubuntu Server 16.04 -  ![Support](https://img.shields.io/badge/Support-Release%20Candidate-yellow.svg)
- CentOS 7 - ![Support](https://img.shields.io/badge/Support-BETA%201-red.svg)

The following versions of Zmbackup are tested:
- Zmbackup 8.7.X -  ![Support](https://img.shields.io/badge/Support-Release%20Candidate-yellow.svg)

The following packages are required to be installed, but Zmbackup can handle the install process if you let it do:


* **GNU Wget** - a computer program that retrieves content from web servers;
* **GNU Parallel** - a shell tool for executing jobs in parallel using one or more CPU;
* **HTTPie** - a command line HTTP client with an intuitive UI, JSON support, syntax highlighting, wget-like downloads, plugins, and more.
* **GNU grep** - a command-line utility for searching plain-text data sets for lines matching a regular expression;
* **date** - command used to print out, or change the value of, the system's time and date information;
* **cron** - a time-based job scheduler in Unix-like computer operating systems;
* **epel-release** - ONLY CentOS users! This package contains the repository epel, where we need to use to download HTTPie and GNU Parallel;
* **ldap-utils** - a package that includes a number of utilities that can be used to perform queries on the LDAP server;
* **mktemp** - make a temporary file or directory;
* **SQLite3** - a relational database management system contained in a C programming library.

## Pre-install Process

If you use CentOS, first install the package **[epel-release](https://fedoraproject.org/wiki/EPEL)**, as we will need this repository to download part of the dependencies.

```
# yum install epel-release
```

Now, install the packages **parallel**, **wget**, **sqlite3**, and **httpie** in your server. You don't need to install grep, date, mktemp and cron, because they are already part of all GNU/Linux distros. **ldap-utils** is need to be installed only if you do a separate server for Zmbackup, otherwise Zimbra OSE is already deployed with this package;

```
# apt-get install parallel wget httpie sqlite3
# yum install parallel wget httpie sqlite3
```

Download the latest package with the BETA tag in "Release" section, or git clone the development branch:

```
git clone -b 1.2-version https://github.com/lucascbeyeler/zmbackup.git
```

## Install script

The install.sh is the script used to install/upgrade/remove the Zmbackup inside your server. The script was made to replace the zmbackup_wizard, because the later one wasn't very smart when peoples decide to stop the installation in the middle of the execution.

## Options

Parameter       | Required | Description
----------------|----------|-----------------------------------------------
-h, --help      | no       | Show all the available options for install.sh
-r, --remove    | no       | Uninstall Zmbackup from the machine
--force-upgrade | no       | Force the upgrade routine

### Installation Process

Inside the project folder, execute the script **install.sh** and follow all the instructions to install the project. To validate if the script is installed, change to your server's zimbra user and execute zmbackup -v.

```
# cd zmbackup
# ./install.sh
# su - zimbra
$ zmbackup -v
  zmbackup version: 1.2.0 Release Candidate
```

### Upgrade/Downgrade Process

The upgrade/downgrade can be done downloading the latest release of Zmbackup to your machine and running the script **install.sh** again. If the release installed is different than the one you are trying to install, the script will execute and modify your environment. To validate if the script is installed, change to your server's zimbra user and execute zmbackup -v. The version showed should be different from the one installed before.

```
# cd zmbackup
# ./install.sh
# su - zimbra
$ zmbackup -v
  zmbackup version: 1.2.0 Release Candidate
```

### Reinstall

The reinstall process is the same as the Upgrade/Downgrade process. The main difference is that you need to pass an extra paramenter to force the reinstall when the versions are the same. To validate if the script is installed, change to your server's zimbra user and execute zmbackup -v.

```
# cd zmbackup
# ./install.sh --force-upgrade
# su - zimbra
$ zmbackup -v
  zmbackup version: 1.2.0 Release Candidate
```
