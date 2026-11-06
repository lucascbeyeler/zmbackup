#!/bin/bash

function render_config_java() {
  local FILE="$1"
  local LDAP_ADDRESS="$OSE_INSTALL_ADDRESS"
  [[ "$LDAP_ADDRESS" == *:* ]] && LDAP_ADDRESS="[$LDAP_ADDRESS]"
  sed -i "s|{OSE_DEFAULT_BKP_DIR}|$(sed_replacement "$OSE_DEFAULT_BKP_DIR")|g" "$FILE"
  sed -i "s|{ZMBKP_MAIL_ALERT}|$(sed_replacement "$ZMBKP_MAIL_ALERT")|g" "$FILE"
  sed -i "s|{ZMBKP_MAIL_SENDER}|$(sed_replacement "$ZMBKP_MAIL_SENDER")|g" "$FILE"
  sed -i "s|{OSE_INSTALL_ADDRESS}|$(sed_replacement "$LDAP_ADDRESS")|g" "$FILE"
  sed -i "s|{OSE_INSTALL_LDAPPASS}|$(sed_replacement "$(yaml_single_quoted "$OSE_INSTALL_LDAPPASS")")|g" "$FILE"
  sed -i "s|{ZMBKP_REST_ADMIN}|$(sed_replacement "$ZMBKP_REST_ADMIN")|g" "$FILE"
  sed -i "s|{ZMBKP_REST_ADMIN_PASS}|$(sed_replacement "$(yaml_single_quoted "$ZMBKP_REST_ADMIN_PASS")")|g" "$FILE"
  sed -i "s|{OSE_USER}|$(sed_replacement "$OSE_USER")|g" "$FILE"
  sed -i "s|{OSE_INSTALL_DIR}|$(sed_replacement "$OSE_INSTALL_DIR")|g" "$FILE"
  sed -i "s|{ZMBKP_CONF}|$(sed_replacement "$ZMBKP_CONF")|g" "$FILE"
  sed -i "s|{MAX_PARALLEL_PROCESS}|$(sed_replacement "$MAX_PARALLEL_PROCESS")|g" "$FILE"
  sed -i "s|{ROTATE_TIME}|$(sed_replacement "$ROTATE_TIME")|g" "$FILE"
  sed -i "s|{LOCK_BACKUP}|$(sed_replacement "$LOCK_BACKUP")|g" "$FILE"
  if [[ -n "$ZMBKP_METADATA_DIR" ]]; then
    sed -i "s|^  # metadataDir: .*|  metadataDir: $(sed_replacement "$ZMBKP_METADATA_DIR")|" "$FILE"
  fi
  if [[ -n "$ZMBKP_CA_CERT" && "$ZMBKP_CA_CERT" != "none" ]]; then
    sed -i "s|^  # caCertificatePath: .*|  caCertificatePath: $(sed_replacement "$(yaml_single_quoted "$ZMBKP_CA_CERT")")|" "$FILE"
  fi
}

function yaml_leaf_values() {
  awk '
    /^[[:space:]]*(#|$)/ { next }
    {
      match($0, /^ */)
      indent = RLENGTH
      level = int(indent / 2)
      line = substr($0, indent + 1)
      if (pending != "") {
        if (level <= pending_level) print pending "\t"
        pending = ""
      }
      if (match(line, /^[A-Za-z_][A-Za-z0-9_]*:/) == 0) next
      key = substr(line, 1, RLENGTH - 1)
      value = substr(line, RLENGTH + 1)
      sub(/^ +/, "", value)
      stack[level] = key
      for (i in stack) if (i + 0 > level) delete stack[i]
      path = stack[0]
      for (i = 1; i <= level; i++) path = path "." stack[i]
      if (value == "") {
        pending = path
        pending_level = level
        next
      }
      print path "\t" value
    }
    END { if (pending != "") print pending "\t" }
  ' "$1"
}

function missing_config_keys() {
  awk -F'\t' 'NR == FNR { if ($2 != "") have[$1] = 1; next } !($1 in have)' \
    <(yaml_leaf_values "$2") <(yaml_leaf_values "$1")
}

function yaml_insert_leaf() {
  local FILE="$1" KEY_PATH="$2" VALUE="$3"
  local PARTS
  IFS=. read -r -a PARTS <<< "$KEY_PATH"
  local COUNT=${#PARTS[@]}
  local FOUND AFTER
  read -r FOUND AFTER < <(awk -v path="$KEY_PATH" '
    BEGIN { parents = split(path, p, ".") - 1 }
    /^[[:space:]]*(#|$)/ { next }
    {
      match($0, /^ */)
      level = int(RLENGTH / 2)
      line = substr($0, RLENGTH + 1)
      depth = level
      if (match(line, /^[A-Za-z_][A-Za-z0-9_]*:/)) {
        stack[level] = substr(line, 1, RLENGTH - 1)
        for (i in stack) if (i + 0 > level) delete stack[i]
        depth = level + 1
      }
      matched = 0
      for (d = 1; d <= parents && d <= depth; d++) {
        if (stack[d - 1] != p[d]) break
        matched = d
      }
      for (d = 1; d <= matched; d++) last[d] = NR
    }
    END {
      found = 0
      for (d = 1; d <= parents; d++) {
        if (!(d in last)) break
        found = d
      }
      print found, (found ? last[found] : 0)
    }
  ' "$FILE")
  local TEXT="" LEVEL
  for ((LEVEL = FOUND; LEVEL < COUNT - 1; LEVEL++)); do
    TEXT+="$(printf '%*s' $((LEVEL * 2)) '')${PARTS[LEVEL]}:"$'\n'
  done
  TEXT+="$(printf '%*s' $(((COUNT - 1) * 2)) '')${PARTS[COUNT - 1]}: $VALUE"
  local RESULT
  RESULT=$(TEXT="$TEXT" awk -v after="$AFTER" '
    { print }
    NR == after { print ENVIRON["TEXT"] }
    END { if (after == 0) print ENVIRON["TEXT"] }
  ' "$FILE")
  printf '%s\n' "$RESULT" > "$FILE"
}

function set_section_ca() {
  local FILE="$1" SECTION="$2" VALUE="$3"
  local RESULT
  if RESULT=$(VALUE="$VALUE" awk -v section="$SECTION:" '
    /^[^ #]/ { inside = ($1 == section) }
    inside && !done && /^  # caCertificatePath:/ { print "  caCertificatePath: " ENVIRON["VALUE"]; done = 1; next }
    { print }
    END { exit !done }
  ' "$FILE"); then
    printf '%s\n' "$RESULT" > "$FILE"
  else
    yaml_insert_leaf "$FILE" "$SECTION.caCertificatePath" "$VALUE"
  fi
}

function review_config_java() {
  ZMBKP_CONFIG_ADD=()
  ZMBKP_CONFIG_CA_SECTIONS=()
  ZMBKP_CONFIG_CA=""
  local CONFIG="$ZMBKP_CONF/zmbackup.yaml"
  printf "  Config File...	          "
  if [[ ! -f "$CONFIG" ]]; then
    printf "[NOT FOUND]\n"
    return 0
  fi
  local TEMPLATE MISSING_KEYS=() SECTION
  TEMPLATE=$(mktemp)
  cp "$MYDIR/project/config/zmbackup.yaml" "$TEMPLATE"
  ZMBKP_CA_CERT="" ZMBKP_METADATA_DIR="" render_config_java "$TEMPLATE"
  mapfile -t MISSING_KEYS < <(missing_config_keys "$TEMPLATE" "$CONFIG")
  rm -f "$TEMPLATE"
  for SECTION in zimbraLdap zimbraMailbox; do
    config_section_sets_trust "$CONFIG" "$SECTION" || ZMBKP_CONFIG_CA_SECTIONS+=("$SECTION")
  done
  if [[ ${#MISSING_KEYS[@]} -eq 0 && ${#ZMBKP_CONFIG_CA_SECTIONS[@]} -eq 0 ]]; then
    printf "[COMPLETE]\n"
    return 0
  fi
  printf "[INCOMPLETE]\n"
  if [[ $UNATTENDED == "Y" ]]; then
    if [[ ${#MISSING_KEYS[@]} -gt 0 ]]; then
      echo "[WARN] - $CONFIG is missing ${MISSING_KEYS[*]%%$'\t'*}. Run ./install-java.sh --force-upgrade without --unattended to add them."
    fi
    return 0
  fi
  ask_missing_config_java "$CONFIG" "${MISSING_KEYS[@]}"
}

function ask_missing_config_java() {
  local CONFIG="$1"
  shift
  local ENTRY KEY_PATH DEFAULT SHOWN TMP
  if [[ $# -gt 0 ]]; then
    echo "$CONFIG is missing settings this version uses. Inform each one, or press ENTER for the default."
  fi
  for ENTRY in "$@"; do
    KEY_PATH="${ENTRY%%$'\t'*}"
    DEFAULT="${ENTRY#*$'\t'}"
    SHOWN="$DEFAULT"
    [[ "$KEY_PATH" == *Password ]] && SHOWN=$(masked_secret "$DEFAULT")
    printf "\nInform %s - DEFAULT [%s]:" "$KEY_PATH" "$SHOWN"
    read -r TMP
    if [[ -n "$TMP" && "$KEY_PATH" == *Password ]]; then
      TMP=$(yaml_single_quoted "$TMP")
    fi
    TMP="${TMP:-$DEFAULT}"
    if [[ -z "$TMP" ]]; then
      echo "[WARN] - $KEY_PATH stays unset. Zmbackup will not start until it is set in $CONFIG."
      continue
    fi
    ZMBKP_CONFIG_ADD+=("$KEY_PATH"$'\t'"$TMP")
  done
  if [[ ${#ZMBKP_CONFIG_CA_SECTIONS[@]} -gt 0 ]]; then
    detect_ca_default
    printf "\n%s has no CA certificate for %s. Inform the CA certificate Zmbackup should trust for this server, or none to use the Java trust store - DEFAULT [%s]:" \
      "$CONFIG" "${ZMBKP_CONFIG_CA_SECTIONS[*]}" "$ZMBKP_CA_DEFAULT"
    read -r TMP
    ZMBKP_CA_CERT="${TMP:-$ZMBKP_CA_DEFAULT}"
    resolve_ca_cert
    ZMBKP_CONFIG_CA="$ZMBKP_CA_CERT"
  fi
  echo ""
}

function complete_config_java() {
  local CONFIG="$ZMBKP_CONF/zmbackup.yaml"
  local SET_CA="N"
  [[ -n "${ZMBKP_CONFIG_CA:-}" && "$ZMBKP_CONFIG_CA" != "none" ]] && SET_CA="Y"
  [[ ${#ZMBKP_CONFIG_ADD[@]} -eq 0 && $SET_CA == "N" ]] && return 0
  local BACKUP ENTRY SECTION
  BACKUP="$CONFIG.$(date +%Y%m%d%H%M%S).bak"
  cp -p "$CONFIG" "$BACKUP"
  echo "Saved the previous configuration as $BACKUP"
  for ENTRY in "${ZMBKP_CONFIG_ADD[@]}"; do
    yaml_insert_leaf "$CONFIG" "${ENTRY%%$'\t'*}" "${ENTRY#*$'\t'}"
    echo "Added ${ENTRY%%$'\t'*} to $CONFIG"
  done
  if [[ $SET_CA == "Y" ]]; then
    for SECTION in "${ZMBKP_CONFIG_CA_SECTIONS[@]}"; do
      set_section_ca "$CONFIG" "$SECTION" "$(yaml_single_quoted "$ZMBKP_CONFIG_CA")"
      echo "Set $SECTION.caCertificatePath to $ZMBKP_CONFIG_CA"
    done
  fi
}
