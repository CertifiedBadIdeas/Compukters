import kotlin.collections.*
class Bias(var value: Int)
fun main() {
    val storage = ArrayList<Int>(256)
    var index = 0
    while (index < 256) { storage.add(1000 + index); index += 1 }
    val data: List<Int> = storage
    val bias = Bias(1)
    println("ready")
    var checksum = 0
    var round = 0
    while (round < 300) {
        checksum += data.fold(0) { sum, item -> sum + item + bias.value }
        round += 1
    }
    index = 0
    while (index < data.size) { checksum += data[index]; index += 1 }
    println(checksum)
}