#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/../.." || exit 2

FAILED=0

./gradlew :app:integrationTest --console=plain || FAILED=1

./gradlew :app:shadowJar -q || FAILED=1
[[ -x node_modules/.bin/bats ]] || npm ci --silent || FAILED=1
node_modules/.bin/bats tests/integration/installer.bats || FAILED=1

exit "$FAILED"
