#!/usr/bin/env bash

# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

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
