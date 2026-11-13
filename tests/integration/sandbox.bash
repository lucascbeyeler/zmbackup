#!/usr/bin/env bash

IT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${IT_DIR}/../.." && pwd)"

sandbox_available() {
  command -v bwrap > /dev/null 2>&1 &&
    bwrap --unshare-user --uid 0 --gid 0 --ro-bind / / --dev /dev true > /dev/null 2>&1
}

sandbox_write_shims() {
  local shims="$1"
  mkdir -p "$shims"

  cat > "$shims/sudo" <<'SHIM'
#!/bin/bash
while [[ $# -gt 0 ]]; do
  case "$1" in
    -H) shift;;
    -u) shift 2;;
    *) exec "$@";;
  esac
done
SHIM

  cat > "$shims/chown" <<'SHIM'
#!/bin/bash
ARGS=()
for ARG in "$@"; do
  [[ $ARG == -* ]] || ARGS+=("$ARG")
done
OWNER="${ARGS[0]%%[.:]*}"
for TARGET in "${ARGS[@]:1}"; do
  echo "$OWNER $TARGET" >> /tmp/ownership.log
done
SHIM

  cat > "$shims/install" <<'SHIM'
#!/bin/bash
OWNER=""
ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) OWNER="$2"; shift 2;;
    *) ARGS+=("$1"); shift;;
  esac
done
/usr/bin/install "${ARGS[@]}" || exit $?
DEST="${ARGS[-1]}"
[[ -d "$DEST" ]] && DEST="$DEST/$(basename "${ARGS[-2]}")"
echo "$OWNER $DEST" >> /tmp/ownership.log
SHIM

  printf '#!/bin/bash\necho zimbra.it.test\n' > "$shims/hostname"
  printf '#!/bin/bash\nexit 0\n' > "$shims/apt"
  printf '#!/bin/bash\n[[ ! -f /tmp/no-cron ]]\n' > "$shims/pgrep"
  chmod +x "$shims"/*
}

sandbox_write_fake_zimbra_tools() {
  local bin="$1"
  mkdir -p "$bin"
  cat > "$bin/zmprov" <<'SHIM'
#!/bin/bash
case "$*" in
  gad) echo it.test;;
  "-l gaa")
    echo admin@it.test
    echo galsync.abc@it.test
    echo alice@it.test
    ;;
esac
SHIM
  printf '#!/bin/bash\necho "zimbra_ldap_password = ldap-from-localconfig"\n' > "$bin/zmlocalconfig"
  chmod +x "$bin"/*
}

sandbox_prepare() {
  local root="$1"
  local jar="$REPO_ROOT/app/build/libs/zmbackup.jar"
  [[ -f "$jar" ]] || return 1
  mkdir -p "$root/stage"
  tar -C "$REPO_ROOT" \
    --exclude='./.git' --exclude='./.gradle' --exclude='build' --exclude='./node_modules' \
    -cf - . | tar -C "$root/stage" -xf -
  mkdir -p "$root/stage/app/build/libs"
  cp "$jar" "$root/stage/app/build/libs/zmbackup.jar"
  printf '#!/bin/bash\nexit 0\n' > "$root/stage/gradlew"
  chmod +x "$root/stage/gradlew"
  sandbox_write_shims "$root/shims"
  sandbox_write_fake_zimbra_tools "$root/zimbra-bin"
}

sandbox_exec() {
  local root="$1"
  local script="$2"
  local args=(
    --unshare-user --uid 0 --gid 0 --unshare-pid --unshare-net --die-with-parent
    --ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/sbin /sbin
    --symlink usr/lib /lib --symlink usr/lib64 /lib64
    --proc /proc --dev /dev --tmpfs /tmp --tmpfs /root --tmpfs /var --tmpfs /run --tmpfs /srv
    --tmpfs /etc --tmpfs /usr/local --dir /usr/local/bin --tmpfs /opt
  )
  local entry
  for entry in /etc/*; do
    case "$(basename "$entry")" in
      cron.d|zmbackup) ;;
      *) args+=(--ro-bind-try "$entry" "$entry");;
    esac
  done
  args+=(
    --dir /etc/cron.d
    --bind "$root/stage" /src
    --ro-bind "$root/shims" /shims
    --ro-bind "$root/zimbra-bin" /fake-zimbra-bin
    --chdir /src
    --setenv PATH "/shims:/usr/local/bin:/usr/bin:/usr/lib/jvm/default/bin"
    --setenv HOME /root
  )
  bwrap "${args[@]}" /bin/bash -c "$script" < /dev/null
}
