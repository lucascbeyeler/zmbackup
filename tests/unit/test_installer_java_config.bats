#!/usr/bin/env bats

load '../setup'

INSTALLER_JAVA_DIR="${PROJECT_ROOT}/installScriptJava"

setup() {
  setup_mock_path
  source "${INSTALLER_JAVA_DIR}/vars.sh" 2>/dev/null || true
  source "${INSTALLER_JAVA_DIR}/menu.sh"
  source "${INSTALLER_JAVA_DIR}/deploy.sh"
  source "${INSTALLER_JAVA_DIR}/config.sh"

  MYDIR="$PROJECT_ROOT"
  ZMBKP_CONF="$BATS_TEST_TMPDIR/conf"
  CONFIG="$ZMBKP_CONF/zmbackup.yaml"
  mkdir -p "$ZMBKP_CONF"
  OSE_USER="zimbra"
  OSE_INSTALL_DIR="/opt/zimbra"
  OSE_INSTALL_ADDRESS="mail.example.com"
  OSE_INSTALL_LDAPPASS="ldap-from-localconfig"
  OSE_DEFAULT_BKP_DIR="/opt/zimbra/backup"
  ZMBKP_REST_ADMIN="admin@example.com"
  ZMBKP_REST_ADMIN_PASS=""
  ZMBKP_MAIL_ALERT="admin@example.com"
  ZMBKP_MAIL_SENDER="root@example.com"
  ZMBKP_METADATA_DIR=""
  ZMBKP_CA_CERT=""
  MAX_PARALLEL_PROCESS="3"
  ROTATE_TIME="30"
  LOCK_BACKUP="true"
  UNATTENDED="N"
  detect_ca_default() {
    ZMBKP_CA_DEFAULT="/opt/zimbra/ssl/zimbra/ca/ca.pem"
    ZMBKP_CA_PROBED_PATH="$ZMBKP_CA_DEFAULT"
    ZMBKP_CA_PROBED_RESULT=0
  }
}

complete_config() {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  ZMBKP_REST_ADMIN_PASS="installed-pass" ZMBKP_CA_CERT="/opt/zimbra/ssl/zimbra/ca/ca.pem" render_config_java "$CONFIG"
}

leaf() {
  yaml_leaf_values "$CONFIG" | awk -F'\t' -v key="$1" '$1 == key { print $2 }'
}

@test "yaml_leaf_values: lists nested settings by dotted path and skips comments" {
  complete_config
  run yaml_leaf_values "$CONFIG"
  [[ "$output" == *$'zimbraLdap.url\tldap://mail.example.com:389'* ]]
  [[ "$output" == *$'backup.emailNotify.level\tALL'* ]]
  [[ "$output" == *$'zimbraMailbox.adminPassword\t\'installed-pass\''* ]]
  [[ "$output" != *"responseTimeoutSeconds"* ]]
}

@test "missing_config_keys: a complete config misses nothing" {
  complete_config
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$BATS_TEST_TMPDIR/template"
  render_config_java "$BATS_TEST_TMPDIR/template"
  run missing_config_keys "$BATS_TEST_TMPDIR/template" "$CONFIG"
  [ -z "$output" ]
}

@test "missing_config_keys: lists what an older config lacks, with the default this server would get" {
  complete_config
  sed -i '/^storage:/,/^  backend:/d; /^  rotateDays:/d' "$CONFIG"
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$BATS_TEST_TMPDIR/template"
  render_config_java "$BATS_TEST_TMPDIR/template"
  run missing_config_keys "$BATS_TEST_TMPDIR/template" "$CONFIG"
  [ "${#lines[@]}" -eq 2 ]
  [ "${lines[0]}" = $'backup.rotateDays\t30' ]
  [ "${lines[1]}" = $'storage.backend\tlocal' ]
}

@test "yaml_insert_leaf: adds a setting at the end of its section" {
  complete_config
  sed -i '/^  rotateDays:/d' "$CONFIG"
  yaml_insert_leaf "$CONFIG" backup.rotateDays 45
  [ "$(leaf backup.rotateDays)" = "45" ]
  [ "$(leaf backup.emailNotify.level)" = "ALL" ]
  run awk '/^backup:/ { inside = 1 } /^storage:/ { inside = 0 } inside && /^  rotateDays: 45$/ { found = 1 } END { exit !found }' "$CONFIG"
  [ "$status" -eq 0 ]
}

@test "yaml_insert_leaf: adds a nested setting inside an existing subsection" {
  complete_config
  sed -i '/^    level: ALL$/d' "$CONFIG"
  yaml_insert_leaf "$CONFIG" backup.emailNotify.level ALL
  [ "$(leaf backup.emailNotify.level)" = "ALL" ]
  [ "$(leaf backup.emailNotify.sender)" = "root@example.com" ]
}

@test "yaml_insert_leaf: creates the sections a setting needs" {
  complete_config
  sed -i '/^storage:/,/^  backend:/d' "$CONFIG"
  sed -i '/^  emailNotify:/,/^    sender:/d' "$CONFIG"
  yaml_insert_leaf "$CONFIG" storage.backend local
  yaml_insert_leaf "$CONFIG" backup.emailNotify.recipient admin@example.com
  [ "$(leaf storage.backend)" = "local" ]
  [ "$(leaf backup.emailNotify.recipient)" = "admin@example.com" ]
  [ "$(leaf backup.lockBackup)" = "true" ]
  [ "$(leaf allowInsecure)" = "false" ]
}

@test "yaml_insert_leaf: keeps backslashes, ampersands and quotes in the value" {
  complete_config
  sed -i '/^  adminPassword:/d' "$CONFIG"
  yaml_insert_leaf "$CONFIG" zimbraMailbox.adminPassword "'a\\b&c''d'"
  [ "$(leaf zimbraMailbox.adminPassword)" = "'a\\b&c''d'" ]
}

@test "yaml_insert_leaf: keeps the file's owner and mode" {
  complete_config
  chmod 600 "$CONFIG"
  sed -i '/^  rotateDays:/d' "$CONFIG"
  yaml_insert_leaf "$CONFIG" backup.rotateDays 30
  [ "$(stat -c %a "$CONFIG")" = "600" ]
}

@test "set_section_ca: replaces the commented example only in the section it is asked for" {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  render_config_java "$CONFIG"
  set_section_ca "$CONFIG" zimbraMailbox "'/ca.pem'"
  [ "$(leaf zimbraMailbox.caCertificatePath)" = "'/ca.pem'" ]
  [ -z "$(leaf zimbraLdap.caCertificatePath)" ]
  [ "$(grep -c '^  # caCertificatePath:' "$CONFIG")" -eq 1 ]
}

@test "set_section_ca: adds the setting when the section has no commented example" {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  render_config_java "$CONFIG"
  sed -i '/^  # caCertificatePath:/d' "$CONFIG"
  set_section_ca "$CONFIG" zimbraLdap "'/ca.pem'"
  [ "$(leaf zimbraLdap.caCertificatePath)" = "'/ca.pem'" ]
}

@test "review_config_java: reports a complete config and asks nothing" {
  complete_config
  run review_config_java < /dev/null
  [ "$status" -eq 0 ]
  [[ "$output" == *"[COMPLETE]"* ]]
  [[ "$output" != *"Inform"* ]]
}

@test "review_config_java: an unattended upgrade lists what is missing and changes nothing" {
  complete_config
  sed -i '/^storage:/,/^  backend:/d; /^  rotateDays:/d' "$CONFIG"
  local before
  before=$(cat "$CONFIG")
  UNATTENDED="Y"
  review_config_java < /dev/null > "$BATS_TEST_TMPDIR/out"
  complete_config_java >> "$BATS_TEST_TMPDIR/out"
  run cat "$BATS_TEST_TMPDIR/out"
  [[ "$output" == *"[INCOMPLETE]"* ]]
  [[ "$output" == *"is missing backup.rotateDays storage.backend. Run ./install-java.sh --force-upgrade without --unattended"* ]]
  [[ "$output" != *"Inform"* ]]
  [ "$before" = "$(cat "$CONFIG")" ]
}

@test "review_config_java: asks for each missing setting and adds the answers after a backup" {
  complete_config
  sed -i '/^  rotateDays:/d; /^  adminPassword:/d; /^    sender:/d' "$CONFIG"
  local before
  before=$(cat "$CONFIG")
  review_config_java > "$BATS_TEST_TMPDIR/out" < <(printf '%s\n' "new'pass" "" "alerts@example.com")
  complete_config_java >> "$BATS_TEST_TMPDIR/out"
  run cat "$BATS_TEST_TMPDIR/out"
  [[ "$output" == *"Inform zimbraMailbox.adminPassword - DEFAULT [(empty)]"* ]]
  [[ "$output" == *"Inform backup.rotateDays - DEFAULT [30]"* ]]
  [[ "$output" == *"Inform backup.emailNotify.sender - DEFAULT [root@example.com]"* ]]
  [[ "$output" == *"Saved the previous configuration as $CONFIG."*".bak"* ]]
  [ "$(leaf zimbraMailbox.adminPassword)" = "'new''pass'" ]
  [ "$(leaf backup.rotateDays)" = "30" ]
  [ "$(leaf backup.emailNotify.sender)" = "alerts@example.com" ]
  [ "$before" = "$(cat "$CONFIG".*.bak)" ]
}

@test "review_config_java: masks a password default and keeps it when the answer is empty" {
  complete_config
  sed -i '/^  bindPassword:/d' "$CONFIG"
  review_config_java > "$BATS_TEST_TMPDIR/out" < <(printf '\n')
  complete_config_java >> "$BATS_TEST_TMPDIR/out"
  run cat "$BATS_TEST_TMPDIR/out"
  [[ "$output" == *"Inform zimbraLdap.bindPassword - DEFAULT [********]"* ]]
  [[ "$output" != *"ldap-from-localconfig"* ]]
  [ "$(leaf zimbraLdap.bindPassword)" = "'ldap-from-localconfig'" ]
}

@test "review_config_java: leaves a required password unset when there is no default and no answer" {
  complete_config
  sed -i '/^  adminPassword:/d' "$CONFIG"
  review_config_java > "$BATS_TEST_TMPDIR/out" < <(printf '\n')
  complete_config_java >> "$BATS_TEST_TMPDIR/out"
  run cat "$BATS_TEST_TMPDIR/out"
  [[ "$output" == *"[WARN] - zimbraMailbox.adminPassword stays unset"* ]]
  [ -z "$(leaf zimbraMailbox.adminPassword)" ]
  [ -z "$(ls "$ZMBKP_CONF"/*.bak 2> /dev/null)" ]
}

@test "review_config_java: asks for a CA once and writes it to every section without one" {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  ZMBKP_REST_ADMIN_PASS="installed-pass" render_config_java "$CONFIG"
  touch "$BATS_TEST_TMPDIR/ca.pem"
  review_config_java > "$BATS_TEST_TMPDIR/out" < <(printf '%s\n' "$BATS_TEST_TMPDIR/ca.pem")
  complete_config_java >> "$BATS_TEST_TMPDIR/out"
  run cat "$BATS_TEST_TMPDIR/out"
  [[ "$output" == *"has no CA certificate for zimbraLdap zimbraMailbox"* ]]
  [[ "$output" == *"DEFAULT [/opt/zimbra/ssl/zimbra/ca/ca.pem]"* ]]
  [ "$(leaf zimbraLdap.caCertificatePath)" = "'$BATS_TEST_TMPDIR/ca.pem'" ]
  [ "$(leaf zimbraMailbox.caCertificatePath)" = "'$BATS_TEST_TMPDIR/ca.pem'" ]
}

@test "review_config_java: only asks about the section that has no CA" {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  ZMBKP_REST_ADMIN_PASS="installed-pass" render_config_java "$CONFIG"
  set_section_ca "$CONFIG" zimbraLdap "'/existing.pem'"
  touch "$BATS_TEST_TMPDIR/ca.pem"
  review_config_java > "$BATS_TEST_TMPDIR/out" < <(printf '%s\n' "$BATS_TEST_TMPDIR/ca.pem")
  complete_config_java >> "$BATS_TEST_TMPDIR/out"
  run cat "$BATS_TEST_TMPDIR/out"
  [[ "$output" == *"has no CA certificate for zimbraMailbox."* ]]
  [ "$(leaf zimbraLdap.caCertificatePath)" = "'/existing.pem'" ]
  [ "$(leaf zimbraMailbox.caCertificatePath)" = "'$BATS_TEST_TMPDIR/ca.pem'" ]
}

@test "review_config_java: an answer of none leaves the CA unset and the config untouched" {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  ZMBKP_REST_ADMIN_PASS="installed-pass" render_config_java "$CONFIG"
  local before
  before=$(cat "$CONFIG")
  review_config_java > /dev/null < <(printf 'none\n')
  complete_config_java
  [ "$before" = "$(cat "$CONFIG")" ]
}

@test "review_config_java: a CA file that does not exist stops with exit 5" {
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  ZMBKP_REST_ADMIN_PASS="installed-pass" render_config_java "$CONFIG"
  run review_config_java < <(printf '/nonexistent/ca.pem\n')
  [ "$status" -eq 5 ]
  [[ "$output" == *"does not exist"* ]]
}

@test "deploy_upgrade_java: only an unattended upgrade prints the missing-CA warning" {
  ZMBKP_SRC="$BATS_TEST_TMPDIR/bin"
  ZMBKP_LIB="$BATS_TEST_TMPDIR/lib"
  ZMBKP_CRON_FILE="$BATS_TEST_TMPDIR/cron"
  MYDIR="$BATS_TEST_TMPDIR/src"
  mkdir -p "$MYDIR/app/build/libs" "$MYDIR/app/src/main/scripts"
  cp "$PROJECT_ROOT/app/src/main/scripts/zmbackup" "$MYDIR/app/src/main/scripts/"
  echo jar > "$MYDIR/app/build/libs/$ZMBKP_JAR_NAME"
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$CONFIG"
  render_config_java "$CONFIG"
  OSE_USER="$(/usr/bin/whoami)"
  ZMBKP_CONFIG_ADD=()
  ZMBKP_CONFIG_CA="none"
  UNATTENDED="N"
  run deploy_upgrade_java
  [[ "$output" != *"has no caCertificatePath"* ]]
  UNATTENDED="Y"
  run deploy_upgrade_java
  [[ "$output" == *"has no caCertificatePath"* ]]
}

@test "yaml_leaf_values: reports a key with no value and nothing under it as an empty setting" {
  printf 'a:\n  empty:\n  sub:\n    x: 1\nlast:\n' > "$CONFIG"
  run yaml_leaf_values "$CONFIG"
  [ "${lines[0]}" = $'a.empty\t' ]
  [ "${lines[1]}" = $'a.sub.x\t1' ]
  [ "${lines[2]}" = $'last\t' ]
  [ "${#lines[@]}" -eq 3 ]
}

@test "missing_config_keys: counts a setting the config leaves empty as missing" {
  complete_config
  sed -i "s|^  adminPassword: .*|  adminPassword:|" "$CONFIG"
  cp "$PROJECT_ROOT/project/config/zmbackup.yaml" "$BATS_TEST_TMPDIR/template"
  render_config_java "$BATS_TEST_TMPDIR/template"
  run missing_config_keys "$BATS_TEST_TMPDIR/template" "$CONFIG"
  [ "$output" = $'zimbraMailbox.adminPassword\t' ]
}
