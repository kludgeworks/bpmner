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
        external = [],
        **kwargs):
    """Bundle a TS test file with esbuild and run it with node:test.

    Args:
        name: The name of the test target.
        entry_point: The entry point for the test.
        srcs: The source files for the test.
        deps: The dependencies for the test.
        loaders: Optional loaders for esbuild.
        external: Extra module names to leave unbundled (resolved from
            node_modules at runtime). When non-empty, `deps` are added to the
            test's runfiles so the externalised packages resolve at run time.
        **kwargs: Additional arguments for js_test.
    """
    bundle_name = name + "_bundle"
    bundle_output = "dist/test/%s.js" % name

    # Use inline source maps so that Node.js's --experimental-test-coverage can
    # map V8 coverage ranges back to original TypeScript source file paths.
    # Without source maps the LCOV paths point to the bundled JS file only and
    # SonarCloud sees 0% coverage on the TypeScript sources.
    config = {"sourcemap": "inline"}
    if loaders:
        config["loader"] = loaders

    esbuild(
        name = bundle_name,
        srcs = srcs,
        config = config,
        entry_point = entry_point,
        external = ["node:test", "node:assert"] + external,
        format = "cjs",
        output = bundle_output,
        platform = "node",
        target = "es2022",
        deps = deps,
    )

    data = [
        ":" + bundle_name,
        "//tools/js:test_wrapper",
    ]

    # Externalised packages are not in the bundle, so they must be present in
    # the runfiles for Node to resolve them from node_modules at runtime.
    if external:
        data = data + deps

    js_test(
        name = name,
        args = ["$(rootpaths :%s)" % bundle_name],
        data = data,
        entry_point = "//tools/js:test_wrapper",
        **kwargs
    )
