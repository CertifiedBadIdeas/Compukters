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

package ru.lazyhat.compukters.ide.client.target

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class IdeTargetCoordinator(
    private val port: IdeTargetPort,
    private val clock: IdeControllerClock,
    limits: IdeClientLimits = IdeClientLimits(),
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val events = ArrayBlockingQueue<TargetEvent>(limits.eventQueueCapacity)
    private val overflow = AtomicBoolean()
    private var generation = 0L
    private var nextOperationId = 1L
    private var current: IdeTargetState = IdeTargetState.LocalOnly
    private var attached: IdeAttachedTarget? = null
    private var heartbeatPending = false
    private var lastHeartbeatMillis = 0L
    private var closed = false

    fun state(): IdeTargetState {
        checkOwner()
        return current
    }

    fun attach(claim: IdeTargetClaim) {
        checkActive()
        releaseAttached()
        val eventGeneration = advanceGeneration()
        val operationId = nextOperationId++
        current = IdeTargetState.Attaching(operationId)
        try {
            port.attach(claim).whenComplete { result, failure ->
                enqueue(TargetEvent.Attach(eventGeneration, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Attach(eventGeneration, null, failure))
        }
    }

    fun detach() {
        checkOwner()
        if (closed) return
        advanceGeneration()
        releaseAttached()
        current = IdeTargetState.LocalOnly
    }

    fun tick() {
        checkActive()
        if (overflow.compareAndSet(true, false)) {
            val target = attached
            advanceGeneration()
            releaseAttached()
            current =
                if (target == null) {
                    IdeTargetState.Failed(null, protocolFailure())
                } else {
                    IdeTargetState.Detached(protocolFailure())
                }
            events.clear()
            return
        }
        while (true) accept(events.poll() ?: break)
        requestHeartbeatIfDue()
    }

    override fun close() {
        checkOwner()
        if (closed) return
        advanceGeneration()
        releaseAttached()
        events.clear()
        current = IdeTargetState.LocalOnly
        closed = true
    }

    private fun accept(event: TargetEvent) {
        if (event.generation != generation) {
            if (event is TargetEvent.Attach) {
                val target = (event.result as? IdeAttachResult.Attached)?.target
                if (target != null) runCatching { port.detach(target) }
            }
            return
        }
        when (event) {
            is TargetEvent.Attach -> acceptAttach(event)
            is TargetEvent.Heartbeat -> acceptHeartbeat(event)
        }
    }

    private fun acceptAttach(event: TargetEvent.Attach) {
        val result = event.result
        if (event.failure != null || result == null) {
            current = IdeTargetState.Failed(null, operationFailure())
            return
        }
        when (result) {
            is IdeAttachResult.Attached -> {
                attached = result.target
                lastHeartbeatMillis = clock.nowMillis().coerceAtLeast(0)
                current = IdeTargetState.Attached(result.target)
            }
            is IdeAttachResult.Rejected -> current = IdeTargetState.Failed(null, result.failure)
        }
    }

    private fun acceptHeartbeat(event: TargetEvent.Heartbeat) {
        heartbeatPending = false
        val target = attached ?: return
        val result = event.result
        if (event.failure != null || result == null) {
            loseTarget(operationFailure())
            return
        }
        when (result) {
            IdeHeartbeatResult.Alive -> lastHeartbeatMillis = clock.nowMillis().coerceAtLeast(0)
            is IdeHeartbeatResult.Lost -> loseTarget(result.failure)
        }
        if (attached != target) heartbeatPending = false
    }

    private fun requestHeartbeatIfDue() {
        val target = attached ?: return
        if (heartbeatPending) return
        val now = clock.nowMillis().coerceAtLeast(0)
        if (now - lastHeartbeatMillis < HEARTBEAT_INTERVAL_MILLIS) return
        heartbeatPending = true
        val eventGeneration = generation
        try {
            port.heartbeat(target).whenComplete { result, failure ->
                enqueue(TargetEvent.Heartbeat(eventGeneration, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Heartbeat(eventGeneration, null, failure))
        }
    }

    private fun loseTarget(failure: IdeTargetFailure) {
        advanceGeneration()
        releaseAttached()
        current = IdeTargetState.Detached(failure)
    }

    private fun releaseAttached() {
        val target = attached
        attached = null
        heartbeatPending = false
        if (target != null) runCatching { port.detach(target) }
    }

    private fun advanceGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
        return generation
    }

    private fun enqueue(event: TargetEvent) {
        if (!events.offer(event)) overflow.set(true)
    }

    private fun checkActive() {
        checkOwner()
        check(!closed) { "target coordinator is closed" }
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "target coordinator must run on its owner thread" }

    private fun operationFailure(): IdeTargetFailure = IdeTargetFailure(IdeTargetFailureKind.Other, "Target operation failed")

    private fun protocolFailure(): IdeTargetFailure = IdeTargetFailure(IdeTargetFailureKind.Protocol, "Target event queue overflow")

    private sealed interface TargetEvent {
        val generation: Long

        data class Attach(
            override val generation: Long,
            val result: IdeAttachResult?,
            val failure: Throwable?,
        ) : TargetEvent

        data class Heartbeat(
            override val generation: Long,
            val result: IdeHeartbeatResult?,
            val failure: Throwable?,
        ) : TargetEvent
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
    }
}
