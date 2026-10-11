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

import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetState

internal fun targetLabel(state: IdeTargetState): String =
    when (state) {
        IdeTargetState.LocalOnly -> "Local only"
        is IdeTargetState.Attaching -> "Attaching…"
        is IdeTargetState.Detached -> "Target detached"
        is IdeTargetState.Failed -> state.target?.displayName ?: "Target unavailable"
        else -> checkNotNull(state.attachedTarget).displayName
    }

internal fun targetStatus(state: IdeTargetState): String? =
    when (state) {
        IdeTargetState.LocalOnly,
        is IdeTargetState.Attached,
        -> null

        is IdeTargetState.Attaching -> "Attaching target…"

        is IdeTargetState.Uploading -> "Verifying…"

        is IdeTargetState.Verified -> "Verified"

        is IdeTargetState.Observing -> "Checking destination…"

        is IdeTargetState.ConfirmationRequired -> "Overwrite confirmation required"

        is IdeTargetState.Deploying -> "Deploying…"

        is IdeTargetState.Deployed -> "Deployed ${state.path.value}"

        is IdeTargetState.Submitting -> "Submitting command…"

        is IdeTargetState.CommandSubmitted -> state.message

        is IdeTargetState.Detached -> state.failure.detail

        is IdeTargetState.Failed -> state.failure.detail
    }

internal val IdeTargetState.attachedTarget: IdeAttachedTarget?
    get() =
        when (this) {
            IdeTargetState.LocalOnly,
            is IdeTargetState.Attaching,
            is IdeTargetState.Detached,
            -> null

            is IdeTargetState.Attached -> target

            is IdeTargetState.Uploading -> target

            is IdeTargetState.Verified -> target

            is IdeTargetState.Observing -> target

            is IdeTargetState.ConfirmationRequired -> target

            is IdeTargetState.Deploying -> target

            is IdeTargetState.Deployed -> target

            is IdeTargetState.Submitting -> target

            is IdeTargetState.CommandSubmitted -> target

            is IdeTargetState.Failed -> target
        }

internal val IdeTargetState.isReadyForAction: Boolean
    get() =
        this is IdeTargetState.Attached ||
            this is IdeTargetState.Verified ||
            this is IdeTargetState.Deployed ||
            this is IdeTargetState.CommandSubmitted ||
            (this is IdeTargetState.Failed && target != null)
