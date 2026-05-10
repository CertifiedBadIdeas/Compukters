import "../stdio.ck" { Stdio, error, fromArgument };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val target: String = strings::trim(ctx.argument)
    if (strings::isBlank(target)) {
        error(ctx, "Usage: touch <path>")
        return
    }
    if (filesystem::isDirectory(target)) {
        error(ctx, "touch: " + target + " is a directory")
        return
    }
    var text: String = ""
    if (filesystem::exists(target)) {
        text = filesystem::readText(target)
    }
    filesystem::writeText(target, text)
}
