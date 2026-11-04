#!/usr/bin/env bats

load 'sandbox'

PREAMBLE='
mkdir -p /opt/zimbra/log
ln -s /fake-zimbra-bin /opt/zimbra/bin
export ZMBKP_REST_ADMIN_PASS=rest-secret-value
export OSE_INSTALL_LDAPPASS=ldap-secret-value
export JAVA_TOOL_OPTIONS=-Duser.name=zimbra
'

setup_file() {
  if ! sandbox_available; then
    skip "bubblewrap with unprivileged user namespaces is required"
  fi
}

setup() {
  sandbox_available || skip "bubblewrap with unprivileged user namespaces is required"
  [[ -f "$REPO_ROOT/app/build/libs/zmbackup.jar" ]] || {
    echo "Build the jar first: ./gradlew :app:shadowJar" >&2
    return 1
  }
  SANDBOX_ROOT="$BATS_TEST_TMPDIR/sandbox"
  sandbox_prepare "$SANDBOX_ROOT"
}

TLS_FIXTURE='
tls_ca() {
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=$1" -keyout "/tmp/$1.key" -out "/tmp/$1.pem" 2> /dev/null
}
tls_server_signed_by() {
  openssl req -newkey rsa:2048 -nodes -subj "/CN=127.0.0.1" -keyout /tmp/server.key -out /tmp/server.csr 2> /dev/null
  openssl x509 -req -days 2 -in /tmp/server.csr -CA "/tmp/$1.pem" -CAkey "/tmp/$1.key" -CAcreateserial -out /tmp/server.crt 2> /dev/null
  openssl s_server -accept 7071 -cert /tmp/server.crt -key /tmp/server.key -quiet > /dev/null 2>&1 &
  for _ in $(seq 50); do
    timeout 1 bash -c "exec 3<>/dev/tcp/127.0.0.1/7071" 2> /dev/null && break
    read -r -t 0.1 < <(tail -f /dev/null) || true
  done
}
install_zimbra_ca() {
  mkdir -p /opt/zimbra/ssl/zimbra/ca
  cp "/tmp/$1.pem" /opt/zimbra/ssl/zimbra/ca/ca.pem
}
export OSE_INSTALL_ADDRESS=127.0.0.1
'

in_sandbox() {
  sandbox_exec "$SANDBOX_ROOT" "$PREAMBLE
$1"
}

@test "unattended install completes with stdin closed and renders every template token" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    grep -nE "\{[A-Z_]+\}" /etc/zmbackup/zmbackup.yaml /etc/cron.d/zmbackup && exit 91
    cat /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"Install completed."* ]]
  [[ "$output" == *"url: ldap://zimbra.it.test:389"* ]]
  [[ "$output" == *"restBaseUrl: https://zimbra.it.test:7071"* ]]
  [[ "$output" == *"bindPassword: 'ldap-secret-value'"* ]]
  [[ "$output" == *"adminPassword: 'rest-secret-value'"* ]]
  [[ "$output" == *"adminUser: admin@it.test"* ]]
  [[ "$output" == *"recipient: admin@it.test"* ]]
  [[ "$output" == *"sender: root@it.test"* ]]
}

@test "unattended install masks both passwords in its own output" {
  run in_sandbox './install-java.sh -u'
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"Zimbra REST Admin Password: ********"* ]]
  [[ "$output" != *"rest-secret-value"* ]]
  [[ "$output" != *"ldap-secret-value"* ]]
}

@test "unattended install honours every environment override" {
  run in_sandbox '
    export OSE_INSTALL_ADDRESS=mail.override.test
    export OSE_DEFAULT_BKP_DIR=/srv/backups
    export ZMBKP_REST_ADMIN=backup-admin@override.test
    export ZMBKP_MAIL_ALERT=alerts@override.test
    export ZMBKP_MAIL_SENDER=zmbackup@override.test
    export MAX_PARALLEL_PROCESS=7
    export ROTATE_TIME=11
    export LOCK_BACKUP=false
    ./install-java.sh --unattended || exit 90
    cat /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"url: ldap://mail.override.test:389"* ]]
  [[ "$output" == *"workDir: /srv/backups"* ]]
  [[ "$output" == *"adminUser: backup-admin@override.test"* ]]
  [[ "$output" == *"recipient: alerts@override.test"* ]]
  [[ "$output" == *"sender: zmbackup@override.test"* ]]
  [[ "$output" == *"maxParallelProcesses: 7"* ]]
  [[ "$output" == *"rotateDays: 11"* ]]
  [[ "$output" == *"lockBackup: false"* ]]
}

@test "unattended install stops with exit 5 and deploys nothing when the REST password is missing" {
  run in_sandbox '
    unset ZMBKP_REST_ADMIN_PASS
    ./install-java.sh --unattended
    CODE=$?
    test ! -e /etc/zmbackup || echo LEFT_CONFIG
    test ! -e /usr/local/bin/zmbackup || echo LEFT_LAUNCHER
    test ! -e /etc/cron.d/zmbackup || echo LEFT_CRON
    exit $CODE
  '
  echo "$output"
  [ "$status" -eq 5 ]
  [[ "$output" == *"missing required values: ZMBKP_REST_ADMIN_PASS"* ]]
  [[ "$output" != *"LEFT_"* ]]
}

@test "unattended install reads the LDAP password from zmlocalconfig when it is not preset" {
  run in_sandbox '
    unset OSE_INSTALL_LDAPPASS
    ./install-java.sh --unattended || exit 90
    grep bindPassword /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"bindPassword: 'ldap-from-localconfig'"* ]]
}

@test "cron file is root-owned and 0644 while the config stays owned by the service user and 0600" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    stat -c "MODE %a %n" /etc/cron.d/zmbackup /etc/zmbackup/zmbackup.yaml
    cat /tmp/ownership.log
    cat /etc/cron.d/zmbackup
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"MODE 644 /etc/cron.d/zmbackup"* ]]
  [[ "$output" == *"MODE 600 /etc/zmbackup/zmbackup.yaml"* ]]
  [[ "$output" == *"root /etc/cron.d/zmbackup"* ]]
  [[ "$output" == *"zimbra /etc/zmbackup/zmbackup.yaml"* ]]
  [[ "$output" == *"zimbra    zmbackup backup full"* ]]
}

@test "unattended install stops with exit 3 and deploys nothing when no cron daemon is running" {
  run in_sandbox '
    touch /tmp/no-cron
    ./install-java.sh --unattended
    CODE=$?
    test ! -e /etc/zmbackup || echo LEFT_CONFIG
    test ! -e /usr/local/bin/zmbackup || echo LEFT_LAUNCHER
    test ! -e /etc/cron.d/zmbackup || echo LEFT_CRON
    exit $CODE
  '
  echo "$output"
  [ "$status" -eq 3 ]
  [[ "$output" == *"would never run"* ]]
  [[ "$output" != *"LEFT_"* ]]
}

@test "ZMBKP_SKIP_CRON_CHECK lets an unattended install proceed without a cron daemon" {
  run in_sandbox '
    touch /tmp/no-cron
    ZMBKP_SKIP_CRON_CHECK=Y ./install-java.sh --unattended || exit 90
    test -f /etc/cron.d/zmbackup || exit 91
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"[SKIPPED]"* ]]
}

@test "unattended remove works without a cron daemon" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    touch /tmp/no-cron
    ./install-java.sh --remove --unattended || exit 91
    test ! -e /etc/zmbackup || exit 92
  '
  echo "$output"
  [ "$status" -eq 0 ]
}

@test "interactive install stops at the cron warning unless the operator continues" {
  run in_sandbox '
    touch /tmp/no-cron
    printf "%s\n" "" > /tmp/answers
    bash -c "./install-java.sh < /tmp/answers"
    echo "declined=$?"
    test ! -e /etc/zmbackup || echo LEFT_CONFIG
    printf "%s\n" y y "" "" "" "" "" "" typed-pass "" "" "" "" "" "" "" n > /tmp/answers
    bash -c "./install-java.sh < /tmp/answers" || exit 90
    test -f /etc/zmbackup/zmbackup.yaml || exit 91
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"declined=3"* ]]
  [[ "$output" != *"LEFT_"* ]]
}

@test "the blocklist is generated from the live account list" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    grep -c "^admin@it.test$" /etc/zmbackup/blockedlist.conf || exit 91
    grep -c "^galsync.abc@it.test$" /etc/zmbackup/blockedlist.conf || exit 92
    grep "^alice@it.test$" /etc/zmbackup/blockedlist.conf && exit 93
    exit 0
  '
  echo "$output"
  [ "$status" -eq 0 ]
}

@test "the blocklist still works when OSE_INSTALL_DIR is overridden" {
  run in_sandbox '
    rm /opt/zimbra/bin
    rm -rf /opt/zimbra
    mkdir -p /opt/zcs/log
    ln -s /fake-zimbra-bin /opt/zcs/bin
    export OSE_INSTALL_DIR=/opt/zcs
    ./install-java.sh --unattended || exit 90
    grep -c "^admin@it.test$" /etc/zmbackup/blockedlist.conf || exit 91
  '
  echo "$output"
  [ "$status" -eq 0 ]
}

@test "the installed launcher runs the packaged jar and loads the rendered config" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    zmbackup --version || exit 91
    zmbackup list || exit 92
    test -f /opt/zimbra/backup/sessions.sqlite3 || exit 93
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"zmbackup version: $(cat "$REPO_ROOT/VERSION")"* ]]
  [[ "$output" == *"Session ID"* ]]
}

@test "ZMBKP_METADATA_DIR is created and the installed jar keeps its database there" {
  run in_sandbox '
    export ZMBKP_METADATA_DIR=/var/zmbackup-meta
    ./install-java.sh --unattended || exit 90
    grep -n "^  metadataDir: /var/zmbackup-meta$" /etc/zmbackup/zmbackup.yaml || exit 91
    zmbackup list || exit 92
    test -f /var/zmbackup-meta/sessions.sqlite3 || exit 93
    test ! -f /opt/zimbra/backup/sessions.sqlite3 || exit 94
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"SQLite3 (/var/zmbackup-meta/sessions.sqlite3)"* ]]
}

@test "passwords containing sed and YAML metacharacters reach the config intact" {
  run in_sandbox '
    export ZMBKP_REST_ADMIN_PASS="a&b|c#d: e\\f"
    export OSE_INSTALL_LDAPPASS="!x&y|z'"'"'q"
    ./install-java.sh --unattended || exit 90
    grep -E "adminPassword|bindPassword" /etc/zmbackup/zmbackup.yaml
    zmbackup list || exit 92
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"Session ID"* ]]
  [[ "$output" == *"adminPassword: 'a&b|c#d: e\\f'"* ]]
  [[ "$output" == *"bindPassword: '!x&y|z''q'"* ]]
}

@test "an empty interactive REST password still fails fast when zmbackup starts" {
  run in_sandbox '
    unset ZMBKP_REST_ADMIN_PASS
    printf "%s\n" y "" "" "" "" "" "" "" "" "" "" "" "" "" "" n > /tmp/answers
    bash -c "./install-java.sh < /tmp/answers" || exit 90
    grep -n "^  adminPassword: $" /etc/zmbackup/zmbackup.yaml || exit 91
    zmbackup list
  '
  echo "$output"
  [ "$status" -ne 0 ]
  [ "$status" -ne 90 ]
  [ "$status" -ne 91 ]
  [[ "$output" == *"Missing required config value: zimbraMailbox.adminPassword"* ]]
}

@test "unattended force-upgrade needs no credentials, keeps the config, and repairs the cron file" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    echo "# operator edit" >> /etc/zmbackup/zmbackup.yaml
    chmod 600 /etc/cron.d/zmbackup
    : > /tmp/ownership.log
    unset ZMBKP_REST_ADMIN_PASS OSE_INSTALL_LDAPPASS
    ./install-java.sh --unattended --force-upgrade || exit 91
    grep -c "operator edit" /etc/zmbackup/zmbackup.yaml
    stat -c "MODE %a" /etc/cron.d/zmbackup
    cat /tmp/ownership.log
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"Upgrade completed."* ]]
  [[ "$output" == *"MODE 644"* ]]
  [[ "$output" == *"root /etc/cron.d/zmbackup"* ]]
}

@test "unattended remove keeps the database and the backup storage" {
  run in_sandbox '
    ./install-java.sh --unattended || exit 90
    zmbackup list > /dev/null || exit 91
    mkdir -p /opt/zimbra/backup/full-sentinel
    ./install-java.sh --remove --unattended || exit 92
    test ! -e /etc/zmbackup || exit 93
    test ! -e /usr/local/bin/zmbackup || exit 94
    test ! -e /usr/local/lib/zmbackup || exit 95
    test ! -e /etc/cron.d/zmbackup || exit 96
    test -f /opt/zimbra/backup/sessions.sqlite3 || exit 97
    test -d /opt/zimbra/backup/full-sentinel || exit 98
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"skipping database truncation"* ]]
  [[ "$output" == *"preserving backup storage"* ]]
}

@test "interactive install still asks for the server hostname and metadata directory" {
  run in_sandbox '
    printf "%s\n" y "" "" prompted.host.test "" /var/prompted-meta "" typed-pass "" "" "" "" "" "" "" n > /tmp/answers
    bash -c "./install-java.sh < /tmp/answers" || exit 90
    grep -E "url:|metadataDir:|adminPassword:" /etc/zmbackup/zmbackup.yaml
    test -d /var/prompted-meta || exit 91
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"url: ldap://prompted.host.test:389"* ]]
  [[ "$output" == *"metadataDir: /var/prompted-meta"* ]]
  [[ "$output" == *"adminPassword: 'typed-pass'"* ]]
}

@test "ca: a Zimbra CA that validates the server is written to both sections" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca zimbra-ca
    install_zimbra_ca zimbra-ca
    tls_server_signed_by zimbra-ca
    ./install-java.sh --unattended || exit 90
    grep -c "^  caCertificatePath: /opt/zimbra/ssl/zimbra/ca/ca.pem$" /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [ "${lines[-1]}" = "2" ]
  [[ "$output" == *"validates the certificate of 127.0.0.1"* ]]
}

@test "ca: a server certificate from another CA leaves the setting unset and warns" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca zimbra-ca
    tls_ca public-ca
    install_zimbra_ca zimbra-ca
    tls_server_signed_by public-ca
    ./install-java.sh --unattended || exit 90
    grep -c "^  # caCertificatePath:" /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [ "${lines[-1]}" = "2" ]
  [[ "$output" == *"[WARN] - /opt/zimbra/ssl/zimbra/ca/ca.pem does not validate"* ]]
  [[ "$output" == *"Zimbra CA Certificate: none"* ]]
}

@test "ca: an unreachable server keeps the Zimbra CA and warns that it could not check" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca zimbra-ca
    install_zimbra_ca zimbra-ca
    ./install-java.sh --unattended || exit 90
    grep -c "^  caCertificatePath: /opt/zimbra/ssl/zimbra/ca/ca.pem$" /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [ "${lines[-1]}" = "2" ]
  [[ "$output" == *"[WARN] - Could not check the certificate"* ]]
}

@test "ca: no Zimbra CA file leaves the setting unset without a warning" {
  run in_sandbox './install-java.sh --unattended || exit 90
    grep -c "^  # caCertificatePath:" /etc/zmbackup/zmbackup.yaml'
  echo "$output"
  [ "$status" -eq 0 ]
  [ "${lines[-1]}" = "2" ]
  [[ "$output" == *"Zimbra CA Certificate: none"* ]]
  [[ "$output" != *"[WARN]"* ]]
}

@test "ca: ZMBKP_CA_CERT=none overrides a CA that would validate" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca zimbra-ca
    install_zimbra_ca zimbra-ca
    tls_server_signed_by zimbra-ca
    ZMBKP_CA_CERT=none ./install-java.sh --unattended || exit 90
    grep -c "^  # caCertificatePath:" /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [ "${lines[-1]}" = "2" ]
}

@test "ca: ZMBKP_CA_CERT with a custom file is written, and a wrong one warns" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca custom-ca
    tls_ca public-ca
    tls_server_signed_by public-ca
    ZMBKP_CA_CERT=/tmp/custom-ca.pem ./install-java.sh --unattended || exit 90
    grep -c "^  caCertificatePath: /tmp/custom-ca.pem$" /etc/zmbackup/zmbackup.yaml
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [ "${lines[-1]}" = "2" ]
  [[ "$output" == *"Zmbackup will refuse to connect"* ]]
}

@test "ca: a ZMBKP_CA_CERT file that does not exist stops with exit 5 and deploys nothing" {
  run in_sandbox '
    ZMBKP_CA_CERT=/tmp/missing.pem ./install-java.sh --unattended
    CODE=$?
    test ! -e /etc/zmbackup || echo LEFT_CONFIG
    exit $CODE
  '
  echo "$output"
  [ "$status" -eq 5 ]
  [[ "$output" != *"LEFT_"* ]]
}

@test "ca: the interactive prompt offers the validated CA and accepts none" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca zimbra-ca
    install_zimbra_ca zimbra-ca
    tls_server_signed_by zimbra-ca
    printf "%s\n" y "" "" "" "" "" "" typed-pass "" "" "" "" "" "" "" n > /tmp/answers
    bash -c "./install-java.sh < /tmp/answers" || exit 90
    echo "enter=$(grep -c "^  caCertificatePath: /opt/zimbra/ssl/zimbra/ca/ca.pem$" /etc/zmbackup/zmbackup.yaml)"
    printf "%s\n" y "" "" "" "" "" "" typed-pass "" "" "" "" "" none "" n > /tmp/answers
    bash -c "./install-java.sh < /tmp/answers" || exit 91
    echo "none=$(grep -c "^  # caCertificatePath:" /etc/zmbackup/zmbackup.yaml)"
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"DEFAULT [/opt/zimbra/ssl/zimbra/ca/ca.pem]"* ]]
  [[ "$output" == *"enter=2"* ]]
  [[ "$output" == *"none=2"* ]]
}

@test "ca: an upgrade warns about a missing CA and leaves the config untouched" {
  run in_sandbox "$TLS_FIXTURE"'
    ./install-java.sh --unattended || exit 90
    tls_ca zimbra-ca
    install_zimbra_ca zimbra-ca
    tls_server_signed_by zimbra-ca
    BEFORE=$(sha256sum /etc/zmbackup/zmbackup.yaml)
    ./install-java.sh --unattended --force-upgrade || exit 91
    test "$BEFORE" = "$(sha256sum /etc/zmbackup/zmbackup.yaml)" && echo CONFIG_UNCHANGED
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"has no caCertificatePath"* ]]
  [[ "$output" == *"CONFIG_UNCHANGED"* ]]
}

@test "ca: an upgrade stays quiet when the config already sets a CA" {
  run in_sandbox "$TLS_FIXTURE"'
    tls_ca zimbra-ca
    install_zimbra_ca zimbra-ca
    tls_server_signed_by zimbra-ca
    ./install-java.sh --unattended || exit 90
    ./install-java.sh --unattended --force-upgrade || exit 91
  '
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" != *"has no caCertificatePath"* ]]
}
