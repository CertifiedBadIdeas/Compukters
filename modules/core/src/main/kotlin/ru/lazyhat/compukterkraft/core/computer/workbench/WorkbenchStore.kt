/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.core.computer.workbench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.ClientCrdtReplica
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CrdtDocument
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.sync.OpOutbox
import ru.lazyhat.compukterkraft.core.computer.workbench.sync.SyncStatus
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.frontend.SourceTextSupport
import java.util.UUID

/**
 * Client-side state container for the Workbench authoring GUI.
 *
 * All state is exposed via [stateFlow] ([StateFlow]) so consumers can
 * observe changes reactively. Synchronous reads use [state] (delegates to `.value`).
 *
 * Remote workspace updates arrive through a [WorkbenchUpdateSource]'s [StateFlow].
 * Call [bind] with a [CoroutineScope] to start reactively collecting remote state changes.
 *
 * Text mutations flow through the CRDT replica:
 * - [applyLocalEdit] turns a [LocalEdit] into an [Op], applies it, and enqueues it on the
 *   outbox for batched dispatch via [opsGateway].
 * - [applyRemoteOps] / [applyAck] / [onSnapshot] are called by the network layer when ops or
 *   snapshots arrive from the server.
 * - [flushAndRun] drains the outbox before issuing RUN so the server sees the latest text.
 */
class WorkbenchStore(
    private val workspaceGateway: WorkspaceGateway,
    private val controlGateway: ComputerControlGateway,
    private val ideFacade: WorkbenchIdeFacade,
    private val opsGateway: WorkbenchOpsGateway = NoOpWorkbenchOpsGateway,
    private val siteIdProvider: () -> SiteId = { SiteId.player(UUID.randomUUID()) },
) {
    private val _state = MutableStateFlow(WorkbenchState())

    /** Observable workbench state. */
    val stateFlow: StateFlow<WorkbenchState> = _state.asStateFlow()

    /** Current workbench state (synchronous read). */
    val state: WorkbenchState get() = _state.value

    private var collectJob: Job? = null
    private var statusCollectJob: Job? = null
    private var pendingCollectJob: Job? = null

    private val siteId: SiteId = siteIdProvider()

    /** Per-document CRDT replica. Recreated on document open / snapshot. Visible for tests. */
    internal var replica: ClientCrdtReplica? = null
        private set

    /** Outbox for the current bind() scope. Visible for tests. */
    internal var outbox: OpOutbox? = null
        private set

    /** Path bound to [outbox]'s send callback for the current open document. */
    private var outboxPath: String? = null

    private var bindScope: CoroutineScope? = null

    /**
     * Bind a [WorkbenchUpdateSource] and start reactively collecting its [StateFlow].
     * Remote changes are merged into local state as soon as they arrive.
     */
    fun bind(
        scope: CoroutineScope,
        updateSource: WorkbenchUpdateSource,
    ) {
        collectJob?.cancel()
        bindScope = scope
        // Immediately merge the current value
        mergeRemoteState(updateSource.stateFlow.value)
        collectJob =
            scope.launch {
                updateSource.stateFlow.collect { remote ->
                    mergeRemoteState(remote)
                }
            }
    }

    fun dispose() {
        collectJob?.cancel()
        collectJob = null
        statusCollectJob?.cancel()
        statusCollectJob = null
        pendingCollectJob?.cancel()
        pendingCollectJob = null
        bindScope = null
        outbox = null
        outboxPath = null
        replica = null
    }

    fun initialize() {
        requestListing("")
    }

    fun toggleTerminalVisibility() {
        // Hiding is always allowed; showing requires a computer in the slot,
        // otherwise the terminal would attach to nothing.
        val nextVisible = !state.terminalVisible
        if (nextVisible && !state.actions.canAttachTerminal) return
        _state.value = state.copy(terminalVisible = nextVisible)
    }

    fun requestListing(path: String) {
        val normalizedPath = path.trim('/').trim()
        _state.value = state.copy(browserPath = normalizedPath)
        workspaceGateway.list(normalizedPath)
    }

    fun requestDocument(path: String) {
        workspaceGateway.read(path)
    }

    fun refreshWorkspace() {
        requestListing(state.browserPath)
        state.openDocument?.path?.let(::requestDocument)
    }

    fun navigateUp() {
        requestListing(state.browserPath.substringBeforeLast('/', ""))
    }

    fun rebootComputer() {
        controlGateway.reboot()
    }

    fun runTargetProgram() {
        if (!state.actions.canRun) return
        controlGateway.runTargetProgram()
    }

    fun attachTargetTerminal() {
        if (!state.actions.canAttachTerminal) return
        controlGateway.attachTargetTerminal()
    }

    fun updateHover(
        line: Int,
        column: Int,
    ) {
        val document = state.openDocument ?: return
        val hoverInfo = ideFacade.hover(document.path, state.editor.text, line, column)
        _state.value = state.copy(editor = state.editor.copy(hoverInfo = hoverInfo))
    }

    fun clearHover() {
        _state.value = state.copy(editor = state.editor.copy(hoverInfo = null))
    }

    fun openCompletion() {
        val document = state.openDocument ?: return
        val items =
            ideFacade.complete(
                document.path,
                state.editor.text,
                state.editor.cursorLine,
                state.editor.cursorColumn,
            )
        _state.value = state.copy(editor = state.editor.copy(completionItems = items, selectedCompletion = 0))
    }

    fun openImportPicker() {
        val document = state.openDocument ?: return
        val items = ideFacade.availableImports(document.path, state.editor.text)
        _state.value =
            state.copy(
                editor =
                    state.editor.copy(
                        importPickerVisible = items.isNotEmpty(),
                        importPickerItems = items,
                        selectedImportPickerIndex = 0,
                    ),
            )
    }

    fun closeImportPicker() {
        _state.value =
            state.copy(
                editor =
                    state.editor.copy(
                        importPickerVisible = false,
                        importPickerItems = emptyList(),
                        selectedImportPickerIndex = 0,
                    ),
            )
    }

    fun closeCompletion() {
        _state.value = state.copy(editor = state.editor.copy(completionItems = emptyList(), selectedCompletion = 0))
    }

    fun applyCompletion(index: Int = state.editor.selectedCompletion) {
        val item = state.editor.completionItems.getOrNull(index) ?: return
        val ed = state.editor
        val lines = ed.lines()
        val line = lines.getOrNull(ed.cursorLine) ?: return
        val identifierStart = identifierStart(line, ed.cursorColumn)
        val cursorFlat = SourceTextSupport.offsetAt(ed.text, ed.cursorLine, ed.cursorColumn)
        val prefixFlat = cursorFlat - (ed.cursorColumn - identifierStart)
        val textToInsert = item.insertText ?: item.label
        val deletedLength = cursorFlat - prefixFlat
        if (deletedLength > 0) applyLocalEdit(LocalEdit.Delete(prefixFlat, deletedLength))
        if (textToInsert.isNotEmpty()) applyLocalEdit(LocalEdit.Insert(prefixFlat, textToInsert))
        _state.value = state.copy(
            editor = state.editor.copy(completionItems = emptyList(), selectedCompletion = 0),
        )
        refreshIde()
    }

    fun applyImportPickerSelection(
        index: Int = state.editor.selectedImportPickerIndex,
        visibleEditorLines: Int,
    ) {
        val item = state.editor.importPickerItems.getOrNull(index) ?: return
        val importText = "import ${item.label};\n"
        applyLocalEdit(LocalEdit.Insert(0, importText))
        // Caret advance applyLocalEdit already handles; ensure visibility.
        _state.value = state.copy(editor = state.editor.keepCursorVisible(visibleEditorLines))
        refreshIde()
        closeImportPicker()
    }

    fun moveCursorTo(
        line: Int,
        column: Int,
        visibleEditorLines: Int,
    ) {
        _state.value = state.copy(editor = state.editor.withCursor(line, column, visibleEditorLines))
    }

    fun scrollEditor(deltaLines: Int) {
        _state.value = state.copy(editor = state.editor.scrollBy(deltaLines))
    }

    fun keyPressed(
        key: Int,
        modifiers: Int,
        visibleEditorLines: Int,
    ): Boolean {
        if (key == KeyCodes.KEY_F4) {
            toggleTerminalVisibility()
            return true
        }

        if ((modifiers and KeyCodes.MOD_CONTROL) != 0) {
            when (key) {
                KeyCodes.KEY_A -> {
                    openImportPicker()
                    return true
                }

                KeyCodes.KEY_SPACE -> {
                    openCompletion()
                    return true
                }
            }
        }

        if (shouldCapturePrintableKeyDown(key, modifiers)) {
            return true
        }

        if (state.editor.importPickerVisible) {
            when (key) {
                KeyCodes.KEY_UP -> {
                    selectImportPicker(state.editor.selectedImportPickerIndex - 1)
                    return true
                }

                KeyCodes.KEY_DOWN -> {
                    selectImportPicker(state.editor.selectedImportPickerIndex + 1)
                    return true
                }

                KeyCodes.KEY_ENTER,
                KeyCodes.KEY_KP_ENTER,
                KeyCodes.KEY_TAB,
                -> {
                    applyImportPickerSelection(visibleEditorLines = visibleEditorLines)
                    return true
                }

                KeyCodes.KEY_ESCAPE -> {
                    closeImportPicker()
                    return true
                }
            }
        }

        if (state.editor.completionItems.isNotEmpty()) {
            when (key) {
                KeyCodes.KEY_UP -> {
                    selectCompletion(state.editor.selectedCompletion - 1)
                    return true
                }

                KeyCodes.KEY_DOWN -> {
                    selectCompletion(state.editor.selectedCompletion + 1)
                    return true
                }

                KeyCodes.KEY_ENTER,
                KeyCodes.KEY_KP_ENTER,
                KeyCodes.KEY_TAB,
                -> {
                    applyCompletion()
                    return true
                }

                KeyCodes.KEY_ESCAPE -> {
                    closeCompletion()
                    return true
                }
            }
        }

        val nextEditor =
            when (key) {
                KeyCodes.KEY_LEFT -> state.editor.moveCursorHorizontal(-1, visibleEditorLines)

                KeyCodes.KEY_RIGHT -> state.editor.moveCursorHorizontal(1, visibleEditorLines)

                KeyCodes.KEY_UP -> state.editor.moveCursorVertical(-1, visibleEditorLines)

                KeyCodes.KEY_DOWN -> state.editor.moveCursorVertical(1, visibleEditorLines)

                KeyCodes.KEY_BACKSPACE -> {
                    deleteBackwardThroughCrdt(visibleEditorLines)
                    return true
                }

                KeyCodes.KEY_DELETE -> {
                    deleteForwardThroughCrdt(visibleEditorLines)
                    return true
                }

                KeyCodes.KEY_ENTER,
                KeyCodes.KEY_KP_ENTER,
                -> {
                    insertTextThroughCrdt("\n", visibleEditorLines)
                    return true
                }

                KeyCodes.KEY_TAB -> {
                    insertTextThroughCrdt("    ", visibleEditorLines)
                    return true
                }

                KeyCodes.KEY_PAGE_UP -> state.editor.scrollBy(-visibleEditorLines)

                KeyCodes.KEY_PAGE_DOWN -> state.editor.scrollBy(visibleEditorLines)

                KeyCodes.KEY_F12 -> navigateToDefinition(visibleEditorLines)

                else -> return false
            }

        _state.value = state.copy(editor = nextEditor)
        refreshIde()
        return true
    }

    private fun shouldCapturePrintableKeyDown(
        key: Int,
        modifiers: Int,
    ): Boolean = (modifiers and KeyCodes.MOD_CONTROL) == 0 && key in 32..126

    fun charTyped(
        ch: Char,
        visibleEditorLines: Int,
    ): Boolean {
        if (state.editor.importPickerVisible) {
            return true
        }
        if (!Character.isISOControl(ch)) {
            insertTextThroughCrdt(ch.toString(), visibleEditorLines)
            if (shouldOpenCompletionAfterCharTyped(ch)) {
                openCompletionFromCurrentSnapshot()
            }
        }
        return true
    }

    private fun shouldOpenCompletionAfterCharTyped(ch: Char): Boolean {
        if (ch == '.') return true
        if (!(ch == '_' || ch.isLetterOrDigit())) return false
        return SourceTextSupport.shouldAutoTriggerIdentifierCompletion(
            state.editor.text,
            SourceTextSupport.offsetAt(state.editor.text, state.editor.cursorLine, state.editor.cursorColumn),
        )
    }

    private fun openCompletionFromCurrentSnapshot() {
        val document = state.openDocument ?: return
        val items =
            ideFacade.completeFromLastAnalysis(
                document.path,
                state.editor.text,
                state.editor.cursorLine,
                state.editor.cursorColumn,
            )
        if (items.isNotEmpty()) {
            _state.value =
                state.copy(
                    editor = state.editor.copy(completionItems = items, selectedCompletion = 0),
                )
        }
    }

    private fun mergeRemoteState(remoteState: WorkbenchRemoteState) {
        val documentChanged = remoteState.document != state.openDocument
        var nextState = state

        if (remoteState.entries != state.entries) {
            nextState = nextState.copy(entries = remoteState.entries)
        }

        if (remoteState.document != state.openDocument) {
            nextState =
                nextState.copy(
                    openDocument = remoteState.document,
                    editor =
                        remoteState.document
                            ?.let {
                                EditorState(text = it.text)
                            } ?: EditorState(),
                )
            // Bootstrap a CRDT replica from the legacy READ payload so subsequent local
            // edits flow through the op pipeline. The wire snapshot path will replace this
            // via [onSnapshot] when the cross-version gateway delivers it.
            if (remoteState.document != null) {
                bootstrapReplica(remoteState.document.path, remoteState.document.text)
            } else {
                replica = null
                outbox = null
                outboxPath = null
            }
        }

        val actionState =
            WorkbenchActionState(
                canRun = remoteState.target.connected,
                canAttachTerminal = remoteState.target.connected,
            )

        _state.value =
            nextState.copy(
                target = remoteState.target,
                actions = actionState,
                // Auto-hide the terminal when the computer is removed; it has
                // nothing to attach to and would render an empty buffer.
                terminalVisible = nextState.terminalVisible && actionState.canAttachTerminal,
            )
        if (documentChanged && remoteState.document != null) {
            refreshIde()
        }
    }

    private fun refreshIde() {
        val document = state.openDocument ?: return
        val snapshot = ideFacade.analyze(document.path, state.editor.text)
        _state.value =
            state.copy(
                editor =
                    state.editor.copy(
                        ideSnapshot = snapshot,
                        hoverInfo = null,
                        completionItems = emptyList(),
                        selectedCompletion = 0,
                    ),
            )
    }

    private fun navigateToDefinition(visibleEditorLines: Int): EditorState {
        val document = state.openDocument ?: return state.editor
        val target =
            ideFacade
                .definition(
                    document.path,
                    state.editor.text,
                    state.editor.cursorLine,
                    state.editor.cursorColumn,
                )?.takeIf { it.path == document.path } ?: return state.editor
        return state.editor.withCursor(target.range.start.line, target.range.start.column, visibleEditorLines)
    }

    private fun selectCompletion(index: Int) {
        val items = state.editor.completionItems
        if (items.isEmpty()) return
        val normalizedIndex = ((index % items.size) + items.size) % items.size
        _state.value = state.copy(editor = state.editor.copy(selectedCompletion = normalizedIndex))
    }

    private fun selectImportPicker(index: Int) {
        val items = state.editor.importPickerItems
        if (items.isEmpty()) return
        val normalizedIndex = ((index % items.size) + items.size) % items.size
        _state.value = state.copy(editor = state.editor.copy(selectedImportPickerIndex = normalizedIndex))
    }

    // -------------------------------------------------------------------------------------
    // CRDT sync API
    // -------------------------------------------------------------------------------------

    /**
     * Apply a [LocalEdit] to the editor: produce the corresponding [Op], apply it locally,
     * enqueue it on the outbox, and recompute the editor text + cursor.
     *
     * No-op when the document has not been opened (replica == null).
     */
    fun applyLocalEdit(edit: LocalEdit) {
        val rep = replica ?: return
        val ed = state.editor
        val cursorFlatBefore = SourceTextSupport.offsetAt(ed.text, ed.cursorLine, ed.cursorColumn)
        val op: Op = when (edit) {
            is LocalEdit.Insert -> {
                if (edit.text.isEmpty()) return
                rep.produceInsert(edit.offset, edit.text)
            }
            is LocalEdit.Delete -> {
                if (edit.length <= 0) return
                rep.produceDelete(edit.offset, edit.length)
            }
        }
        rep.applyLocal(op)
        outbox?.enqueue(op)

        val newText = rep.document.flatten()
        val newCursorFlat = when (edit) {
            is LocalEdit.Insert -> if (edit.offset <= cursorFlatBefore) cursorFlatBefore + edit.text.length else cursorFlatBefore
            is LocalEdit.Delete -> when {
                edit.offset + edit.length <= cursorFlatBefore -> cursorFlatBefore - edit.length
                edit.offset >= cursorFlatBefore -> cursorFlatBefore
                else -> edit.offset
            }
        }
        val (newLine, newCol) = lineColumnAt(newText, newCursorFlat)
        _state.value = state.copy(
            editor = state.editor.copy(
                text = newText,
                cursorLine = newLine,
                cursorColumn = newCol,
            ),
        )
        refreshIde()
    }

    /**
     * Apply a batch of remote ops to the local replica and shift the cursor to follow its
     * visible position when remote inserts/deletes happen to the LEFT of the caret.
     */
    fun applyRemoteOps(ops: List<Op>) {
        val rep = replica ?: return
        val ed = state.editor
        var cursorFlat = SourceTextSupport.offsetAt(ed.text, ed.cursorLine, ed.cursorColumn)
        for (op in ops) {
            if (op.author == rep.siteId) continue
            val before = rep.document
            rep.applyRemote(op)
            cursorFlat = shiftCursorByOp(before.flatten(), rep.document.flatten(), cursorFlat, op)
        }
        val newText = rep.document.flatten()
        val (newLine, newCol) = lineColumnAt(newText, cursorFlat.coerceIn(0, newText.length))
        _state.value = state.copy(
            editor = state.editor.copy(
                text = newText,
                cursorLine = newLine,
                cursorColumn = newCol,
            ),
        )
        refreshIde()
    }

    /** Acknowledge that the server has applied ops up to and including [ackedClock]. */
    fun applyAck(ackedClock: Int) {
        replica?.applyAck(ackedClock)
        outbox?.onAck(ackedClock)
    }

    /**
     * Drain the outbox, wait until [SyncStatus.Idle] (or [timeoutMs] elapses), then issue RUN.
     * Used by the toolbar's RUN button and the integration test.
     */
    suspend fun flushAndRun(timeoutMs: Long = 3_000L): Boolean {
        if (!state.actions.canRun) return false
        val ob = outbox
        if (ob != null) {
            ob.flushNow()
            withTimeoutOrNull(timeoutMs) {
                ob.status.first { it == SyncStatus.Idle }
            }
        }
        controlGateway.runTargetProgram()
        return true
    }

    /**
     * Replace the current replica with one rebuilt from a server-authoritative snapshot. Used
     * when joining a session or recovering after a rejected op.
     */
    fun onSnapshot(
        path: String,
        document: CrdtDocument,
    ) {
        val open = state.openDocument
        if (open == null || open.path != path) return
        replica = ClientCrdtReplica(siteId, document)
        outboxPath = path
        outbox = createOutbox(path)
        val text = document.flatten()
        _state.value = state.copy(
            openDocument = open.copy(text = text),
            editor = state.editor.copy(
                text = text,
                cursorLine = 0,
                cursorColumn = 0,
                pendingOpCount = 0,
                syncStatus = SyncStatus.Idle,
            ),
        )
        refreshIde()
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    private fun bootstrapReplica(
        path: String,
        text: String,
    ) {
        val initial = CrdtDocument.fromText(text, SiteId.ServerInit)
        replica = ClientCrdtReplica(siteId, initial)
        outboxPath = path
        outbox = createOutbox(path)
    }

    private fun createOutbox(path: String): OpOutbox? {
        val scope = bindScope ?: return null
        statusCollectJob?.cancel()
        pendingCollectJob?.cancel()
        val ob = OpOutbox(
            scope = scope,
            send = { batch -> opsGateway.sendOps(path, batch) },
        )
        statusCollectJob = scope.launch {
            ob.status.collect { status ->
                _state.value = state.copy(editor = state.editor.copy(syncStatus = status))
            }
        }
        pendingCollectJob = scope.launch {
            ob.pendingCount.collect { count ->
                _state.value = state.copy(editor = state.editor.copy(pendingOpCount = count))
            }
        }
        return ob
    }

    private fun insertTextThroughCrdt(
        insertedText: String,
        visibleEditorLines: Int,
    ) {
        if (replica == null) {
            _state.value = state.copy(editor = state.editor.insertText(insertedText, visibleEditorLines))
            refreshIde()
            return
        }
        val ed = state.editor
        val flat = SourceTextSupport.offsetAt(ed.text, ed.cursorLine, ed.cursorColumn)
        applyLocalEdit(LocalEdit.Insert(flat, insertedText))
        _state.value = state.copy(editor = state.editor.keepCursorVisible(visibleEditorLines))
    }

    private fun deleteBackwardThroughCrdt(visibleEditorLines: Int) {
        if (replica == null) {
            _state.value = state.copy(editor = state.editor.deleteBackward().keepCursorVisible(visibleEditorLines))
            refreshIde()
            return
        }
        val ed = state.editor
        val flat = SourceTextSupport.offsetAt(ed.text, ed.cursorLine, ed.cursorColumn)
        if (flat == 0) return
        applyLocalEdit(LocalEdit.Delete(flat - 1, 1))
        _state.value = state.copy(editor = state.editor.keepCursorVisible(visibleEditorLines))
    }

    private fun deleteForwardThroughCrdt(visibleEditorLines: Int) {
        if (replica == null) {
            _state.value = state.copy(editor = state.editor.deleteForward().keepCursorVisible(visibleEditorLines))
            refreshIde()
            return
        }
        val ed = state.editor
        val flat = SourceTextSupport.offsetAt(ed.text, ed.cursorLine, ed.cursorColumn)
        if (flat >= ed.text.length) return
        applyLocalEdit(LocalEdit.Delete(flat, 1))
        _state.value = state.copy(editor = state.editor.keepCursorVisible(visibleEditorLines))
    }

    private fun lineColumnAt(text: String, offset: Int): Pair<Int, Int> {
        var line = 0
        var col = 0
        val end = offset.coerceAtMost(text.length)
        for (i in 0 until end) {
            if (text[i] == '\n') {
                line += 1
                col = 0
            } else {
                col += 1
            }
        }
        return line to col
    }

    private fun shiftCursorByOp(
        before: String,
        @Suppress("UNUSED_PARAMETER") after: String,
        cursorFlat: Int,
        op: Op,
    ): Int = when (op) {
        is Op.Insert -> {
            // Locate the first inserted char's flat offset in `after` by scanning the new
            // visible string. The insert atom shows up immediately to the right of leftId.
            val insertOffset = leftAtomVisibleOffset(op.leftId, before)
            if (insertOffset <= cursorFlat) cursorFlat + op.text.length else cursorFlat
        }
        is Op.Delete -> {
            val targetOffset = visibleOffsetOfAtom(op.targetId, before)
            if (targetOffset == -1) cursorFlat
            else when {
                targetOffset + op.length <= cursorFlat -> cursorFlat - op.length
                targetOffset >= cursorFlat -> cursorFlat
                else -> targetOffset
            }
        }
    }

    private fun leftAtomVisibleOffset(
        leftId: ru.lazyhat.compukterkraft.core.computer.workbench.crdt.AtomId?,
        @Suppress("UNUSED_PARAMETER") before: String,
    ): Int {
        if (leftId == null) return 0
        val rep = replica ?: return 0
        // Insert was already applied to `rep.document` by the time this runs; recover the
        // visible offset just AFTER leftId in the current document.
        var consumed = 0
        for (run in rep.document.runs) {
            if (run.deleted) continue
            for (i in run.text.indices) {
                if (run.id.site == leftId.site && run.id.clock + i == leftId.clock) {
                    return consumed + i + 1
                }
            }
            consumed += run.text.length
        }
        return consumed
    }

    private fun visibleOffsetOfAtom(
        atomId: ru.lazyhat.compukterkraft.core.computer.workbench.crdt.AtomId,
        @Suppress("UNUSED_PARAMETER") before: String,
    ): Int {
        // After delete is applied the run is tombstoned; walk the runs counting visible chars
        // until we reach the run that USED to contain atomId.
        val rep = replica ?: return -1
        var consumed = 0
        for (run in rep.document.runs) {
            if (run.id.site == atomId.site &&
                atomId.clock in run.id.clock until (run.id.clock + run.text.length)
            ) {
                return consumed + (atomId.clock - run.id.clock)
            }
            if (!run.deleted) consumed += run.text.length
        }
        return -1
    }

    private fun identifierStart(line: String, column: Int): Int {
        var i = column
        while (i > 0) {
            val ch = line[i - 1]
            if (!(ch.isLetterOrDigit() || ch == '_')) break
            i -= 1
        }
        return i
    }
}
