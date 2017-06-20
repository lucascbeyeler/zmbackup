<h1 align="center">
  <img src="https://www.beyeler.com.br/wp-content/uploads/2017/06/zmbackup.png" alt="Markdownify">
</h1>


Zmbackup - Backup Script for Zimbra OSE
=========

Zmbackup is a reliable Bash shell script developed to help you in your daily task to backup and restore mails and accounts from Zimbra Open Source Email Platform. This script is based on another project called [Zmbkpose](https://github.com/bggo/Zmbkpose), and completely compatible with the structure if you have plans on migrate from one to another.

[![Build Status](https://travis-ci.org/lucascbeyeler/zmbackup.svg?branch=master)](https://travis-ci.org/lucascbeyeler/zmbackup)
[![Zimbra Version](https://img.shields.io/badge/Zimbra%20OSE-8.7.11-orange.svg)](https://www.zimbra.com/downloads/zimbra-collaboration-open-source/)
![Linux Distro](https://img.shields.io/badge/platform-CentOS%20%7C%20Red%20Hat%20%7C%20Ubuntu-blue.svg)

Features
------------
* Online Backup and Restore - no need to stop the base to do;
* Backup routines for one, many, or all mailbox, accounts, alias and distribution lists;
* Restore the routines in your respective places, or inside another account using Restore on Account;
* Multithreading - Execute each rotine quickly as possible;
* Have some insights about eacho backup routine;
* Receive alert everytime a backup session begins;
* Better internal garbage manager;
* Filter the accounts that should not be execute with blacklists;
* Log management compatible with rsyslog.

Requirements
------------

* **GNU Wget** - a computer program that retrieves content from web servers;
* **GNU Parallel** - a shell tool for executing jobs in parallel using one or more CPU;
* **HTTPie** - a command line HTTP client with an intuitive UI, JSON support, syntax highlighting, wget-like downloads, plugins, and more.
* **GNU grep** - a command-line utility for searching plain-text data sets for lines matching a regular expression;
* **date** - command used to print out, or change the value of, the system's time and date information;
* **cron** - a time-based job scheduler in Unix-like computer operating systems.
* **epel-release** - ONLY CentOS users! This package contains the repository epel, where we need to use to download HTTPie and GNU Parallel;

Instalation
------------

If you use CentOS, first install the package **[epel-release](https://fedoraproject.org/wiki/EPEL)**, as we will need this repository to download part of the dependencies.

```
# yum install epel-release
```

Now, install the packages parallel, wget and httpie in your server. You don't need to install grep, date and cron, because they are already part of all GNU/Linux distros.

```
# apt-get install parallel wget httpie
# yum install parallel wget httpie
```

Download the latest package with the BETA tag in "Release" section, or git clone the development branch:

```
git clone -b dev https://github.com/lucascbeyeler/zmbackup.git
```

Inside the project folder, execute the script wizard.sh and follow all the instructions to install the project. To validate if the script is installed, change to your server's zimbra user and execute zmbackup -v.

```
# cd zmbackup
# ./wizard.sh
$ zmbackup -v
  zmbackup version: 1.2.0 BETA
```

Usage
------------

To check all the options available to Zmbackup, just execute zmbackup -h or zmbackup --help. This will return for you a list with all the options, what each one of them does, and the syntax.

```
$ zmbackup -h
usage: zmbackup [-f] [options] <mail>
       zmbackup [-i] <mail>
       zmbackup [-r] [options] <session> <mail>
       zmbackup [-r] [-ro] <session> <mail_origin> <mail_destination>
       zmbackup [-d] <session>

Options:

 -f, --full                     : Execute full backup of an account, a list of accounts, or all accounts.
 -i, --incremental              : Execute incremental backup for an account, a list of accounts, or all accounts.
 -l, --list                     : List all backup sessions that still exist in your disk.
 -r, --restore                  : Restore the backup inside the users account.
 -d, --delete                   : Delete a session of backup.
 -hp, --housekeep               : Execute the Housekeep to remove old sessions - Zmbhousekeep
 -v, --version                  : Show the zmbackup version.

Full Backup Options:

 -m,   --mail                   : Execute a backup of an account, but only the mailbox.
 -dl,  --distributionlist       : Execute a backup of a distributionlist instead of an account.
 -al,  --alias                  : Execute a backup of an alias instead of an account.
 -ldp, --ldap                   : Execute a backup of an account, but only the ldap entry.

Restore Backup Options:

 -dl, --distributionlist        : Execute a restore of a distributionlist instead of an account.
 -al, --alias                   : Execute a restore of an alias instead of an account.
 -m, --mail                     : Execute a restore of an account,  but only the mailbox.
 -ldp, --ldap                   : Execute a restore of an account, but only the ldap entry.
 -ro, --restoreOnAccount        : Execute a restore of an account inside another account.

```

To execute a full backup routine, which include by default the mailbox and the ldiff, just run the script with the option -f or --full. Depending of the ammount of accounts or the number of proccess you set in the option **MAX_PARALLEL_PROCESS**, this will take sometime before conclude.

License
-------

GNU GENERAL PUBLIC LICENSE

Author Information
------------------

https://github.com/lucascbeyeler
