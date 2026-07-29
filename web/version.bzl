# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

"""Bakes the release version into a static JSON resource served at /version.json.

`native.module_version()` reads MODULE.bazel's `module(version = ...)`, which
release-please's `bazel` updater keeps current — so bumping the release version
is the only edit needed to change the served/displayed value.
"""

load("@bazel_skylib//rules:write_file.bzl", "write_file")

def version_json(name, out):
    write_file(
        name = name,
        out = out,
        content = ['{"version": "%s"}' % native.module_version()],
    )

def bpmner_module_version(name = None):
    """The release version from MODULE.bazel's module(version = ...).

    Args:
        name: unused; present only so buildifier recognizes this as an
            intentional public helper rather than flagging it as an
            unnamed macro (it calls `native.module_version()`).
    """
    return native.module_version()
