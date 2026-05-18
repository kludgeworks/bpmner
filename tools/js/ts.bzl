# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

"""Shared TypeScript project macro for BPMNer packages."""

load("@aspect_rules_ts//ts:defs.bzl", "ts_config", "ts_project")

def bpmner_ts_configs():
    """Declares the standard tsconfig/tsconfig_bazel pair for a TS package."""
    ts_config(name = "tsconfig", src = "tsconfig.json")
    ts_config(name = "tsconfig_bazel", src = "tsconfig.bazel.json", deps = [":tsconfig"])

def bpmner_ts_project(name, srcs, deps, tsconfig, assets = [], out_dir = "tsc", transpiler = "tsc", **kwargs):
    """Wraps ts_project with BPMNer defaults.

    `tsconfig` is required: pass `:tsconfig_bazel` after invoking
    `bpmner_ts_configs()` in the same BUILD.bazel, or pass an explicit
    ts_config target. There is no default because the old fallback
    (`//linter:tsconfig_bazel`) was wrong for non-linter packages.

    Args:
      name: name of the resulting ts_project target.
      srcs: TS source files.
      deps: npm/ts_project deps.
      tsconfig: ts_config target (e.g. `:tsconfig_bazel`).
      assets: non-TS files to copy into the output directory.
      out_dir: output directory for the compiled JS.
      transpiler: transpiler to use (default `tsc`).
      **kwargs: additional arguments forwarded to ts_project.
    """
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
