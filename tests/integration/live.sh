#!/usr/bin/env bash
set -euo pipefail

: "${ZMBACKUP_LIVE_HOST:?Set ZMBACKUP_LIVE_HOST to the IP address of a disposable Zimbra dev server}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAMP="$(date +%s)"
SRC_DIR="zmbackup-src-${STAMP}"
EML_PREFIX="/tmp/zmbackup-it-${STAMP}"
TEMP_ADMIN=""
CRON_UNIT=""
CLEANED="no"
ZMBACKUP_LIVE_CLEANUP="${ZMBACKUP_LIVE_CLEANUP:-no}"

if [[ ! "$ZMBACKUP_LIVE_HOST" =~ ^[0-9.]+$ ]]; then
  echo "ZMBACKUP_LIVE_HOST must be an IP address, so a stale DNS name can never receive credentials." >&2
  exit 2
fi

remote() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$ZMBACKUP_LIVE_HOST" "$@"
}

as_zimbra() {
  remote "sudo -n -H -u zimbra bash -lc '$1'"
}

as_cron() {
  remote "sudo -n -H -u zimbra env -i SHELL=/bin/bash HOME=/opt/zimbra PATH=$CRON_PATH bash -c '$1'"
}

cron_available() {
  [[ -n "$CRON_UNIT" ]] && return 0
  local unit
  for unit in cron crond; do
    if remote "systemctl is-active --quiet $unit"; then
      CRON_UNIT="$unit"
      return 0
    fi
  done
  return 1
}

cron_fire() {
  local out="$EML_PREFIX-cron-$1.log"
  remote "echo '* * * * * zimbra [ -e $out ] || { $2 > $out 2>&1; echo exit=\$? >> $out; } # zmbackup-it-cron' | sudo -n tee -a /etc/cron.d/zmbackup > /dev/null"
  if ! remote "for i in \$(seq 18); do sudo -n grep -q '^exit=' $out 2> /dev/null && exit 0; sleep 10; done; exit 1"; then
    echo "FAIL: the cron daemon never ran the $1 job" >&2
    exit 1
  fi
  remote "sudo -n sed -i '/zmbackup-it-cron/d' /etc/cron.d/zmbackup"
  CRON_OUTPUT="$(remote "sudo -n cat $out")"
  echo "$CRON_OUTPUT"
}

zmbin() {
  as_zimbra "/opt/zimbra/bin/$1"
}

random_password() {
  printf 'It%s' "$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
}

create_account() {
  printf 'ca %s %s %s\n' "$1" "$2" "${3:-}" | remote "sudo -n -H -u zimbra bash -lc /opt/zimbra/bin/zmprov" > /dev/null
  zmbin "zmprov ga $1 zimbraId" > /dev/null
}

inbox_count() {
  zmbin "zmmailbox -z -m $1 getFolder /Inbox" | grep -oE 'itemCount[^0-9]*[0-9]+' | grep -oE '[0-9]+$'
}

add_message() {
  remote "printf 'Subject: zmbackup integration %s\nFrom: admin@$DOMAIN\nTo: $ACCOUNT\n\nBody.\n' '$1' | sudo -n tee $EML_PREFIX-$1.eml > /dev/null && sudo -n chown zimbra:zimbra $EML_PREFIX-$1.eml"
  zmbin "zmmailbox -z -m $ACCOUNT addMessage /Inbox $EML_PREFIX-$1.eml" > /dev/null
}

latest_session() {
  as_zimbra "zmbackup list" | grep -oE "$1-[0-9]+" | sort | tail -1 || true
}

assert_equal() {
  if [[ "$2" != "$3" ]]; then
    echo "FAIL: $1: expected [$2], got [$3]" >&2
    exit 1
  fi
  echo "ok - $1"
}

finish() {
  local code=$?
  if [[ -n "$TEMP_ADMIN" ]]; then
    zmbin "zmprov ma $TEMP_ADMIN zimbraIsAdminAccount FALSE zimbraAccountStatus closed" > /dev/null 2>&1 || true
  fi
  if [[ "$CLEANED" == "yes" ]]; then
    echo "Left on $ZMBACKUP_LIVE_HOST: ~/$SRC_DIR, $EML_PREFIX-*.eml and /opt/zimbra/backup/sessions.sqlite3."
    exit "$code"
  fi
  remote "sudo -n test -f /etc/cron.d/zmbackup && sudo -n mv /etc/cron.d/zmbackup /etc/cron.d/zmbackup.disabled" > /dev/null 2>&1 || true
  echo "Left on $ZMBACKUP_LIVE_HOST: accounts $ACCOUNT $RESTORED ${TEMP_ADMIN:-}, ~/$SRC_DIR, $EML_PREFIX-*.eml, /etc/zmbackup, the zmbackup install (cron disabled) and its sessions."
  exit "$code"
}

remote true
DOMAIN="${ZMBACKUP_LIVE_DOMAIN:-$(zmbin "zmprov gad" | head -1)}"
ACCOUNT="zmbackup-it-${STAMP}@${DOMAIN}"
RESTORED="zmbackup-it-restored-${STAMP}@${DOMAIN}"
trap finish EXIT

if [[ -z "${ZMBKP_REST_ADMIN_PASS:-}" ]]; then
  TEMP_ADMIN="zmbackup-it-admin-${STAMP}@${DOMAIN}"
  ZMBKP_REST_ADMIN="$TEMP_ADMIN"
  ZMBKP_REST_ADMIN_PASS="$(random_password)"
  create_account "$TEMP_ADMIN" "$ZMBKP_REST_ADMIN_PASS" "zimbraIsAdminAccount TRUE"
fi
ZMBKP_REST_ADMIN="${ZMBKP_REST_ADMIN:-admin@$DOMAIN}"

remote "mkdir -p ~/$SRC_DIR"
tar -C "$REPO_ROOT" --exclude='./.git' --exclude='.gradle' --exclude='build' --exclude='./node_modules' -czf - . |
  remote "tar -xzf - -C ~/$SRC_DIR"
INSTALL_STARTED="$(remote "date '+%Y-%m-%d %H:%M:%S'")"
printf '%s' "$ZMBKP_REST_ADMIN_PASS" |
  remote "cd ~/$SRC_DIR && sudo -n bash -c 'IFS= read -r -d \"\" ZMBKP_REST_ADMIN_PASS || true; export ZMBKP_REST_ADMIN=$ZMBKP_REST_ADMIN ZMBKP_REST_ADMIN_PASS; exec ./install-java.sh --unattended'"
assert_equal "the REST password is not in the system journal" "0" \
  "$(printf '%s' "$ZMBKP_REST_ADMIN_PASS" |
    remote "sudo -n bash -c 'IFS= read -r -d \"\" PASS || true; journalctl --no-pager --since \"$INSTALL_STARTED\" | grep -cF -f <(printf \"%s\\n\" \"\$PASS\")'" || true)"
CRON_PATH="$(remote "sudo -n sed -n 's/^PATH=//p' /etc/cron.d/zmbackup")"
assert_equal "the installer set the Zimbra CA in both config sections" "2" \
  "$(remote "sudo -n grep -cxF \"  caCertificatePath: '/opt/zimbra/ssl/zimbra/ca/ca.pem'\" /etc/zmbackup/zmbackup.yaml")"
as_cron "zmbackup accounts list" > /dev/null
as_zimbra "zmbackup accounts list" > /dev/null
echo "ok - a default install connects under both the cron and login-shell JVMs"
remote "sudo -n sed -i 's|^  caCertificatePath: .*|  # caCertificatePath: /opt/zimbra/ssl/zimbra/ca/ca.pem|' /etc/zmbackup/zmbackup.yaml"
if as_cron "zmbackup accounts list" > /dev/null 2>&1; then
  echo "FAIL: the system JVM accepted a self-signed certificate with no CA configured" >&2
  exit 1
fi
echo "ok - the system JVM refuses the self-signed certificate when no CA is configured"
if as_zimbra "zmbackup accounts list" > /dev/null 2>&1; then
  echo "FAIL: the login shell accepted the certificate with no CA configured, so it does not run the JVM cron runs" >&2
  exit 1
fi
echo "ok - the login shell runs the same JVM as cron, so it refuses the certificate too"
if cron_available; then
  cron_fire no-ca "zmbackup backup full"
  if [[ "$CRON_OUTPUT" != *"TLS certificate validation failed"* || "$CRON_OUTPUT" == *"exit=0"* ]]; then
    echo "FAIL: expected the cron job to fail certificate validation with no CA configured" >&2
    exit 1
  fi
  echo "ok - the cron daemon loaded the installed file and its job refused the certificate with no CA"
else
  echo "skip - no active cron daemon on this server"
fi
remote "sudo -n sed -i \"s|^  # caCertificatePath: .*|  caCertificatePath: '/opt/zimbra/ssl/zimbra/ca/ca.pem'|\" /etc/zmbackup/zmbackup.yaml"
as_cron "zmbackup accounts list" > /dev/null
as_zimbra "zmbackup accounts list" > /dev/null
echo "ok - both JVMs connect with full certificate validation against the Zimbra CA"
assert_equal "cron file is root-owned 0644" "root 644" "$(remote 'sudo -n stat -c "%U %a" /etc/cron.d/zmbackup')"
assert_equal "config is zimbra-owned 0600" "zimbra 600" "$(remote 'sudo -n stat -c "%U %a" /etc/zmbackup/zmbackup.yaml')"
echo "ok - unattended install"

create_account "$ACCOUNT" "$(random_password)"
add_message first
add_message second
assert_equal "seeded inbox" 2 "$(inbox_count "$ACCOUNT")"

if cron_available; then
  cron_fire with-ca "zmbackup backup full"
  assert_equal "the cron job exited cleanly" "exit=0" "$(grep '^exit=' <<< "$CRON_OUTPUT")"
  assert_equal "the cron daemon did not reject the file" "0" \
    "$(remote "sudo -n journalctl -u $CRON_UNIT --no-pager --since '-20 min' | grep -cE 'WRONG FILE OWNER|BAD FILE MODE|INSECURE MODE'" || true)"
  remote "sudo -n journalctl -u $CRON_UNIT --no-pager --since '-20 min' | grep -E '\(zimbra\) CMD' | tail -2 | cut -c1-160"
else
  as_zimbra "zmbackup backup full"
fi
FULL="$(latest_session full)"
[[ -n "$FULL" ]] || { echo "FAIL: no full session recorded" >&2; exit 1; }
remote "sudo -n test -s /opt/zimbra/backup/$FULL/$ACCOUNT.ldiff && sudo -n gzip -t /opt/zimbra/backup/$FULL/$ACCOUNT.tgz"
echo "ok - discovered full backup wrote a non-empty ldiff and a valid tgz ($FULL)"
assert_equal "blocklisted accounts were not backed up" "0" \
  "$(remote "sudo -n ls /opt/zimbra/backup/$FULL" | grep -cE '^(admin|spam|ham|virus|galsync|zmbackup-it-admin)' || true)"

as_zimbra "zmbackup backup full"
assert_equal "same-day discovered full skips the account (lockBackup)" "1" \
  "$(remote "sudo -n find /opt/zimbra/backup -path '*/full-*' -name '$ACCOUNT.tgz'" | wc -l)"

remote "sudo -n -H -u zimbra python3 - $ACCOUNT" <<'REWIND'
import datetime, sqlite3, sys
midnight = datetime.datetime.now().astimezone().replace(hour=0, minute=0, second=0, microsecond=0)
before = (midnight - datetime.timedelta(minutes=1)).astimezone(datetime.timezone.utc)
stamp = before.strftime("%Y-%m-%dT%H:%M:%SZ")
database = sqlite3.connect("/opt/zimbra/backup/sessions.sqlite3")
database.execute("update backup_account set conclusion_date = ? where email = ?", (stamp, sys.argv[1]))
database.commit()
REWIND
as_zimbra "zmbackup backup full"
SECOND_FULL="$(latest_session full)"
assert_equal "a backup from just before local midnight no longer blocks today's full" "2" \
  "$(remote "sudo -n find /opt/zimbra/backup -path '*/full-*' -name '$ACCOUNT.tgz'" | wc -l)"

as_cron "zmbackup backup alias"
echo "ok - a scheduled job's command runs under the environment the cron file defines"

add_message third
as_zimbra "zmbackup backup incremental --account $ACCOUNT"
INCREMENTAL="$(latest_session inc)"
remote "sudo -n gzip -t /opt/zimbra/backup/$INCREMENTAL/$ACCOUNT.tgz"
echo "ok - incremental backup wrote a valid tgz ($INCREMENTAL)"

create_account "$RESTORED" "$(random_password)"
as_zimbra "zmbackup restore --session $FULL --account $ACCOUNT --into $RESTORED --verify"
assert_equal "mailbox restored into a new account matches the full backup" 2 "$(inbox_count "$RESTORED")"

remote "sudo -n sed -i '/^  rotateDays:/d' /etc/zmbackup/zmbackup.yaml"
remote "cd ~/$SRC_DIR && sudo -n ./install-java.sh --unattended --force-upgrade"
as_zimbra "zmbackup list" > /dev/null
echo "ok - unattended force-upgrade keeps a working install"
assert_equal "the unattended upgrade added the missing setting with its default" "  rotateDays: 30" \
  "$(remote "sudo -n grep '^  rotateDays:' /etc/zmbackup/zmbackup.yaml")"

BACKUPS_BEFORE="$(remote "sudo -n sh -c 'ls /etc/zmbackup/zmbackup.yaml.*.bak 2> /dev/null | wc -l'")"
remote "sudo -n sed -i -e 's|^  caCertificatePath: .*|  # caCertificatePath: /opt/zimbra/ssl/zimbra/ca/ca.pem|' -e '/^  rotateDays:/d' /etc/zmbackup/zmbackup.yaml"
UPGRADE_OUTPUT="$(printf '%s\n' y 21 "" n | remote "cd ~/$SRC_DIR && sudo -n ./install-java.sh --force-upgrade" 2>&1)"
for PROMPT in "Inform backup.rotateDays - DEFAULT [30]" "has no CA certificate for zimbraLdap zimbraMailbox" "DEFAULT [/opt/zimbra/ssl/zimbra/ca/ca.pem]"; do
  if [[ "$UPGRADE_OUTPUT" != *"$PROMPT"* ]]; then
    echo "FAIL: the interactive upgrade did not ask: $PROMPT" >&2
    exit 1
  fi
done
assert_equal "the interactive upgrade added the missing setting" "  rotateDays: 21" \
  "$(remote "sudo -n grep '^  rotateDays:' /etc/zmbackup/zmbackup.yaml")"
assert_equal "the interactive upgrade set the Zimbra CA in both config sections" "2" \
  "$(remote "sudo -n grep -cxF \"  caCertificatePath: '/opt/zimbra/ssl/zimbra/ca/ca.pem'\" /etc/zmbackup/zmbackup.yaml")"
assert_equal "the interactive upgrade kept a backup of the previous config" "$((BACKUPS_BEFORE + 1))" \
  "$(remote "sudo -n sh -c 'ls /etc/zmbackup/zmbackup.yaml.*.bak | wc -l'")"
as_cron "zmbackup accounts list" > /dev/null
echo "ok - the completed config connects under the cron environment"

if [[ "$ZMBACKUP_LIVE_CLEANUP" == "yes" ]]; then
  zmbin "zmprov da $ACCOUNT" > /dev/null
  as_zimbra "zmbackup restore --session $FULL --account $ACCOUNT --verify"
  assert_equal "deleted account restored with its mailbox" 2 "$(inbox_count "$ACCOUNT")"
  zmbin "zmprov da $ACCOUNT" > /dev/null
  zmbin "zmprov da $RESTORED" > /dev/null
  as_zimbra "zmbackup delete --session $INCREMENTAL"
  as_zimbra "zmbackup delete --session $SECOND_FULL"
  as_zimbra "zmbackup delete --session $FULL"
  ALIAS_SESSION="$(latest_session alias)"
  if [[ -n "$ALIAS_SESSION" ]]; then
    as_zimbra "zmbackup delete --session $ALIAS_SESSION"
  fi
  remote "cd ~/$SRC_DIR && sudo -n ./install-java.sh --remove --unattended"
  remote "sudo -n test ! -e /etc/zmbackup && sudo -n test ! -e /etc/cron.d/zmbackup && sudo -n test ! -e /usr/local/bin/zmbackup && sudo -n test ! -e /usr/local/lib/zmbackup && sudo -n test -f /opt/zimbra/backup/sessions.sqlite3"
  echo "ok - unattended remove clears the install and keeps the database"
  if [[ -n "$TEMP_ADMIN" ]]; then
    zmbin "zmprov da $TEMP_ADMIN" > /dev/null
    TEMP_ADMIN=""
  fi
  CLEANED="yes"
fi

echo "ALL LIVE CHECKS PASSED"
