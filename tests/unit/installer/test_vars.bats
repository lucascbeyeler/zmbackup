#!/usr/bin/env bats

load '../../setup'

setup() {
  setup_mock_path
  # vars.sh runs commands at source time; mocks suppress failures
  source "${INSTALLER_DIR}/vars.sh" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Exit code constants
# ---------------------------------------------------------------------------

@test "vars: ERR_OK is 0" {
  [ "$ERR_OK" = "0" ]
}

@test "vars: ERR_NOBKPDIR is 1" {
  [ "$ERR_NOBKPDIR" = "1" ]
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

@test "vars: ERR_CREATE_USER is 5" {
  [ "$ERR_CREATE_USER" = "5" ]
}

# ---------------------------------------------------------------------------
# Installation paths
# ---------------------------------------------------------------------------

@test "vars: ZMBKP_SRC is /usr/local/bin" {
  [ "$ZMBKP_SRC" = "/usr/local/bin" ]
}

@test "vars: ZMBKP_CONF is /etc/zmbackup" {
  [ "$ZMBKP_CONF" = "/etc/zmbackup" ]
}

@test "vars: ZMBKP_LIB is /usr/local/lib/zmbackup" {
  [ "$ZMBKP_LIB" = "/usr/local/lib/zmbackup" ]
}

@test "vars: ZMBKP_SHARE is /usr/local/share/zmbackup" {
  [ "$ZMBKP_SHARE" = "/usr/local/share/zmbackup" ]
}

# ---------------------------------------------------------------------------
# Zimbra defaults
# ---------------------------------------------------------------------------

@test "vars: OSE_USER is zimbra" {
  [ "$OSE_USER" = "zimbra" ]
}

@test "vars: OSE_INSTALL_DIR is /opt/zimbra" {
  [ "$OSE_INSTALL_DIR" = "/opt/zimbra" ]
}

@test "vars: OSE_DEFAULT_BKP_DIR is /opt/zimbra/backup" {
  [ "$OSE_DEFAULT_BKP_DIR" = "/opt/zimbra/backup" ]
}

# ---------------------------------------------------------------------------
# Backup defaults
# ---------------------------------------------------------------------------

@test "vars: MAX_PARALLEL_PROCESS is 3" {
  [ "$MAX_PARALLEL_PROCESS" = "3" ]
}

@test "vars: ROTATE_TIME is 30" {
  [ "$ROTATE_TIME" = "30" ]
}

@test "vars: LOCK_BACKUP is true" {
  [ "$LOCK_BACKUP" = "true" ]
}

@test "vars: SESSION_TYPE is TXT" {
  [ "$SESSION_TYPE" = "TXT" ]
}

@test "vars: ZMBKP_VERSION contains 1.2" {
  [[ "$ZMBKP_VERSION" == *"1.2"* ]]
}

@test "vars: TERM is set to linux" {
  [ "$TERM" = "linux" ]
}

@test "vars: OLE_TANGE repository URL is defined" {
  [ -n "$OLE_TANGE" ]
  [[ "$OLE_TANGE" == *"tange"* ]]
}
