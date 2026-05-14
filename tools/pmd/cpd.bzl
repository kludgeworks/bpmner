def _cpd_test_impl(ctx):
    pmd_cli = ctx.executable._pmd_cli
    
    # Create a file containing the list of source files to check
    sources_file = ctx.actions.declare_file(ctx.label.name + ".sources")
    ctx.actions.write(
        output = sources_file,
        content = "\n".join([f.path for f in ctx.files.srcs]),
    )
    
    executable = ctx.actions.declare_file(ctx.label.name + ".sh")
    
    # Construct the CPD command
    # PMD 6.x CPD CLI: cpd --minimum-tokens 100 --language kotlin --files <file> --format text
    # We use --filelist to avoid command line length limits
    cmd = [
        ctx.executable._pmd_cli.short_path,
        "cpd",
        "--minimum-tokens",
        str(ctx.attr.minimum_tokens),
        "--language",
        ctx.attr.language,
        "--encoding",
        ctx.attr.encoding,
        "--format",
        ctx.attr.format,
        "--failOnViolation",
        "true" if ctx.attr.fail_on_violation else "false",
        "--filelist",
        sources_file.short_path,
    ]
    
    ctx.actions.write(
        output = executable,
        content = "#!/bin/bash\nexec " + " ".join(cmd) + " \"$@\"",
        is_executable = True,
    )
    
    return [
        DefaultInfo(
            executable = executable,
            runfiles = ctx.runfiles(
                files = ctx.files.srcs + [sources_file],
                transitive_files = ctx.attr._pmd_cli[DefaultInfo].files,
            ).merge(ctx.attr._pmd_cli[DefaultInfo].default_runfiles),
        ),
    ]

cpd_test = rule(
    implementation = _cpd_test_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = True,
            mandatory = True,
        ),
        "language": attr.string(
            mandatory = True,
            doc = "The language to use (e.g., kotlin, typescript)",
        ),
        "minimum_tokens": attr.integer(
            default = 100,
        ),
        "encoding": attr.string(
            default = "UTF-8",
        ),
        "format": attr.string(
            default = "text",
        ),
        "fail_on_violation": attr.bool(
            default = True,
        ),
        "_pmd_cli": attr.label(
            default = Label("@net_sourceforge_pmd//:pmd"),
            executable = True,
            cfg = "exec",
        ),
    },
    test = True,
)
