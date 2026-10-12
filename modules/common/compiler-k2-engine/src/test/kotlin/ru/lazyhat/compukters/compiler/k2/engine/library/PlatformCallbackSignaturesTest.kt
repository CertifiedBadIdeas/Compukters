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

package ru.lazyhat.compukters.compiler.k2.engine.library

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCallbackSignaturesTest {
    @Test
    fun `nullable extension callbacks retain receiver as the leading function parameter`() {
        assertEquals("fun(Int,Function1<Builder,Unit>?):Result", canonicalCallbackSignature("fun(Int,(Builder.()->Unit)?):Result"))
        assertEquals(
            "fun(Function3<Receiver,Int,String,Boolean>):Unit",
            canonicalCallbackSignature("fun(Receiver.(Int,String)->Boolean):Unit"),
        )
    }

    @Test
    fun `nested callbacks generic commas and nullable returns are distinguished`() {
        assertEquals(
            "fun(Function1<Function1<Int,Unit>,Function1<String,Boolean?>>):List<Function0<Unit>?>",
            canonicalCallbackSignature("fun(((Int)->Unit)->((String)->Boolean?)):List<(() -> Unit)?>".replace(" ", "")),
        )
        assertEquals("fun(Int):Function0<String?>", canonicalCallbackSignature("fun(Int):()->String?"))
        assertEquals("fun(Vector3d.Vector3d):Vector3d?", canonicalCallbackSignature("fun(Vector3d.Vector3d):Vector3d?"))
    }
}
