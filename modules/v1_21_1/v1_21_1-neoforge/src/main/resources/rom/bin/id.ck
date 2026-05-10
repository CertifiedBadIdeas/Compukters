import "../stdio.ck" { Stdio, fromArgument, println };

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    println(ctx, "" + system::deviceId())
}
