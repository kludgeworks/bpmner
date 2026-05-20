# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

"""Test macros for the bpmner project."""

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
        name: The name of the test target.
        entry_point: The entry point for the test.
        srcs: The source files for the test.
        deps: The dependencies for the test.
        loaders: Optional loaders for esbuild.
        **kwargs: Additional arguments for js_test.
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
        node_options = ["--test-reporter=spec", "--experimental-test-coverage"],
        **kwargs
    )
