import "stdio.ck" { Stdio, fromArgument, println };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val path: String = process::currentDirectory()
    if (path == "") {
        println(ctx, "/")
    } else {
        println(ctx, "/" + path)
    }
}
