pub struct Stdio { input: Int, output: Int, error: Int, argument: String }

pub fun fromArgument(raw: String): Stdio {
    val inputText: String = strings::beforeSpace(raw)
    val rest1: String = strings::afterSpace(raw)
    val outputText: String = strings::beforeSpace(rest1)
    val rest2: String = strings::afterSpace(rest1)
    val errorText: String = strings::beforeSpace(rest2)
    val userArgument: String = strings::afterSpace(rest2)
    return Stdio(input = strings::toInt(inputText), output = strings::toInt(outputText), error = strings::toInt(errorText), argument = userArgument)
}

pub fun encode(ctx: Stdio, argument: String): String {
    return ctx.input + " " + ctx.output + " " + ctx.error + " " + argument
}

pub fun write(ctx: Stdio, text: String) {
    ipc::write(ctx.output, text)
}

pub fun println(ctx: Stdio, text: String) {
    ipc::write(ctx.output, text + "\n")
}

pub fun error(ctx: Stdio, text: String) {
    ipc::write(ctx.error, text + "\n")
}

pub fun readLine(ctx: Stdio): String {
    return ipc::read(ctx.input)
}

pub fun main() {
    return
}