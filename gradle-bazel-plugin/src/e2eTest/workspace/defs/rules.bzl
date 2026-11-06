"""Minimal rules for the e2e suite: a tree artifact and an executable with runfiles."""

def _tree_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    ctx.actions.run_shell(
        inputs = ctx.files.srcs,
        outputs = [out],
        command = "mkdir -p {o}/sub && cp {srcs} {o}/ && echo nested > {o}/sub/nested.txt".format(
            o = out.path,
            srcs = " ".join([f.path for f in ctx.files.srcs]),
        ),
    )
    return [DefaultInfo(files = depset([out]))]

tree = rule(implementation = _tree_impl, attrs = {"srcs": attr.label_list(allow_files = True)})

def _tool_impl(ctx):
    exe = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(
        output = exe,
        content = "#!/bin/sh\ncat \"$0.runfiles/_main/{}\"\n".format(ctx.file.data.short_path),
        is_executable = True,
    )
    return [DefaultInfo(executable = exe, runfiles = ctx.runfiles(files = [ctx.file.data]))]

tool = rule(implementation = _tool_impl, executable = True, attrs = {"data": attr.label(allow_single_file = True)})
