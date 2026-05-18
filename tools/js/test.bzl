# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

"""Shared TypeScript test macros for BPMNer packages."""

load("@aspect_rules_esbuild//esbuild:defs.bzl", "esbuild")
load("@aspect_rules_js//js:defs.bzl", "js_test")

def bpmner_node_test(
        name,
        entry_point,
        srcs,
        deps,
        loaders = {},
        **kwargs):
    """Bundle a TS test file with esbuild and run it with node:test.

    Args:
      name: name of the resulting js_test target.
      entry_point: TS file containing the node:test entrypoint.
      srcs: source files (TS + assets) included in the esbuild bundle.
      deps: npm/ts_project deps for the bundle.
      loaders: optional esbuild loader map (e.g. {".bpmn": "text"}).
      **kwargs: additional arguments forwarded to js_test.
    """
    bundle_name = name + "_bundle"
    bundle_output = "dist/test/%s.js" % name

    config = {"sourcemap": False}
    if loaders:
        config["loader"] = loaders

    esbuild(
        name = bundle_name,
        srcs = srcs,
        config = config,
        entry_point = entry_point,
        external = ["node:test", "node:assert"],
        format = "cjs",
        output = bundle_output,
        platform = "node",
        target = "es2022",
        deps = deps,
    )

    js_test(
        name = name,
        data = [":" + bundle_name],
        entry_point = bundle_output,
        node_options = ["--test-reporter=spec"],
        **kwargs
    )
