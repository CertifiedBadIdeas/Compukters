            import kotlin.collections.*
            class Cell(var x: Int, var y: Int)
            fun main() {
                val data = ArrayList<Cell>(1024)
                var index = 0
                while (index < 1024) {
                    data.add(Cell(1000 + index, 1001 + index))
                    index += 1
                }
                val pool = ArrayList<Cell>(512)
index = 0
while (index < 512) { pool.add(Cell(0, 0)); index += 1 }
val result = ArrayList<Cell>(512)
                println("ready")
                var round = 0
                var checksum = 0
                while (round < 100) {
                    result.clear()
var position = 0
data.mapNotNullTo(result) { cell ->
    if ((cell.x - 1000) % 2 == 0) {
        val mapped = pool[position]
        position += 1
        mapped.x = cell.x + round + 1
        mapped.y = cell.y
        mapped
    } else null
}
                    require(result.size == 512)
                    require(result[0].x == 1001 + round)
                    index = 0
                    while (index < result.size) {
                        checksum += result[index].x + result[index].y
                        index += 1
                    }
                    round += 1
                }
                require(result[0] !== data[0])
                require(result[0] === pool[0] && result[511] === pool[511])
                index = 0
                while (index < data.size) {
                    checksum += data[index].x + data[index].y
                    index += 1
                }
                println(checksum)
            }