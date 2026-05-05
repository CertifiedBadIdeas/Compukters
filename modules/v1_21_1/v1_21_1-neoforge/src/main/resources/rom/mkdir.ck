import "stdio.ck" { Stdio, error, fromArgument };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val target: String = strings::trim(ctx.argument)
    if (strings::isBlank(target)) {
        error(ctx, "Usage: mkdir <path>")
        return
    }
    if (!filesystem::makeDir(target)) {
        error(ctx, "mkdir failed: " + target)
    }
}
