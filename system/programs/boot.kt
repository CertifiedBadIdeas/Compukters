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

import compukter.io.Stderr
import compukter.process.Process
import compukter.process.ProcessFailureReason
import compukter.process.ProcessResult

fun main() {
    val diagnostic = bootDiagnostic(Process.run("/rom/shell"))
    if (diagnostic != "") Stderr.write("boot failed: " + diagnostic + "\n")
}

private fun bootDiagnostic(result: ProcessResult): String {
    if (result is ProcessResult.Exited) {
        if (result.code == 0) return ""
        return "shell exited with an error"
    }
    if (result is ProcessResult.Failed) return processFailure(result.reason, result.diagnostic)
    return "shell failed"
}

private fun processFailure(
    reason: ProcessFailureReason,
    diagnostic: String,
): String {
    if (diagnostic != "") return diagnostic
    if (reason == ProcessFailureReason.INVALID_PATH) return "invalid path"
    if (reason == ProcessFailureReason.NOT_FOUND) return "shell not found"
    if (reason == ProcessFailureReason.ACCESS_DENIED) return "access denied"
    if (reason == ProcessFailureReason.NOT_EXECUTABLE) return "shell is not executable"
    if (reason == ProcessFailureReason.INVALID_PROGRAM) return "invalid executable"
    if (reason == ProcessFailureReason.INCOMPATIBLE) return "incompatible program"
    if (reason == ProcessFailureReason.LIMIT_EXCEEDED) return "process limit exceeded"
    if (reason == ProcessFailureReason.TRAPPED) return "shell trapped"
    if (reason == ProcessFailureReason.VM_FAULT) return "virtual machine fault"
    if (reason == ProcessFailureReason.HOST_FAILURE) return "host failure"
    return "I/O failure"
}
