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

package ru.lazyhat.compukters.impl.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdeOpeningHandoffFailureTest {
    @Test
    fun `failed session creation reports failure without suspending the terminal and permits retry`() {
        val failure = IllegalStateException("project filesystem unavailable")
        val events = mutableListOf<String>()
        assertFalse(
            IdeOpeningHandoff.attempt(reportFailure = {
                assertSame(failure, it)
                events += "reported"
            }) {
                IdeOpeningHandoff.open<String, String>(
                    createSession = { throw failure },
                    attachTarget = { events += "attach" },
                    suspendParent = {
                        events += "suspend"
                        "parent"
                    },
                    installScreen = { _, _ -> events += "install" },
                    closeSession = { events += "close" },
                    resumeParent = { events += "resume" },
                )
            },
        )
        assertEquals(listOf("reported"), events)
        assertTrue(IdeOpeningHandoff.attempt(reportFailure = { error("unexpected retry failure") }) { events += "retry" })
        assertEquals(listOf("reported", "retry"), events)
    }

    @Test
    fun `target and screen failures release the session and resume a suspended terminal`() {
        for (stage in listOf("attach", "suspend", "install")) {
            val events = mutableListOf<String>()

            fun step(name: String) {
                events += name
                if (stage == name) error("failure at $stage")
            }
            assertFalse(
                IdeOpeningHandoff.attempt(reportFailure = { events += "reported" }) {
                    IdeOpeningHandoff.open(
                        createSession = {
                            events += "open"
                            "session"
                        },
                        attachTarget = { step("attach") },
                        suspendParent = {
                            step("suspend")
                            "parent"
                        },
                        installScreen = { _, _ -> step("install") },
                        closeSession = { events += "close" },
                        resumeParent = { events += "resume" },
                    )
                },
            )
            val expected =
                when (stage) {
                    "attach" -> listOf("open", "attach", "close", "reported")
                    "suspend" -> listOf("open", "attach", "suspend", "close", "resume", "reported")
                    else -> listOf("open", "attach", "suspend", "install", "close", "resume", "reported")
                }
            assertEquals(expected, events)
        }
    }
}
