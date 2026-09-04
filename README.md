# Zmbackup - Backup Script for Zimbra OSE

Zmbackup is a reliable tool developed to help you in your daily task to backup and restore mails and accounts from Zimbra Open Source Email Platform. It's based on another project called [Zmbkpose](https://github.com/bggo/Zmbkpose), and completely compatible with the structure if you have plans on migrate from one to another.

![Linux Distro](https://img.shields.io/badge/platform-Rocky%20Linux%20%7C%20Red%20Hat%20%7C%20Ubuntu-blue.svg)
![Branch](https://img.shields.io/badge/Branch-Stable-green.svg)
![Release](<https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Flucascbeyeler%2Fzmbackup%2F1.2-version%2FVERSION&search=%5E(.%2B)&replace=%241&label=Release&color=green>)
[![Build Status](https://circleci.com/gh/lucascbeyeler/zmbackup.svg?style=shield)](https://circleci.com/gh/lucascbeyeler/zmbackup)

## Features

- Online Backup and Restore - no need to stop the server to do;
- Backup routines for one, many, or all mailbox, accounts, alias and distribution lists;
- Restore the routines in your respective places, or inside another account using Restore on Account;
- Multithreading - Execute each rotine quickly as possible;
- Have some insights about eacho backup routine;
- Receive alert everytime a backup session begins;
- Better internal garbage manager;
- Filter the accounts that should not be execute with blocked lists;
- Log management compatible with rsyslog;
- Sessions stored in a SQLite3 database, with automatic one-time migration from the legacy TXT format;

## Backup & Restore Scope

The table below documents what zmbackup covers and what falls outside its scope. Items marked **No** are not touched by zmbackup at all — you will need separate tooling (e.g. etckeeper, manual cert exports) to protect them.

| Object                     | Scope                             | Backup | Restore | Command                                                                                                |
| -------------------------- | --------------------------------- | ------ | ------- | ------------------------------------------------------------------------------------------------------- |
| Mailbox                    | Per user                          | Yes    | Yes     | `zmbackup backup mailbox --account user@domain` / `zmbackup restore mailbox --session <id> --account user@domain` |
| Mailbox                    | All accounts                      | Yes    | Yes     | `zmbackup backup mailbox` / `zmbackup restore mailbox --session <id>`                                    |
| LDAP account entry         | Per user                          | Yes    | Yes     | `zmbackup backup ldap --account user@domain` / `zmbackup restore ldap --session <id> --account user@domain` |
| LDAP account entry         | All accounts                      | Yes    | Yes     | `zmbackup backup ldap` / `zmbackup restore ldap --session <id>`                                          |
| Alias                      | Per alias                         | Yes    | Yes     | `zmbackup backup alias --account alias@domain` / `zmbackup restore ldap --session <id> --account alias@domain` |
| Distribution list          | Per list                          | Yes    | Yes     | `zmbackup backup distlist --account list@domain` / `zmbackup restore ldap --session <id> --account list@domain` |
| Signature                  | Per user                          | Yes    | Yes     | `zmbackup backup signature --account user@domain` / `zmbackup restore ldap --session <id> --account user@domain` |
| Zimbra domain LDAP config  | Per domain                        | Yes    | Yes     | `zmbackup backup domain --domain domain.com` / `zmbackup restore domain --session <id>`                  |
| Zimbra component passwords | Internal services                 | No     | No      | —                                                                                                         |
| SSL/TLS certificates       | Services                          | No     | No      | —                                                                                                         |
| Java Keystores (JKS)       | Services                          | No     | No      | —                                                                                                         |
| Zimbra server config       | `/opt/zimbra/conf`, `/etc/zimbra` | No     | No      | —                                                                                                         |

**Notes:**

- A full backup (`zmbackup backup full`) includes both the mailbox and the LDAP entry for each account by default.
- An incremental backup (`zmbackup backup incremental`) also covers the mailbox, but only captures mail received since the last backup session.
- `--into <account>` restores a mailbox into a different destination account (restore-on-account).
- Use `zmbackup list` to list available session IDs before running a restore.
- **LDAP restores include password hashes.** The LDAP backup dumps the full LDAP entry as the LDAP admin, which includes the `userPassword` attribute (the hashed password). Restoring an LDAP entry will therefore overwrite the account's current password with whatever hash was stored at backup time. Be aware of this before running a restore in production.
- Server-level configuration, certificates, and Zimbra component passwords are **never read or written** by zmbackup. Back these up independently (e.g. etckeeper for `/etc` directories).

## Requirements

- **Java 21 (JDK)** - required to build and run zmbackup; the Gradle toolchain will download it automatically if it isn't already installed.

## Installation

Download the latest package with the BETA tag in "Release" section, or git clone the development branch:

```
git clone -b master https://github.com/lucascbeyeler/zmbackup.git
```

Inside the project folder, execute the script **install-java.sh** and follow all the instructions
to install the project. It checks for (and, if missing, installs) a Java 21 JDK, builds
`zmbackup.jar` with the bundled Gradle wrapper, then installs the jar, a thin `zmbackup` launcher,
`zmbackup.yaml`, the blocked list and a cron file.

If you're moving from an older 1.2.x (bash) install and its `WORKDIR` still has a `sessions.txt`,
`install-java.sh` migrates it into the SQLite metadata store automatically at the end of the
install (or upgrade), via the `zmbackup migrate` command. That command is also safe to run by
hand at any time - `$ zmbackup migrate` - and is a no-op once there's nothing left to import (it
renames `sessions.txt` to `sessions.txt.migrated` after a successful import, so re-running it,
e.g. after `install-java.sh --force-upgrade`, doesn't import the same sessions twice).

`zmbackup truncate` permanently empties `sessions.sqlite3` (every session and account record) and
refuses to do anything unless run as `zmbackup truncate --force-clean`. **This is for
test/development installs only - never run it against production**, since the deleted
session/account history cannot be recovered afterward. `install-java.sh --remove` offers to run
it for you (with the same warning) before it removes the rest of the install.

```
# cd zmbackup
# ./install-java.sh
# su - zimbra
$ zmbackup --version
  zmbackup version: 1.2.9
```

Building the jar needs internet access on first run (to download Gradle and the project's Maven
dependencies). Run `./install-java.sh --remove` to uninstall, or `./install-java.sh --force-upgrade`
to rebuild and redeploy the jar without touching your existing configuration. See
`./install-java.sh --help` for details.

## Usage

Run **zmbackup -h** or **zmbackup --help** for the full, current option list; a summary of the
top-level commands:

```
$ zmbackup --help
Usage: zmbackup [-hv] [--config=<configFile>]
                 [COMMAND]
Commands:
  backup     Back up accounts, aliases, distribution lists, LDAP entries, or domains.
  restore    Restore a backup session (LDAP + mailbox).
  list       List stored backup sessions.
  delete     Delete a stored backup session.
  housekeep  Prune old and empty backup sessions.
  accounts   List Zimbra accounts from LDAP (diagnostic; not a backup operation).
  migrate    Import a bash-tool sessions.txt into the SQLite metadata store.
  truncate   Empty the backup metadata database. TEST/DEV USE ONLY.
```

`backup` has one subcommand per object type - `full`, `incremental`, `mailbox`, `ldap`, `alias`,
`distlist`, `signature`, `domain` - each taking a repeatable `--account` (or, for `domain`,
`--domain`) to restrict which objects are backed up, and (except `domain`) a `--domain` to
restrict discovery to one Zimbra domain. With no `--account`/`--domain`, every discovered object
is backed up.

```
$ zmbackup backup full
$ zmbackup backup full --account user@domain.com
$ zmbackup backup mailbox --domain domain.com
$ zmbackup backup ldap --account user@domain.com
$ zmbackup backup incremental
```

`restore` takes `--session <sessionId>` (check available IDs with `zmbackup list` first) and,
optionally, a repeatable `--account`/`--domain` to restrict which objects are restored; with no
subcommand it restores both LDAP and mailbox content, or use the `ldap`, `domain`, or `mailbox`
subcommands to restore one kind of content on its own. `--into <account>` restores a mailbox into
a different destination account (requires exactly one `--account`).

```
$ zmbackup list
$ zmbackup restore --session full-20170621201603
$ zmbackup restore mailbox --session full-20170621201603 --account user@domain.com
$ zmbackup restore mailbox --session full-20170621201603 --account origin@domain.com --into dest@domain.com
```

`delete` removes a stored session; `housekeep` prunes old and empty sessions:

```
$ zmbackup delete --session full-20170621201603
$ zmbackup housekeep
```

See [Installation](#installation) above for `migrate` and `truncate`.

## Scheduling backups

The installer script automatically creates a cron config file in `/etc/cron.d/zmbackup`. You can customize backup routines editing that file.

## Want to contribute to the project?

Contributions are welcome - please open an issue or pull request.

## License

[![GNU GPL v3.0](http://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl.html)

View official GNU site <http://www.gnu.org/licenses/gpl.html>.

## Author Information

- [Lucas Costa Beyeler](https://github.com/lucascbeyeler)
