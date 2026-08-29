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

package ru.lazyhat.compukters.ide.client.files

import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryEntry
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

interface IdeComputerFileAccess {
    fun stat(path: IdeTargetVirtualPath): CompletableFuture<IdeFileStatResult>

    fun list(
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<IdeFileListResult>

    fun read(
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<IdeFileReadResult> =
        CompletableFuture.completedFuture(
            IdeFileReadResult.Failed(IdeTargetFailure(IdeTargetFailureKind.Unsupported, "Target filesystem is unavailable")),
        )
}

class IdeComputerFileCoordinator(
    private val access: IdeComputerFileAccess,
    eventCapacity: Int = 256,
) {
    private val owner = Thread.currentThread()
    private val events = ArrayBlockingQueue<Event>(eventCapacity)
    private val overflow = AtomicBoolean()
    private var epoch = 0L
    private var target: IdeAttachedTarget? = null
    private var current: IdeComputerTreeState = IdeComputerTreeState.NoTarget
    private var restoreExpanded = emptySet<IdeTargetVirtualPath>()

    init {
        require(eventCapacity > 0) { "computer file event capacity must be positive" }
    }

    fun state(): IdeComputerTreeState {
        checkOwner()
        return current
    }

    fun attach(target: IdeAttachedTarget) {
        checkOwner()
        advanceEpoch()
        this.target = target
        restoreExpanded = setOf(ROOT)
        if (!target.capabilities.readableFileSystem) {
            current = IdeComputerTreeState.Unavailable("Target filesystem is unavailable")
            return
        }
        beginRootLoad()
    }

    fun detach() {
        checkOwner()
        advanceEpoch()
        target = null
        restoreExpanded = emptySet()
        current = IdeComputerTreeState.NoTarget
    }

    fun targetLost(detail: String) {
        checkOwner()
        advanceEpoch()
        target = null
        restoreExpanded = emptySet()
        current = IdeComputerTreeState.TargetLost(detail)
    }

    fun refresh() {
        checkOwner()
        if (target == null) return
        restoreExpanded = (current as? IdeComputerTreeState.Available)?.expanded ?: restoreExpanded
        if (ROOT !in restoreExpanded) restoreExpanded = restoreExpanded + ROOT
        advanceEpoch()
        beginRootLoad()
    }

    fun expand(path: IdeTargetVirtualPath) {
        checkOwner()
        val state = current as? IdeComputerTreeState.Available ?: return
        val directory = find(state.root, path) as? IdeComputerNode.Directory ?: return
        if (directory.children !is IdeComputerChildren.Unloaded) return
        current = state.copy(root = replace(state.root, path) { it.copy(children = IdeComputerChildren.Loading) })
        requestDirectory(path, directory.metadata, epoch, restore = false)
    }

    fun tick() {
        checkOwner()
        if (overflow.getAndSet(false)) {
            advanceEpoch()
            current = IdeComputerTreeState.Unavailable("Target filesystem event queue overflow")
            events.clear()
            return
        }
        while (true) {
            val event = events.poll() ?: break
            if (event.epoch != epoch) continue
            when (event) {
                is Event.RootStat -> acceptRootStat(event)
                is Event.DirectoryPage -> acceptDirectoryPage(event)
            }
        }
    }

    private fun beginRootLoad() {
        current = IdeComputerTreeState.Loading
        val requestEpoch = epoch
        access.stat(ROOT).whenComplete { result, failure -> enqueue(Event.RootStat(requestEpoch, result, failure)) }
    }

    private fun acceptRootStat(event: Event.RootStat) {
        val observed = event.result as? IdeFileStatResult.Observed
        if (event.failure != null || observed == null) {
            fail(event.failure, (event.result as? IdeFileStatResult.Failed)?.failure)
            return
        }
        val metadata = observed.stat.metadata
        if (metadata.kind != IdeTargetFileKind.Directory) {
            failProtocol("Target filesystem root is not a directory")
            return
        }
        requestDirectory(ROOT, metadata, event.epoch, restore = true, expectedFileSystemGeneration = observed.stat.fileSystemGeneration)
    }

    private fun requestDirectory(
        path: IdeTargetVirtualPath,
        metadata: IdeTargetFileMetadata,
        requestEpoch: Long,
        restore: Boolean,
        expectedFileSystemGeneration: Long? = null,
    ) {
        requestPage(
            DirectoryLoad(path, metadata, requestEpoch, restore, expectedFileSystemGeneration, emptyList(), null),
        )
    }

    private fun requestPage(load: DirectoryLoad) {
        access.list(load.path, load.cursor, PAGE_SIZE).whenComplete { result, failure ->
            enqueue(Event.DirectoryPage(load.epoch, load, result, failure))
        }
    }

    private fun acceptDirectoryPage(event: Event.DirectoryPage) {
        val listed = event.result as? IdeFileListResult.Listed
        if (event.failure != null || listed == null) {
            fail(event.failure, (event.result as? IdeFileListResult.Failed)?.failure)
            return
        }
        val listing = listed.listing
        val expectedFileSystemGeneration = event.load.fileSystemGeneration ?: listing.fileSystemGeneration
        if (
            listing.fileSystemGeneration != expectedFileSystemGeneration ||
            listing.directoryGeneration != event.load.metadata.generation
        ) {
            failProtocol("Target directory changed while it was being listed")
            return
        }
        val previous = event.load.cursor
        if (listing.entries.isNotEmpty() && previous != null && compareUtf8(previous, listing.entries.first().name) >= 0) {
            failProtocol("Target directory pages are not strictly ordered")
            return
        }
        if (!listing.complete && listing.entries.isEmpty()) {
            failProtocol("Target returned an empty incomplete directory page")
            return
        }
        val entries = event.load.entries + listing.entries
        if (entries.size > MAXIMUM_DIRECTORY_ENTRIES) {
            failProtocol("Target directory contains too many entries")
            return
        }
        if (!listing.complete) {
            requestPage(
                event.load.copy(
                    fileSystemGeneration = expectedFileSystemGeneration,
                    entries = entries,
                    cursor = listing.entries.last().name,
                ),
            )
            return
        }
        publishDirectory(event.load, entries)
    }

    private fun publishDirectory(load: DirectoryLoad, entries: List<IdeTargetDirectoryEntry>) {
        val nodes = entries.map { entry -> entry.toNode(child(load.path, entry.name)) }
        if (load.path == ROOT) {
            val root = IdeComputerNode.Directory(ROOT, load.metadata, IdeComputerChildren.Loaded(nodes))
            current = IdeComputerTreeState.Available(root, setOf(ROOT))
        } else {
            val state = current as? IdeComputerTreeState.Available ?: return
            if (find(state.root, load.path) !is IdeComputerNode.Directory) return
            current =
                state.copy(
                    root = replace(state.root, load.path) { it.copy(children = IdeComputerChildren.Loaded(nodes)) },
                    expanded = state.expanded + load.path,
                )
        }
        if (load.restore) restoreChildren(load.path)
    }

    private fun restoreChildren(parent: IdeTargetVirtualPath) {
        val state = current as? IdeComputerTreeState.Available ?: return
        val directory = find(state.root, parent) as? IdeComputerNode.Directory ?: return
        val children = (directory.children as? IdeComputerChildren.Loaded)?.nodes.orEmpty()
        children
            .filterIsInstance<IdeComputerNode.Directory>()
            .filter { it.path in restoreExpanded }
            .forEach { child ->
                val latest = current as? IdeComputerTreeState.Available ?: return
                current = latest.copy(root = replace(latest.root, child.path) { it.copy(children = IdeComputerChildren.Loading) })
                requestDirectory(child.path, child.metadata, epoch, restore = true)
            }
    }

    private fun fail(throwable: Throwable?, failure: IdeTargetFailure?) {
        val detail = failure?.detail ?: throwable?.message ?: "Target filesystem request failed"
        current =
            if (failure?.kind == IdeTargetFailureKind.TargetLost || failure?.kind == IdeTargetFailureKind.Closed) {
                IdeComputerTreeState.TargetLost(detail)
            } else {
                IdeComputerTreeState.Unavailable(detail)
            }
    }

    private fun failProtocol(detail: String) {
        current = IdeComputerTreeState.Unavailable(detail)
    }

    private fun enqueue(event: Event) {
        if (!events.offer(event)) overflow.set(true)
    }

    private fun advanceEpoch() {
        epoch = Math.incrementExact(epoch)
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "computer file coordinator may only be used from its construction thread" }
    }

    private data class DirectoryLoad(
        val path: IdeTargetVirtualPath,
        val metadata: IdeTargetFileMetadata,
        val epoch: Long,
        val restore: Boolean,
        val fileSystemGeneration: Long?,
        val entries: List<IdeTargetDirectoryEntry>,
        val cursor: String?,
    )

    private sealed interface Event {
        val epoch: Long

        data class RootStat(
            override val epoch: Long,
            val result: IdeFileStatResult?,
            val failure: Throwable?,
        ) : Event

        data class DirectoryPage(
            override val epoch: Long,
            val load: DirectoryLoad,
            val result: IdeFileListResult?,
            val failure: Throwable?,
        ) : Event
    }

    private companion object {
        val ROOT = IdeTargetVirtualPath.of("/")
        const val PAGE_SIZE = 256
        const val MAXIMUM_DIRECTORY_ENTRIES = 16_384
    }
}

private fun IdeTargetDirectoryEntry.toNode(path: IdeTargetVirtualPath): IdeComputerNode =
    when (metadata.kind) {
        IdeTargetFileKind.File -> IdeComputerNode.File(path, metadata)
        IdeTargetFileKind.Directory -> IdeComputerNode.Directory(path, metadata, IdeComputerChildren.Unloaded)
    }

private fun child(parent: IdeTargetVirtualPath, name: String): IdeTargetVirtualPath =
    IdeTargetVirtualPath.of(if (parent.value == "/") "/$name" else "${parent.value}/$name")

private fun find(root: IdeComputerNode, path: IdeTargetVirtualPath): IdeComputerNode? {
    if (root.path == path) return root
    val children = (root as? IdeComputerNode.Directory)?.children as? IdeComputerChildren.Loaded ?: return null
    return children.nodes.firstNotNullOfOrNull { find(it, path) }
}

private fun replace(
    root: IdeComputerNode.Directory,
    path: IdeTargetVirtualPath,
    transform: (IdeComputerNode.Directory) -> IdeComputerNode.Directory,
): IdeComputerNode.Directory {
    if (root.path == path) return transform(root)
    val loaded = root.children as? IdeComputerChildren.Loaded ?: return root
    var changed = false
    val nodes =
        loaded.nodes.map { node ->
            if (node is IdeComputerNode.Directory) {
                val replacement = replace(node, path, transform)
                if (replacement !== node) changed = true
                replacement
            } else {
                node
            }
        }
    return if (changed) root.copy(children = IdeComputerChildren.Loaded(nodes)) else root
}

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
        val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return leftBytes.size - rightBytes.size
}
