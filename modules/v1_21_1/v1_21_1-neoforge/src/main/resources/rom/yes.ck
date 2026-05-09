import "stdio.ck" { Stdio, fromArgument, println };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    var text: String = strings::trim(ctx.argument)
    if (strings::isBlank(text)) {
        text = "y"
    }
    while true {
        println(ctx, text)
        yield()
    }
}
