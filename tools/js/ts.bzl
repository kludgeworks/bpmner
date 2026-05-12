"""Shared TypeScript project macro for BPMNer packages."""

load("@aspect_rules_ts//ts:defs.bzl", "ts_project")

def bpmner_ts_project(name, srcs, deps, assets = [], out_dir = "tsc", tsconfig = "//linter:tsconfig_bazel", **kwargs):
    ts_project(
        name = name,
        srcs = srcs,
        assets = assets,
        declaration = True,
        out_dir = out_dir,
        transpiler = "tsc",
        tsconfig = tsconfig,
        deps = deps,
        **kwargs
    )
