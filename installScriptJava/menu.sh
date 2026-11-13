#!/bin/bash

function set_values_java() {
  echo "##################################################################################"
  echo "#                                                                                #"
  echo "# The follow messages will ask you about some configurations for Zmbackup run in #"
  echo "# your server. Please answer each one of then or press ENTER to assume the       #"
  echo "# default value.                                                                 #"
  echo "#                                                                                #"
  echo "##################################################################################"
  echo -e "\n"

  printf "Inform Zimbra's default user - DEFAULT [%s]:" "$OSE_USER"
  read -r TMP
  OSE_USER=${TMP:-$OSE_USER}

  printf "\nInform Zimbra's default install path - DEFAULT [%s]:" "$OSE_INSTALL_DIR"
  read -r TMP
  OSE_INSTALL_DIR=${TMP:-$OSE_INSTALL_DIR}

  printf "\nInform the Zimbra server hostname Zmbackup should connect to (must match its TLS certificate) - DEFAULT [%s]:" "$OSE_INSTALL_ADDRESS"
  read -r TMP
  OSE_INSTALL_ADDRESS=${TMP:-$OSE_INSTALL_ADDRESS}

  printf "\nInform the path Zmbackup will use to store - DEFAULT [%s]:" "$OSE_DEFAULT_BKP_DIR"
  read -r TMP
  OSE_DEFAULT_BKP_DIR=${TMP:-$OSE_DEFAULT_BKP_DIR}

  printf "\nInform a local directory for Zmbackup's metadata database and lock file, recommended when the backup path is a network share - DEFAULT [same as backup path]:"
  read -r TMP
  ZMBKP_METADATA_DIR=${TMP:-$ZMBKP_METADATA_DIR}

  printf "\nInform the Zimbra admin account Zmbackup should use for mailbox REST auth - DEFAULT [%s]:" "$ZMBKP_REST_ADMIN"
  read -r TMP
  ZMBKP_REST_ADMIN=${TMP:-$ZMBKP_REST_ADMIN}

  printf "\nInform %s's own Zimbra password (not the LDAP password above):" "$ZMBKP_REST_ADMIN"
  read -r TMP
  ZMBKP_REST_ADMIN_PASS=${TMP:-$ZMBKP_REST_ADMIN_PASS}

  printf "\nInform the account to receive all Zmbackup's alerts - DEFAULT [%s]:" "$ZMBKP_MAIL_ALERT"
  read -r TMP
  ZMBKP_MAIL_ALERT=${TMP:-$ZMBKP_MAIL_ALERT}

  printf "\nInform the account Zmbackup should send alerts from - DEFAULT [%s]:" "$ZMBKP_MAIL_SENDER"
  read -r TMP
  ZMBKP_MAIL_SENDER=${TMP:-$ZMBKP_MAIL_SENDER}

  printf "\nInform Zmbackup's number of parallel workers - DEFAULT [%s]:" "$MAX_PARALLEL_PROCESS"
  read -r TMP
  MAX_PARALLEL_PROCESS=${TMP:-$MAX_PARALLEL_PROCESS}

  printf "\nInform the number of days Zmbackup should store the backups - DEFAULT [%s]:" "$ROTATE_TIME"
  read -r TMP
  ROTATE_TIME=${TMP:-$ROTATE_TIME}

  printf "\nZmbackup should limit backups for one per day? - DEFAULT [%s]:" "$LOCK_BACKUP"
  read -r TMP
  LOCK_BACKUP=${TMP:-$LOCK_BACKUP}

  detect_ca_default
  printf "\nInform the CA certificate Zmbackup should trust for this server, or none to use the Java trust store - DEFAULT [%s]:" "${ZMBKP_CA_CERT:-$ZMBKP_CA_DEFAULT}"
  read -r TMP
  ZMBKP_CA_CERT=${TMP:-${ZMBKP_CA_CERT:-$ZMBKP_CA_DEFAULT}}

  echo -e "\n\n"
  echo "##################################################################################"
  echo "#                                                                                #"
  echo "#                            CONFIGURATION COMPLETED                             #"
  echo "#                                                                                #"
  echo "##################################################################################"
}

function masked_secret() {
  if [[ -n "$1" ]]; then
    printf '********'
  else
    printf '(empty)'
  fi
}

function check_config_java() {
  echo ""
  echo "Here is a Summary of your settings:"
  echo ""
  echo "Detected Mail Suite: $OSE_SUITE"
  echo "Zimbra User: $OSE_USER"
  echo "Zimbra Server Address: $OSE_INSTALL_ADDRESS"
  echo "Zimbra LDAP/Admin Password: $(masked_secret "$OSE_INSTALL_LDAPPASS")"
  echo "Zimbra REST Admin Account: $ZMBKP_REST_ADMIN"
  echo "Zimbra REST Admin Password: $(masked_secret "$ZMBKP_REST_ADMIN_PASS")"
  echo "Zimbra CA Certificate: $ZMBKP_CA_CERT"
  echo "Zimbra Install Directory: $OSE_INSTALL_DIR"
  echo "Zimbra Backup Directory: $OSE_DEFAULT_BKP_DIR"
  echo "Zmbackup Launcher: $ZMBKP_SRC/zmbackup"
  echo "Zmbackup Jar: $ZMBKP_LIB/$ZMBKP_JAR_NAME"
  echo "Zmbackup Settings Directory: $ZMBKP_CONF"
  echo "Zmbackup Backups Days Max: $ROTATE_TIME"
  echo "Zmbackup Number of Parallel Workers: $MAX_PARALLEL_PROCESS"
  echo "Zmbackup Backup Lock: $LOCK_BACKUP"
  echo "Zmbackup Session Storage: SQLite3 (${ZMBKP_METADATA_DIR:-$OSE_DEFAULT_BKP_DIR}/sessions.sqlite3)"
  echo ""
  if [[ $UNATTENDED == "Y" ]]; then
    return
  fi
  echo "Press ENTER to continue or CTRL+C to cancel."
  read -r
}

function check_required_java() {
  local MISSING=""
  [[ -z "$OSE_INSTALL_ADDRESS" ]] && MISSING="$MISSING OSE_INSTALL_ADDRESS"
  [[ -z "$OSE_INSTALL_LDAPPASS" ]] && MISSING="$MISSING OSE_INSTALL_LDAPPASS"
  [[ -z "$ZMBKP_REST_ADMIN" || "$ZMBKP_REST_ADMIN" == "admin@" ]] && MISSING="$MISSING ZMBKP_REST_ADMIN"
  [[ -z "$ZMBKP_REST_ADMIN_PASS" ]] && MISSING="$MISSING ZMBKP_REST_ADMIN_PASS"
  if [[ -n "$MISSING" ]]; then
    echo "[FAIL] - Unattended install is missing required values:$MISSING"
    echo "Export them as environment variables and run the installer again."
    exit "$ERR_MISSING_VALUE"
  fi
}

function openssl_binary() {
  if command -v openssl > /dev/null 2>&1; then
    command -v openssl
  elif [[ -x "$OSE_INSTALL_DIR/common/bin/openssl" ]]; then
    echo "$OSE_INSTALL_DIR/common/bin/openssl"
  else
    return 1
  fi
}

function probe_ca_against_server() {
  local OPENSSL
  OPENSSL=$(openssl_binary) || return 2
  local ADDRESS="$OSE_INSTALL_ADDRESS"
  local NAME_CHECK=(-verify_hostname "$OSE_INSTALL_ADDRESS" -servername "$OSE_INSTALL_ADDRESS")
  if [[ "$ADDRESS" == *:* ]]; then
    ADDRESS="[$ADDRESS]"
    NAME_CHECK=(-verify_ip "$OSE_INSTALL_ADDRESS")
  elif [[ "$ADDRESS" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    NAME_CHECK=(-verify_ip "$OSE_INSTALL_ADDRESS")
  fi
  local OUTPUT
  OUTPUT=$(timeout 10 "$OPENSSL" s_client -connect "$ADDRESS:7071" -CAfile "$1" "${NAME_CHECK[@]}" < /dev/null 2> /dev/null)
  [[ "$OUTPUT" == *"BEGIN CERTIFICATE"* ]] || return 2
  [[ "$OUTPUT" == *"Verify return code: 0 (ok)"* ]] && return 0
  return 1
}

function suite_ca_file() {
  local CANDIDATE
  for CANDIDATE in "$1/ssl/zimbra/ca/ca.pem" "$1/ssl/carbonio/ca/ca.pem"; do
    if [[ -f "$CANDIDATE" ]]; then
      echo "$CANDIDATE"
      return 0
    fi
  done
  return 1
}

function detect_ca_default() {
  ZMBKP_CA_DEFAULT="none"
  local CANDIDATE
  CANDIDATE=$(suite_ca_file "$OSE_INSTALL_DIR") || return 0
  local RESULT=0
  probe_ca_against_server "$CANDIDATE" || RESULT=$?
  ZMBKP_CA_PROBED_PATH="$CANDIDATE"
  ZMBKP_CA_PROBED_RESULT="$RESULT"
  case "$RESULT" in
    0)
      ZMBKP_CA_DEFAULT="$CANDIDATE"
      echo "$CANDIDATE validates the certificate of $OSE_INSTALL_ADDRESS."
      ;;
    1)
      echo "[WARN] - $CANDIDATE does not validate the certificate of $OSE_INSTALL_ADDRESS, so Zmbackup will use the Java trust store."
      ;;
    *)
      ZMBKP_CA_DEFAULT="$CANDIDATE"
      echo "[WARN] - Could not check the certificate of $OSE_INSTALL_ADDRESS, so Zmbackup will trust $CANDIDATE. Test it with: zmbackup accounts list"
      ;;
  esac
}

function resolve_ca_cert() {
  if [[ -z "$ZMBKP_CA_CERT" ]]; then
    detect_ca_default
    ZMBKP_CA_CERT="$ZMBKP_CA_DEFAULT"
  fi
  [[ "$ZMBKP_CA_CERT" == "none" ]] && return 0
  if [[ ! -f "$ZMBKP_CA_CERT" ]]; then
    echo "[FAIL] - CA certificate $ZMBKP_CA_CERT does not exist. Use an existing file, or none to use the Java trust store."
    exit "$ERR_MISSING_VALUE"
  fi
  if ! sudo -H -u "$OSE_USER" test -r "$ZMBKP_CA_CERT"; then
    echo "[FAIL] - $OSE_USER cannot read CA certificate $ZMBKP_CA_CERT, so every Zmbackup job would fail. Make it readable by $OSE_USER, or use none to use the Java trust store."
    exit "$ERR_MISSING_VALUE"
  fi
  local RESULT=0
  if [[ "$ZMBKP_CA_CERT" == "${ZMBKP_CA_PROBED_PATH:-}" ]]; then
    RESULT="$ZMBKP_CA_PROBED_RESULT"
  else
    probe_ca_against_server "$ZMBKP_CA_CERT" || RESULT=$?
  fi
  if [[ "$RESULT" -eq 1 ]]; then
    echo "[WARN] - $ZMBKP_CA_CERT does not validate the certificate of $OSE_INSTALL_ADDRESS. Zmbackup will refuse to connect until this is corrected."
  fi
}

function config_section_sets_trust() {
  awk -v section="$2:" '
    /^[^ #]/ { inside = ($1 == section) }
    inside && (/^  caCertificatePath:/ || /^  trustAllCertificates: true/) { found = 1 }
    END { exit !found }
  ' "$1"
}
