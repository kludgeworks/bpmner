# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

"""Shared TypeScript project macro for BPMNer packages."""

load("@aspect_rules_ts//ts:defs.bzl", "ts_config", "ts_project")

def bpmner_ts_configs():
    """Declares the standard tsconfig/tsconfig_bazel pair for a TS package."""
    ts_config(name = "tsconfig", src = "tsconfig.json")
    ts_config(name = "tsconfig_bazel", src = "tsconfig.bazel.json", deps = [":tsconfig"])

def bpmner_ts_project(name, srcs, deps, tsconfig, assets = [], out_dir = "tsc", transpiler = "tsc", **kwargs):
    ts_project(
        name = name,
        srcs = srcs,
        assets = assets,
        declaration = True,
        out_dir = out_dir,
        transpiler = transpiler,
        tsconfig = tsconfig,
        deps = deps,
        **kwargs
    )
