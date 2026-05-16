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

jar_path="${1:?expected spring boot jar path}"

manifest="$(unzip -p "${jar_path}" META-INF/MANIFEST.MF)"
entries="$(jar tf "${jar_path}")"

assert_contains() {
  local haystack="$1"
  local needle="$2"

  if [[ "${haystack}" != *"${needle}"* ]]; then
    echo "Expected to find '${needle}' in ${jar_path}" >&2
    exit 1
  fi
}

assert_contains "${manifest}" "Main-Class: org.springframework.boot.loader.launch.JarLauncher"
assert_contains "${manifest}" "Start-Class: dev.groknull.bpmner.BpmnerApplicationKt"

assert_contains "${entries}" "org/springframework/boot/loader/launch/JarLauncher.class"
assert_contains "${entries}" "org/springframework/boot/loader/launch/Launcher.class"
assert_contains "${entries}" "BOOT-INF/classes/dev/groknull/bpmner/BpmnerApplication.class"
