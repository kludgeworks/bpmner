# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

"""Bazel rules for generating SpotBugs reports importable by SonarCloud."""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def _spotbugs_sonar_report_impl(ctx):
    target_java = ctx.attr.target[JavaInfo]
    target_jar = target_java.outputs.jars[0].class_jar
    runtime_jars = target_java.transitive_runtime_jars.to_list()
    auxclasspath_file = ctx.actions.declare_file(ctx.label.name + ".auxclasspath")
    xml = ctx.actions.declare_file(ctx.attr.xml_report)
    sonar_json = ctx.actions.declare_file(ctx.attr.sonar_report)

    ctx.actions.write(
        output = auxclasspath_file,
        content = "\n".join([jar.path for jar in runtime_jars if jar != target_jar]),
    )

    ctx.actions.run_shell(
        inputs = depset(
            ctx.files.srcs + [target_jar, auxclasspath_file],
            transitive = [target_java.transitive_runtime_jars],
        ),
        outputs = [xml, sonar_json],
        tools = [
            ctx.executable._converter,
            ctx.executable._spotbugs,
        ],
        command = """
set -euo pipefail
{spotbugs} \
  -textui \
  -effort:max \
  -medium \
  -xml:withMessages={xml} \
  -sourcepath . \
  -auxclasspathFromFile {auxclasspath_file} \
  {target_jar}
{converter} \
  --xml {xml} \
  --out {sonar_json}
""".format(
            auxclasspath_file = auxclasspath_file.path,
            converter = ctx.executable._converter.path,
            sonar_json = sonar_json.path,
            spotbugs = ctx.executable._spotbugs.path,
            target_jar = target_jar.path,
            xml = xml.path,
        ),
        mnemonic = "SpotBugsSonarReport",
        progress_message = "Generating SpotBugs Sonar report %{label}",
    )

    return [
        DefaultInfo(files = depset([xml, sonar_json])),
        OutputGroupInfo(
            sonar_external_issues = depset([sonar_json]),
            spotbugs_xml = depset([xml]),
        ),
    ]

spotbugs_sonar_report = rule(
    implementation = _spotbugs_sonar_report_impl,
    attrs = {
        "sonar_report": attr.string(mandatory = True),
        "srcs": attr.label_list(allow_files = True),
        "target": attr.label(
            mandatory = True,
            providers = [JavaInfo],
        ),
        "xml_report": attr.string(mandatory = True),
        "_converter": attr.label(
            default = Label("//tools/spotbugs:spotbugs_sonar_converter"),
            executable = True,
            cfg = "exec",
        ),
        "_spotbugs": attr.label(
            default = Label("//tools/spotbugs:spotbugs_bin"),
            executable = True,
            cfg = "exec",
        ),
    },
)
