import "../stdio.ck" { Stdio, error, fromArgument };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val target: String = strings::trim(ctx.argument)
    if (strings::isBlank(target)) {
        error(ctx, "Usage: rm <path>")
        return
    }
    if (!filesystem::remove(target)) {
        error(ctx, "rm failed: " + target)
    }
}
