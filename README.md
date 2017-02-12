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
* date - command used to print out, or change the value of, the system's time and date information.

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
zmbackup version: 1.0.0 Release Candidate
```

Documentation
------------

TO DO

License
-------

GNU GENERAL PUBLIC LICENSE

Author Information
------------------

https://github.com/lucascbeyeler
