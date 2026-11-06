#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${INSTALLER_JAVA_DIR}/menu.sh"
}

@test "set_values_java: uses defaults when all inputs are empty" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n\n\n\n')
    echo \"OSE_USER=\$OSE_USER\"
    echo \"MAX_PARALLEL_PROCESS=\$MAX_PARALLEL_PROCESS\"
  "
  [[ "$output" == *"OSE_USER=zimbra"* ]]
  [[ "$output" == *"MAX_PARALLEL_PROCESS=3"* ]]
}

@test "set_values_java: overrides OSE_USER when provided" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf 'myuser\n\n\n\n\n\n\n\n\n\n\n\n')
    echo \"OSE_USER=\$OSE_USER\"
  "
  [[ "$output" == *"OSE_USER=myuser"* ]]
}

@test "set_values_java: overrides MAX_PARALLEL_PROCESS when provided" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n8\n\n\n')
    echo \"MAX_PARALLEL_PROCESS=\$MAX_PARALLEL_PROCESS\"
  "
  [[ "$output" == *"MAX_PARALLEL_PROCESS=8"* ]]
}

@test "set_values_java: defaults the server address to the FQDN" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n\n\n\n')
    echo \"OSE_INSTALL_ADDRESS=\$OSE_INSTALL_ADDRESS\"
  "
  [[ "$output" == *"OSE_INSTALL_ADDRESS=mail.example.com"* ]]
}

@test "set_values_java: overrides the server address when provided" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\nzimbra.example.org\n\n\n\n\n\n\n\n\n\n')
    echo \"OSE_INSTALL_ADDRESS=\$OSE_INSTALL_ADDRESS\"
  "
  [[ "$output" == *"OSE_INSTALL_ADDRESS=zimbra.example.org"* ]]
}

@test "set_values_java: leaves the metadata directory unset by default" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n\n\n\n')
    echo \"ZMBKP_METADATA_DIR=[\$ZMBKP_METADATA_DIR]\"
  "
  [[ "$output" == *"ZMBKP_METADATA_DIR=[]"* ]]
}

@test "set_values_java: sets the metadata directory when provided" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n/var/zmbackup\n\n\n\n\n\n\n\n')
    echo \"ZMBKP_METADATA_DIR=\$ZMBKP_METADATA_DIR\"
  "
  [[ "$output" == *"ZMBKP_METADATA_DIR=/var/zmbackup"* ]]
}

@test "set_values_java: displays CONFIGURATION COMPLETED message" {
  run bash -c "
    PATH='${MOCKS_DIR}:${PATH}'
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null || true
    source '${INSTALLER_JAVA_DIR}/menu.sh'
    set_values_java < <(printf '\n\n\n\n\n\n\n\n\n\n\n\n')
  "
  [[ "$output" == *"CONFIGURATION COMPLETED"* ]]
}

@test "check_config_java: displays configuration summary" {
  OSE_USER="zimbra"
  OSE_INSTALL_ADDRESS="192.168.1.1"
  OSE_INSTALL_LDAPPASS="secret"
  ZMBKP_REST_ADMIN="admin@example.com"
  ZMBKP_REST_ADMIN_PASS="secret2"
  OSE_INSTALL_DIR="/opt/zimbra"
  OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"
  ZMBKP_SRC="/usr/local/bin"
  ZMBKP_LIB="/usr/local/lib/zmbackup"
  ZMBKP_JAR_NAME="zmbackup.jar"
  ZMBKP_CONF="/etc/zmbackup"
  ROTATE_TIME="30"
  MAX_PARALLEL_PROCESS="3"
  LOCK_BACKUP="true"
  output=$(echo "" | check_config_java)
  [[ "$output" == *"Summary"* ]]
  [[ "$output" == *"zimbra"* ]]
  [[ "$output" == *"SQLite3"* ]]
}

@test "check_config_java: reports the metadata directory as the session storage location when set" {
  OSE_DEFAULT_BKP_DIR="/mnt/nfs/backup"
  ZMBKP_METADATA_DIR="/var/zmbackup"
  output=$(echo "" | check_config_java)
  [[ "$output" == *"SQLite3 (/var/zmbackup/sessions.sqlite3)"* ]]
}

@test "check_config_java: does not wait for ENTER when unattended" {
  UNATTENDED="Y"
  run check_config_java < /dev/null
  [ "$status" -eq 0 ]
  [[ "$output" != *"Press ENTER"* ]]
}

@test "check_config_java: masks both passwords when unattended" {
  UNATTENDED="Y"
  OSE_INSTALL_LDAPPASS="ldapsecret"
  ZMBKP_REST_ADMIN_PASS="restsecret"
  run check_config_java < /dev/null
  [[ "$output" != *"ldapsecret"* ]]
  [[ "$output" != *"restsecret"* ]]
  [[ "$output" == *"Zimbra REST Admin Password: ********"* ]]
}

@test "check_config_java: masks both passwords when interactive" {
  UNATTENDED="N"
  OSE_INSTALL_LDAPPASS="ldapsecret"
  ZMBKP_REST_ADMIN_PASS="restsecret"
  run check_config_java <<< ""
  [[ "$output" != *"ldapsecret"* ]]
  [[ "$output" != *"restsecret"* ]]
  [[ "$output" == *"Zimbra LDAP/Admin Password: ********"* ]]
  [[ "$output" == *"Zimbra REST Admin Password: ********"* ]]
}

@test "check_config_java: shows an empty password as empty" {
  UNATTENDED="N"
  ZMBKP_REST_ADMIN_PASS=""
  run check_config_java <<< ""
  [[ "$output" == *"Zimbra REST Admin Password: (empty)"* ]]
}

@test "check_required_java: passes when every required value is set" {
  OSE_INSTALL_ADDRESS="mail.example.com"
  OSE_INSTALL_LDAPPASS="ldapsecret"
  ZMBKP_REST_ADMIN="admin@example.com"
  ZMBKP_REST_ADMIN_PASS="restsecret"
  run check_required_java
  [ "$status" -eq 0 ]
}

@test "check_required_java: exits ERR_MISSING_VALUE naming the missing REST admin password" {
  OSE_INSTALL_ADDRESS="mail.example.com"
  OSE_INSTALL_LDAPPASS="ldapsecret"
  ZMBKP_REST_ADMIN="admin@example.com"
  ZMBKP_REST_ADMIN_PASS=""
  run check_required_java
  [ "$status" -eq 5 ]
  [[ "$output" == *"ZMBKP_REST_ADMIN_PASS"* ]]
}

@test "check_required_java: rejects an admin account whose domain could not be detected" {
  OSE_INSTALL_ADDRESS="mail.example.com"
  OSE_INSTALL_LDAPPASS="ldapsecret"
  ZMBKP_REST_ADMIN="admin@"
  ZMBKP_REST_ADMIN_PASS="restsecret"
  run check_required_java
  [ "$status" -eq 5 ]
  [[ "$output" == *"ZMBKP_REST_ADMIN"* ]]
}

@test "probe_ca_against_server: falls back to Zimbra's bundled openssl when there is no system one" {
  local fake_bin="$BATS_TEST_TMPDIR/bin"
  OSE_INSTALL_DIR="$BATS_TEST_TMPDIR/zimbra"
  mkdir -p "$fake_bin" "$OSE_INSTALL_DIR/common/bin"
  ln -s "$(command -v timeout)" "$fake_bin/timeout"
  printf '#!/bin/bash\necho "$*" > %q\nprintf -- "-----BEGIN CERTIFICATE-----\\nVerify return code: 0 (ok)\\n"\n' \
    "$BATS_TEST_TMPDIR/openssl-args" > "$OSE_INSTALL_DIR/common/bin/openssl"
  chmod +x "$OSE_INSTALL_DIR/common/bin/openssl"
  OSE_INSTALL_ADDRESS="mail.example.com"
  local old_path="$PATH"
  PATH="$fake_bin"
  run probe_ca_against_server /tmp/ca.pem
  PATH="$old_path"
  [ "$status" -eq 0 ]
  [[ "$(cat "$BATS_TEST_TMPDIR/openssl-args")" == "s_client -connect mail.example.com:7071 -CAfile /tmp/ca.pem -verify_hostname mail.example.com"* ]]
}

@test "probe_ca_against_server: reports it could not check when no openssl exists at all" {
  local fake_bin="$BATS_TEST_TMPDIR/bin"
  mkdir -p "$fake_bin"
  OSE_INSTALL_DIR="$BATS_TEST_TMPDIR/no-zimbra"
  local old_path="$PATH"
  PATH="$fake_bin"
  run probe_ca_against_server /tmp/ca.pem
  PATH="$old_path"
  [ "$status" -eq 2 ]
}

ca_config() {
  ZMBKP_CONF="$BATS_TEST_TMPDIR/conf"
  mkdir -p "$ZMBKP_CONF"
  cp "${PROJECT_ROOT}/project/config/zmbackup.yaml" "$ZMBKP_CONF/zmbackup.yaml"
  detect_ca_default() { ZMBKP_CA_DEFAULT="/opt/zimbra/ssl/zimbra/ca/ca.pem"; }
}

set_ca_in_section() {
  awk -v section="$1:" '
    /^[^ #]/ { inside = ($1 == section) }
    inside && /^  # caCertificatePath:/ { print "  caCertificatePath: /opt/zimbra/ssl/zimbra/ca/ca.pem"; next }
    { print }
  ' "$ZMBKP_CONF/zmbackup.yaml" > "$ZMBKP_CONF/new" && mv "$ZMBKP_CONF/new" "$ZMBKP_CONF/zmbackup.yaml"
}

@test "warn_upgrade_without_ca: names both sections when neither sets a CA" {
  ca_config
  run warn_upgrade_without_ca
  [[ "$output" == *"has no caCertificatePath in zimbraLdap zimbraMailbox"* ]]
  [[ "$output" == *"Set zimbraLdap.caCertificatePath zimbraMailbox.caCertificatePath to /opt/zimbra/ssl/zimbra/ca/ca.pem"* ]]
}

@test "warn_upgrade_without_ca: warns about the mailbox section when only LDAP sets a CA" {
  ca_config
  set_ca_in_section zimbraLdap
  run warn_upgrade_without_ca
  [[ "$output" == *"has no caCertificatePath in zimbraMailbox,"* ]]
  [[ "$output" == *"Set zimbraMailbox.caCertificatePath to"* ]]
}

@test "warn_upgrade_without_ca: stays quiet when both sections set a CA" {
  ca_config
  set_ca_in_section zimbraLdap
  set_ca_in_section zimbraMailbox
  run warn_upgrade_without_ca
  [ -z "$output" ]
}

@test "warn_upgrade_without_ca: accepts trustAllCertificates in a section" {
  ca_config
  set_ca_in_section zimbraLdap
  sed -i '/^zimbraMailbox:/,/^backup:/s/^  trustAllCertificates: false/  trustAllCertificates: true/' "$ZMBKP_CONF/zmbackup.yaml"
  run warn_upgrade_without_ca
  [ -z "$output" ]
}

@test "resolve_ca_cert: stops with exit 5 when the service user cannot read the CA" {
  ZMBKP_CA_CERT="$BATS_TEST_TMPDIR/ca.pem"
  touch "$ZMBKP_CA_CERT"
  OSE_USER="zimbra"
  export MOCK_SU_FAIL=1
  run resolve_ca_cert
  [ "$status" -eq 5 ]
  [[ "$output" == *"zimbra cannot read CA certificate $ZMBKP_CA_CERT"* ]]
}

@test "resolve_ca_cert: accepts a CA the service user can read" {
  ZMBKP_CA_CERT="$BATS_TEST_TMPDIR/ca.pem"
  touch "$ZMBKP_CA_CERT"
  ZMBKP_CA_PROBED_PATH="$ZMBKP_CA_CERT"
  ZMBKP_CA_PROBED_RESULT=0
  run resolve_ca_cert
  [ "$status" -eq 0 ]
}
