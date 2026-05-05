import "stdio.ck" { Stdio, fromArgument, println };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val target: String = strings::trim(ctx.argument)
    if (strings::isBlank(target)) {
        println(ctx, filesystem::list())
    } else {
        println(ctx, filesystem::list(target))
    }
}
