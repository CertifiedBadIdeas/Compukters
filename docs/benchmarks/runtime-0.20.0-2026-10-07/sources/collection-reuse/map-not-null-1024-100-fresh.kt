            import kotlin.collections.*
            class Cell(var x: Int, var y: Int)
            fun main() {
                val data = ArrayList<Cell>(1024)
                var index = 0
                while (index < 1024) {
                    data.add(Cell(1000 + index, 1001 + index))
                    index += 1
                }
                var result: List<Cell> = emptyList<Cell>()
                println("ready")
                var round = 0
                var checksum = 0
                while (round < 100) {
                    result = data.mapNotNull { cell ->
    if ((cell.x - 1000) % 2 == 0) Cell(cell.x + round + 1, cell.y) else null
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

                index = 0
                while (index < data.size) {
                    checksum += data[index].x + data[index].y
                    index += 1
                }
                println(checksum)
            }