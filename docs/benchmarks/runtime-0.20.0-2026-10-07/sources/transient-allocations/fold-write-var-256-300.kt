            import kotlin.collections.*
            class Bias(var value: Int)
            fun main() {
                val storage = ArrayList<Int>(256)
                var index = 0
                while (index < 256) { storage.add(1000 + index); index += 1 }
                val data: List<Int> = storage
                var bias = 0
                println("ready")
                var checksum = 0
                var round = 0
                while (round < 300) {
                    bias = 1
val operation: (Int, Int) -> Int = { sum, item -> sum + item + bias }
checksum += data.fold(0, operation)
bias = 2
require(operation(0, 0) == 2)
                    round += 1
                }
                index = 0
                while (index < data.size) { checksum += data[index]; index += 1 }
                println(checksum)
            }