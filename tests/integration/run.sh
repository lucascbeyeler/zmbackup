#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/../.." || exit 2
source tests/integration/sandbox.bash

FAILED=0

./gradlew :app:integrationTest --console=plain || FAILED=1

if sandbox_available; then
  ./gradlew :app:shadowJar -q || FAILED=1
  [[ -x node_modules/.bin/bats ]] || npm ci --silent || FAILED=1
  node_modules/.bin/bats tests/integration/installer.bats || FAILED=1
else
  echo "FAIL: installer.bats needs bubblewrap with unprivileged user namespaces, so none of its tests ran." >&2
  FAILED=1
fi

exit "$FAILED"
