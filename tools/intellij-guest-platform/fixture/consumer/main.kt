/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import kotlin.math.Vector3d
import prototype.flight.Velocity

fun main() {
    val velocity = Velocity(3.0, 4.0, 0.0)
    val direction = velocity.direction()
    check(direction.x == 0.6 && direction.y == 0.8 && direction.z == 0.0)
    val next = velocity.advance(Vector3d(1.0, 2.0, 3.0), 2.0)
    check(next.x == 7.0 && next.y == 10.0 && next.z == 3.0)
    println("GUEST_LIBRARY_OK")
}
