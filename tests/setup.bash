#!/usr/bin/env bash
# Common test helpers loaded by all test files

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${TESTS_DIR}/.." && pwd)"
MOCKS_DIR="${TESTS_DIR}/mocks"

setup_mock_path() {
  PATH="${MOCKS_DIR}:${PATH}"
  export PATH
}
