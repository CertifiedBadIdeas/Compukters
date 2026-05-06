pub struct Stdio { input: Int, output: Int, error: Int, argument: String }

pub fun fromArgument(raw: String): Stdio {
    val tag: String = strings::beforeSpace(raw)
    if (tag != "stdio-v1") {
        val closed: Int = 0 - 1
        return Stdio(input = closed, output = closed, error = closed, argument = raw)
    }
    val rest1: String = strings::afterSpace(raw)
    val inputText: String = strings::beforeSpace(rest1)
    val rest2: String = strings::afterSpace(rest1)
    val outputText: String = strings::beforeSpace(rest2)
    val rest3: String = strings::afterSpace(rest2)
    val errorText: String = strings::beforeSpace(rest3)
    val userArgument: String = strings::afterSpace(rest3)
    return Stdio(input = strings::toInt(inputText), output = strings::toInt(outputText), error = strings::toInt(errorText), argument = userArgument)
}

pub fun encode(ctx: Stdio, argument: String): String {
    return "stdio-v1 " + ctx.input + " " + ctx.output + " " + ctx.error + " " + argument
}

pub fun write(ctx: Stdio, text: String) {
    if (ctx.output >= 0) {
        ipc::write(ctx.output, text)
    }
}

pub fun println(ctx: Stdio, text: String) {
    write(ctx, text + "\n")
}

pub fun error(ctx: Stdio, text: String) {
    if (ctx.error >= 0) {
        ipc::write(ctx.error, text + "\n")
    }
}

fun stripLineDelimiter(text: String): String {
    val length: Int = strings::length(text)
    if (length <= 0) {
        return text
    }
    if (strings::charAt(text, length - 1) != "\n") {
        return text
    }
    var result: String = ""
    var i: Int = 0
    while i + 1 < length + 0 {
        result = result + strings::charAt(text, i)
        i = i + 1
    }
    return result
}

pub fun readLine(ctx: Stdio): String {
    if (ctx.input < 0) {
        return ""
    }
    return stripLineDelimiter(ipc::read(ctx.input))
}

pub fun main() {
    return
}