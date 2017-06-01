![](http://www.beyeler.com.br/wp-content/uploads/2017/03/logo.png)

zmbackup
=========

zmbackup is a fork from Zmbkpose, a Bash shell script created to suppress the need for backup in Zimbra Collaboration Suite Opensource. As the project is dead, with no update or correction, I took the code, made the necessary corrections to work again and include new functions that was missing. So zmbackup is entirelly compatible with zmbkpose, just changing the binary and is ready to go!

[![Build Status](https://travis-ci.org/lucascbeyeler/zmbackup.svg?branch=master)](https://travis-ci.org/lucascbeyeler/zmbackup)

Requirements
------------

* Zimbra Collaboration Suite Opensource 8.6 or higher;
* GNU Wget - a computer program that retrieves content from web servers;
* GNU Parallel - a shell tool for executing jobs in parallel using one or more CPU;
* cURL - a command line tool and library for transferring data with URL syntax;
* GNU grep - a command-line utility for searching plain-text data sets for lines matching a regular expression;
* date - command used to print out, or change the value of, the system's time and date information;
* cron - a time-based job scheduler in Unix-like computer operating systems.

Instalation
------------

First, install all the required packages in your system:

```
apt-get install parallel wget curl grep date
```

After that, download and unpack a stable release of zmbackup in "Release" section, or git clone the Master branch:

```
git clone https://github.com/lucascbeyeler/zmbackup.git
```

Enter inside the folder zmbackup and execute the script install.sh. Follow the instructions and then, to validate, execute the command "zmbackup -v" as zimbra user. The command should execute correctly:

```
$ zmbackup -v
zmbackup version: 1.1.5
```

Open the folder /etc/cron.d/zmbackup.cron and adjust each job scheduled to the time you want the execution. If you configured zmbkpose or any old release before, please undo and use this file for scheduling.
````
$ vim /etc/cron.d/zmbackup.cron
###############################################################################
#                             ZMBACKUP CRON FILE                              #
###############################################################################
# This file is used to manage the time and day each backup activity will be
# executed. Please modify this file rather than create a new one.
# Default values for each activity:
#       Full Backup: Every Sunday at 1 AM
#       Incremental Backup: From Monday to Saturday at 1 AM
#       Alias: Every day at 1 AM
#       Distribution List: Every day at 1 AM
#       Backup Rotation: Every day at Midnight
###############################################################################
SHELL=/bin/bash
PATH=/sbin:/bin:/usr/sbin:/usr/bin:/usr/local/bin
MAILTO=root
0  2 * * 0     zimbra    zmbackup -f
0  2 * * 1-6   zimbra    zmbackup -i
0  1 * * *     zimbra    zmbackup -f -dl
30 1 * * *     zimbra    zmbackup -f -al
0  0 * * *     zimbra    zmhousekeep
````

Keep in mind that the script zmhousekeep is the one who is going to rotate your backups inside the folder, and do the cleaning inside each folder. Configure him to execute before the zmbackup proccess, because release the space for the next proccess, and is more quickly than the others.

Documentation
------------

TO DO

License
-------

GNU GENERAL PUBLIC LICENSE

Author Information
------------------

https://github.com/lucascbeyeler
