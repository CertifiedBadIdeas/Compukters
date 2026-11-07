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

package fixture.builder

import ru.lazyhat.compukters.compiler.k2.engine.build.buildTrustedAddonGuestApi

/** Arbitrary Guest code is intentional in compiler conformance fixtures only. */
fun main(args: Array<String>) {
    require(args.size % 2 == 0)
    buildTrustedAddonGuestApi(args.toList().windowed(2, 2).associate { (name, value) -> name to value })
}
