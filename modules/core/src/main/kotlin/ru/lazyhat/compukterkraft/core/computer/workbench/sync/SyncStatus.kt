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

package ru.lazyhat.compukterkraft.core.computer.workbench.sync

/**
 * Reactive sync state surfaced to the UI as the editor's sync status indicator.
 *
 * - [Idle]     — no unacked ops; pending count == 0; last ack received.
 * - [Pending]  — local edits queued in the outbox, debounce timer ticking.
 * - [Syncing]  — ops sent to server, awaiting ack within the stale-timeout window.
 * - [Stale]    — server has not acked within `staleAfterMs`; editor remains usable, transitions
 *   back to [Idle] on next ack.
 */
enum class SyncStatus { Idle, Pending, Syncing, Stale }
