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

package compukter.network

/** The second computer on this computer's cable. No addresses or peripheral binding are needed. */
public object Network {
    public val connected: Boolean get() = NetworkBindings.connected()

    /** Copies one message of at most 4096 bytes into the peer's bounded inbox. Throws IOException on failure. */
    public fun send(message: ByteArray) { NetworkBindings.send(message) }

    /** Waits for one message without blocking the server; throws IOException if the connection is interrupted. */
    public fun receive(): ByteArray = NetworkBindings.receive()
}

private object NetworkBindings {
    external fun connected(): Boolean
    external fun send(message: ByteArray)
    external fun receive(): ByteArray
}
