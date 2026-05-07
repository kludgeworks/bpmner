load("@aspect_rules_ts//ts:defs.bzl", "ts_project")

COMMON_TS_DEPS = [
    ":node_modules/@types/node",
    ":node_modules/bpmn-moddle",
    ":node_modules/bpmnlint",
    ":node_modules/bpmnlint-utils",
    ":node_modules/wink-eng-lite-web-model",
    ":node_modules/wink-nlp",
]

def linter_ts_project(name, srcs, assets = [], out_dir = "tsc", deps = [], tsconfig = ":tsconfig_bazel", **kwargs):
    ts_project(
        name = name,
        srcs = srcs,
        assets = assets,
        declaration = True,
        out_dir = out_dir,
        transpiler = "tsc",
        tsconfig = tsconfig,
        deps = COMMON_TS_DEPS + deps,
        **kwargs
    )
