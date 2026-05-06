#!/usr/bin/env bash

set -euo pipefail

app_path="${1:?expected springboot launcher path}"
workspace_root="${TEST_SRCDIR}/${TEST_WORKSPACE}"
env_script="${workspace_root}/src/bpmner_app_bazelrun_env.sh"
sample_path="${workspace_root}/toast-process.txt"

assert_contains() {
  local haystack="$1"
  local needle="$2"

  if [[ "${haystack}" != *"${needle}"* ]]; then
    echo "Expected to find '${needle}'" >&2
    exit 1
  fi
}

if [[ ! -f "${workspace_root}/${app_path}" ]]; then
  echo "Expected ${workspace_root}/${app_path} to exist" >&2
  exit 1
fi

if [[ ! -f "${env_script}" ]]; then
  echo "Expected ${env_script} to exist" >&2
  exit 1
fi

if [[ ! -f "${sample_path}" ]]; then
  echo "Expected ${sample_path} to exist" >&2
  exit 1
fi

env_contents="$(cat "${env_script}")"
assert_contains "${env_contents}" 'DATAFILES="toast-process.txt '
