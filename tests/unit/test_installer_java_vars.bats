#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
}

@test "vars: ERR_OK is 0" {
  [ "$ERR_OK" = "0" ]
}

@test "vars: ERR_NOROOT is 2" {
  [ "$ERR_NOROOT" = "2" ]
}

@test "vars: ERR_DEPNOTFOUND is 3" {
  [ "$ERR_DEPNOTFOUND" = "3" ]
}

@test "vars: ERR_NO_CONNECTION is 4" {
  [ "$ERR_NO_CONNECTION" = "4" ]
}

@test "vars: ERR_BUILD_FAILED is 6" {
  [ "$ERR_BUILD_FAILED" = "6" ]
}

@test "vars: ZMBKP_SRC is /usr/local/bin" {
  [ "$ZMBKP_SRC" = "/usr/local/bin" ]
}

@test "vars: ZMBKP_CONF is /etc/zmbackup" {
  [ "$ZMBKP_CONF" = "/etc/zmbackup" ]
}

@test "vars: ZMBKP_LIB is /usr/local/lib/zmbackup" {
  [ "$ZMBKP_LIB" = "/usr/local/lib/zmbackup" ]
}

@test "vars: ZMBKP_JAR_NAME is zmbackup.jar" {
  [ "$ZMBKP_JAR_NAME" = "zmbackup.jar" ]
}

@test "vars: JAVA_MIN_VERSION is 17" {
  [ "$JAVA_MIN_VERSION" = "17" ]
}

@test "vars: OSE_USER is zimbra" {
  [ "$OSE_USER" = "zimbra" ]
}

@test "vars: OSE_INSTALL_DIR is /opt/zimbra" {
  [ "$OSE_INSTALL_DIR" = "/opt/zimbra" ]
}

@test "vars: OSE_DEFAULT_BKP_DIR is /opt/zimbra/backup" {
  [ "$OSE_DEFAULT_BKP_DIR" = "/opt/zimbra/backup" ]
}

@test "vars: MAX_PARALLEL_PROCESS is 3" {
  [ "$MAX_PARALLEL_PROCESS" = "3" ]
}

@test "vars: ROTATE_TIME is 30" {
  [ "$ROTATE_TIME" = "30" ]
}

@test "vars: LOCK_BACKUP is true" {
  [ "$LOCK_BACKUP" = "true" ]
}

@test "vars: ZMBKP_VERSION has the expected prefix" {
  [[ "$ZMBKP_VERSION" == "zmbackup version: "* ]]
}

@test "vars: ZMBKP_VERSION reads the real VERSION file when MYDIR points at the repo" {
  MYDIR="$PROJECT_ROOT"
  ZMBKP_VERSION="zmbackup version: $(cat "$MYDIR/VERSION")"
  [[ "$ZMBKP_VERSION" == *"$(cat "$MYDIR/VERSION")"* ]]
}

@test "vars: TERM is set to linux" {
  [ "$TERM" = "linux" ]
}

@test "vars: OSE_INSTALL_ADDRESS defaults to the server's FQDN, not a resolved IP" {
  [ "$OSE_INSTALL_ADDRESS" = "mail.example.com" ]
}

@test "vars: ZMBKP_CRON_OWNER is root" {
  [ "$ZMBKP_CRON_OWNER" = "root" ]
}

@test "vars: ZMBKP_METADATA_DIR defaults to empty" {
  [ -z "$ZMBKP_METADATA_DIR" ]
}

@test "vars: ERR_MISSING_VALUE is 5" {
  [ "$ERR_MISSING_VALUE" = "5" ]
}

@test "vars: every prompted setting can be preset through the environment" {
  run env OSE_USER=svc OSE_INSTALL_DIR=/srv/zimbra OSE_DEFAULT_BKP_DIR=/mnt/backup \
    OSE_INSTALL_DOMAIN=corp.example OSE_INSTALL_ADDRESS=zimbra.corp.example \
    OSE_INSTALL_LDAPPASS=ldapsecret ZMBKP_REST_ADMIN=ops@corp.example ZMBKP_REST_ADMIN_PASS=restsecret \
    ZMBKP_METADATA_DIR=/var/zmbackup ZMBKP_MAIL_ALERT=alerts@corp.example ZMBKP_MAIL_SENDER=noreply@corp.example \
    MAX_PARALLEL_PROCESS=8 ROTATE_TIME=7 LOCK_BACKUP=false \
    PATH="${MOCKS_DIR}:${PATH}" bash -c "
      source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null
      echo \"\$OSE_USER|\$OSE_INSTALL_DIR|\$OSE_DEFAULT_BKP_DIR|\$OSE_INSTALL_ADDRESS|\$OSE_INSTALL_LDAPPASS\"
      echo \"\$ZMBKP_REST_ADMIN|\$ZMBKP_REST_ADMIN_PASS|\$ZMBKP_METADATA_DIR|\$ZMBKP_MAIL_ALERT|\$ZMBKP_MAIL_SENDER\"
      echo \"\$MAX_PARALLEL_PROCESS|\$ROTATE_TIME|\$LOCK_BACKUP\"
    "
  [[ "$output" == *"svc|/srv/zimbra|/mnt/backup|zimbra.corp.example|ldapsecret"* ]]
  [[ "$output" == *"ops@corp.example|restsecret|/var/zmbackup|alerts@corp.example|noreply@corp.example"* ]]
  [[ "$output" == *"8|7|false"* ]]
}

@test "vars: a preset OSE_INSTALL_DOMAIN drives the admin and alert defaults" {
  run env OSE_INSTALL_DOMAIN=corp.example PATH="${MOCKS_DIR}:${PATH}" bash -c "
    source '${INSTALLER_JAVA_DIR}/vars.sh' 2>/dev/null
    echo \"\$ZMBKP_REST_ADMIN|\$ZMBKP_MAIL_ALERT|\$ZMBKP_MAIL_SENDER\"
  "
  [[ "$output" == *"admin@corp.example|admin@corp.example|root@corp.example"* ]]
}
