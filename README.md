Zmbackup - Backup Script for Zimbra OSE
=========
*** The Project is Dead - if you want to own zmbackup from now own send me a message ***

Zmbackup is a reliable Bash shell script developed to help you in your daily task to backup and restore mails and accounts from Zimbra Open Source Email Platform. This script is based on another project called [Zmbkpose](https://github.com/bggo/Zmbkpose), and completely compatible with the structure if you have plans on migrate from one to another.

For the next version of the tool, please consider support [Waddles](https://github.com/lucascbeyeler/waddles-cli/tree/master).

[![Zimbra Version](https://img.shields.io/badge/Zimbra%20OSE-8.8.15-orange.svg)](https://www.zimbra.com/downloads/zimbra-collaboration-open-source/)
![Linux Distro](https://img.shields.io/badge/platform-CentOS%20%7C%20Red%20Hat%20%7C%20Ubuntu-blue.svg)
![Branch](https://img.shields.io/badge/Branch-Stable-green.svg)
![Release](https://img.shields.io/badge/Release-1.2.6-green.svg)

Features
------------
* Online Backup and Restore - no need to stop the server to do;
* Backup routines for one, many, or all mailbox, accounts, alias and distribution lists;
* Restore the routines in your respective places, or inside another account using Restore on Account;
* Multithreading - Execute each rotine quickly as possible;
* Have some insights about eacho backup routine;
* Receive alert everytime a backup session begins;
* Better internal garbage manager;
* Filter the accounts that should not be execute with blocked lists;
* Log management compatible with rsyslog;
* Sessions stored in a relational database - SQLITE3 only - or TXT file;

Requirements
------------

* **GNU Parallel** - a shell tool for executing jobs in parallel using one or more CPU;
* **GNU grep** - a command-line utility for searching plain-text data sets for lines matching a regular expression;
* **date** - command used to print out, or change the value of, the system's time and date information;
* **cron** - a time-based job scheduler in Unix-like computer operating systems;
* **epel-release** - ONLY CentOS users! This package contains the repository epel, where we need to use to download GNU Parallel;
* **ldap-utils** - a package that includes a number of utilities that can be used to perform queries on the LDAP server;
* **mktemp** - make a temporary file or directory;
* **SQLite3** - a relational database management system contained in a C programming library.

Installation
------------

If you use CentOS, first install the package **[epel-release](https://fedoraproject.org/wiki/EPEL)**, as we will need this repository to download part of the dependencies.

```
# yum install epel-release
```

Now, install the packages **parallel**, **wget**, **sqlite3** and **curl** in your server. You don't need to install grep, date, mktemp and cron, because they are already part of all GNU/Linux distros. **ldap-utils** is need to be installed only if you do a separate server for Zmbackup, otherwise Zimbra OSE is already deployed with this package;

```
# apt-get install parallel wget curl sqlite3
# yum install parallel wget curl sqlite3
```

Download the latest package with the BETA tag in "Release" section, or git clone the development branch:

```
git clone -b 1.2-version https://github.com/lucascbeyeler/zmbackup.git
```

Inside the project folder, execute the script **install.sh** and follow all the instructions to install the project. To validate if the script is installed, change to your server's zimbra user and execute zmbackup -v.

```
# cd zmbackup
# ./install.sh
# su - zimbra
$ zmbackup -v
  zmbackup version: 1.2.6
```

Usage
------------

To check all the options available to Zmbackup, just execute **zmbackup -h** or **zmbackup --help**. This will return for you a list with all the options, what each one of them does, and the syntax.

```
$ zmbackup -h
usage: zmbackup -f [-m,-dl,-al,-ldp, -sig] [-d,-a] <mail/domain>
       zmbackup -i <mail>
       zmbackup -r [-m,-dl,-al,-ldp, -sig] [-d,-a] <session> <mail>
       zmbackup -r [-ro] <session> <mail_origin> <mail_destination>
       zmbackup -d <session>
       zmbackup -m

Options:

 -f,  --full                      : Execute full backup of an account, a list of accounts, or all accounts.
 -i,  --incremental               : Execute incremental backup for an account, a list of accounts, or all accounts.
 -l,  --list                      : List all backup sessions that still exist in your disk.
 -r,  --restore                   : Restore the backup inside the users account.
 -d,  --delete                    : Delete a session of backup.
 -hp, --housekeep                 : Execute the Housekeep to remove old sessions - Zmbhousekeep
 -m,  --migrate                   : Migrate the database from TXT to SQLITE3 and vice versa.
 -v,  --version                   : Show the zmbackup version.
 -h,  --help                      : Show this help

Full Backup Options:

 -m,   --mail                     : Execute a backup of an account, but only the mailbox.
 -dl,  --distributionlist         : Execute a backup of a distributionlist instead of an account.
 -al,  --alias                    : Execute a backup of an alias instead of an account.
 -ldp, --ldap                     : Execute a backup of an account, but only the ldap entry.
 -sig, --signature                : Execute a backup of a signature.
 -d,   --domain                   : Execute a backup of only a set of domains, comma separated
 -a,   --account                  : Execute a backup of only a set of accounts, comma separated

Restore Backup Options:

 -m,   --mail                     : Execute a restore of an account,  but only the mailbox.
 -dl,  --distributionlist         : Execute a restore of a distributionlist instead of an account.
 -al,  --alias                    : Execute a restore of an alias instead of an account.
 -ldp, --ldap                     : Execute a restore of an account, but only the ldap entry.
 -ro,  --restoreOnAccount         : Execute a restore of an account inside another account.
 -sig, --signature                : Execute a restore of a signature.
 -d,   --domain                   : Execute a backup of only a set of domains, comma separated
 -a,   --account                  : Execute a backup of only a set of accounts, comma separated
```

To execute a full backup routine, which include by default the mailbox and the ldiff, just run the script with the option **-f** or **--full**. Depending of the ammount of accounts or the number of proccess you set in the option **MAX_PARALLEL_PROCESS**, this will take sometime before conclude.

```
$ zmbackup -f
```

You can filter for what you want using the options **-m** for Mailbox, **-ldp** for Accounts, **-al** for Alias, and **-dl** for Distribution List. REMEMBER - This options doesn't stack with each other, so don't try -dl and -al at the same time (The script will only broke if you do this).

**CORRECT**
```
$ zmbackup -f -m
```

**INCORRECT**
```
$ zmbackup -f -m -ldp
```

Aside from the full backup action, Zmbackup still have a option to do incremental backups. This works like this: before a incremental be executed, Zmbackup should check the date for the latest routine for each account, and execute a restore action based on that date. At the moment, the incremental will backup the ldap account and the mailbox, and accept no paramenter aside the list of accounts to be backed up.

```
$ zmbackup -i
```

To restore a backup, you use the option **-r** or **--restore**, but this time you should inform the ID session you want to restore. You can check the sessionID with the command zmbackup -l.

```
$ zmbackup -l
+---------------------------+--------------+--------------+----------+----------------------------+
|       Session Name        |    Start     |    Ending    |   Size   |        Description         |
+---------------------------+--------------+--------------+----------+----------------------------+
| full-20180408160227       |  04/08/2018  |  04/08/2018  | 76K      | Full Account               |
| mbox-20180408160808       |  04/08/2018  |  04/08/2018  | 40K      | Mailbox                    |
+---------------------------+--------------+--------------+----------+----------------------------+


$ zmbackup -r full-20170621201603
```

The restoreOnAccount act different of the rest of the restore actions, as you should inform the account you want to restore, and the destination of that account, aside from the sessionID. This will dump all the content inside that account from that session in the destination account.

```
$ zmbackup -r -ro full-20170621201603 slayerofdemons@boletaria.com chosenundead@lordran.com
```

To remove a backup session, you only need to use the option **-d** or **--delete**, and inform the session you want to delete. Or, if you want to remove all the backups before X days, you can use the option **-hp** or **--housekeep** to execute the Housekeep process. **WARNING**: The housekeep can take sometime depending the ammount of data you want to remove.

```
$ zmbackup -d full-20170621201603
$ zmbackup -hp
```

Zmbackup is capable to migrate from TXT to SQLite3, if you want to store you data inside a relational database. The advantage of doing this is more efficience when trying to list the sessions, and more details when you do this (like the beginning and conclusion of the session). To enable the SQLite3, first edit the option SESSION_TYPE insinde zmbackup.conf:

```
# vim /etc/zmbackup/zmbackup.conf
...
SESSION_TYPE=SQLITE3
```

With the SQLITE3 option enabled, now you need to migrate your entire sessions.txt to the relational database using the option **-m** or **--migrate**. After the end of the migration, you can run all zmbackup commands again.

```
$ zmbackup -m
```

**REMEMBER:** at this moment, this migration activity is a only one way road. There is no rollback, and, if you try to do a rollback, you will lost your sessions file.

Scheduling backups
------------

The installer script automatically creates a cron config file in `/etc/cron.d/zmbackup`. You can customize backup routines editing that file.


Want to contribute to the project?
------------------
* Please help us contributing the Waddles project instead - Zmbackup will be deprecated and the only thing we will do here will be bugfixes.

License
-------

[![GNU GPL v3.0](http://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl.html)

View official GNU site <http://www.gnu.org/licenses/gpl.html>.

Author Information
------------------

* [Lucas Costa Beyeler](https://github.com/lucascbeyeler) - lucas.costab@outlook.com
