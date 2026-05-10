import "../stdio.ck" { Stdio, error, fromArgument };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val target: String = strings::trim(ctx.argument)
    if (strings::isBlank(target)) {
        error(ctx, "Usage: rmdir <path>")
        return
    }
    if (!filesystem::remove(target)) {
        error(ctx, "rmdir failed: " + target)
    }
}
