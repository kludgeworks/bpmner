#!/usr/bin/env bash
# Copyright (c) 2026 The Project Contributors
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

set -euo pipefail

app_path="${1:?expected springboot launcher path}"
: "${TEST_SRCDIR:?TEST_SRCDIR must be set by Bazel}"
: "${TEST_WORKSPACE:?TEST_WORKSPACE must be set by Bazel}"
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
