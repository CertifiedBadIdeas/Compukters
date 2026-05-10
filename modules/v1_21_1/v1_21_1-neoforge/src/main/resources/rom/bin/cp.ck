import "../stdio.ck" { Stdio, error, fromArgument };

fun firstArg(text: String): String {
    return strings::beforeSpace(strings::trim(text))
}

fun secondArg(text: String): String {
    return strings::beforeSpace(strings::afterSpace(strings::trim(text)))
}

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val source: String = firstArg(ctx.argument)
    val target: String = secondArg(ctx.argument)
    if (strings::isBlank(source) || strings::isBlank(target)) {
        error(ctx, "Usage: cp <source> <target>")
        return
    }
    if (!filesystem::exists(source)) {
        error(ctx, "cp: source not found: " + source)
        return
    }
    if (filesystem::isDirectory(source)) {
        error(ctx, "cp: source is a directory: " + source)
        return
    }
    if (filesystem::isDirectory(target)) {
        error(ctx, "cp: target is a directory: " + target)
        return
    }
    filesystem::writeText(target, filesystem::readText(source))
}
