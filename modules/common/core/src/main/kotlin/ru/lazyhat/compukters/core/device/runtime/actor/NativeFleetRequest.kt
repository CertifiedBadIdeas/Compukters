/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDeploymentFailure
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDeploymentResult
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetInputResult
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision

/**
 * Validates bounded carrier requests before queue admission and translates their typed replies.
 * Only the queued command owns its input payload; response tracking retains no artifact or other input payload.
 * Limits match the existing native transports, including 4096 scalar text input and 32 KiB reads.
 */
internal class NativeFleetRequest private constructor(
    private val command: ProgramRuntimeActorCommand,
    val response: Response,
    private val path: VmVirtualPath? = null,
) : NativeFleetOutbox.Command {
    override val retainedBytes: Long =
        64L +
            when (command) {
                is ProgramRuntimeActorCommand.SendTerminalText -> 2L * command.value.length
                is ProgramRuntimeActorCommand.SubmitCanonicalLine -> 2L * command.lineLength
                is ProgramRuntimeActorCommand.FileStat -> 2L * command.path.value.length
                is ProgramRuntimeActorCommand.FileList -> 2L * (command.path.value.length + (command.startAfter?.length ?: 0))
                is ProgramRuntimeActorCommand.FileRead -> 2L * command.path.value.length
                is ProgramRuntimeActorCommand.PrepareDeployment -> command.artifactSize.toLong()
                is ProgramRuntimeActorCommand.ExecutableRevision -> 2L * command.path.length
                is ProgramRuntimeActorCommand.Deploy -> 2L * command.path.length
                else -> 0L
            }

    val deploymentCandidate: Long?
        get() = (command as? ProgramRuntimeActorCommand.Deploy)?.token?.value

    override fun send(
        fleet: ExperimentalFleet,
        machine: Long,
    ) {
        val id = command.requestId.value
        when (val command = command) {
            is ProgramRuntimeActorCommand.FileSystemGeneration -> {
                fleet.filesystemGeneration(machine, id)
            }

            is ProgramRuntimeActorCommand.TerminalFullState -> {
                fleet.terminalUpdate(machine, id, Long.MAX_VALUE)
            }

            is ProgramRuntimeActorCommand.TerminalChangesSince -> {
                fleet.terminalUpdate(machine, id, command.revision)
            }

            is ProgramRuntimeActorCommand.SendTerminalKey -> {
                fleet.terminalKey(machine, command.key, command.action, command.modifiers, id)
            }

            is ProgramRuntimeActorCommand.SendTerminalText -> {
                fleet.terminalText(machine, command.value, id)
            }

            is ProgramRuntimeActorCommand.SubmitCanonicalLine -> {
                fleet.canonicalLine(machine, command.lineChars(), id)
            }

            is ProgramRuntimeActorCommand.FileStat -> {
                fleet.fileStat(machine, id, command.path)
            }

            is ProgramRuntimeActorCommand.FileList -> {
                fleet.fileList(machine, id, command.path, command.startAfter, command.maximumEntries)
            }

            is ProgramRuntimeActorCommand.FileRead -> {
                fleet.fileRead(
                    machine,
                    id,
                    command.path,
                    command.offset,
                    command.maximumBytes,
                    command.expectedGeneration,
                )
            }

            is ProgramRuntimeActorCommand.PrepareDeployment -> {
                fleet.prepareDeployment(machine, id, command.artifactBytes())
            }

            is ProgramRuntimeActorCommand.ExecutableRevision -> {
                fleet.deploymentRevision(machine, id, checkNotNull(path))
            }

            is ProgramRuntimeActorCommand.DiscardDeployment -> {
                fleet.discardDeployment(machine, id, command.token.value)
            }

            is ProgramRuntimeActorCommand.Deploy -> {
                if (response == Response.INVALID_DEPLOY) {
                    fleet.discardDeployment(machine, id, command.token.value)
                } else {
                    fleet.installDeployment(machine, id, checkNotNull(path), command.token.value, command.expected)
                }
            }

            else -> {
                error("unsupported native request")
            }
        }
    }

    enum class Response {
        GENERATION,
        TERMINAL_FULL,
        TERMINAL_UPDATE,
        INPUT,
        INTERRUPT_INPUT,
        FILE_STAT,
        FILE_LIST,
        FILE_READ,
        PREPARE_DEPLOYMENT,
        EXECUTABLE_REVISION,
        DEPLOY,
        DISCARD_DEPLOYMENT,
        INVALID_DEPLOY,
        ;

        fun decode(event: ExperimentalFleetEvent): ProgramRuntimeActorValue =
            when {
                event is ExperimentalFleetEvent.Deployment && this in DEPLOYMENT_RESPONSES -> {
                    decodeDeployment(event.result)
                }

                event is ExperimentalFleetEvent.FileFailure && this in FILE_RESPONSES -> {
                    ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.FileSystem(event.failure))
                }

                this == GENERATION && event is ExperimentalFleetEvent.FilesystemGeneration -> {
                    ProgramRuntimeActorValue.FileSystemGeneration(event.generation)
                }

                this == TERMINAL_FULL && event is ExperimentalFleetEvent.Terminal && event.update is TerminalUpdate.Full -> {
                    ProgramRuntimeActorValue.TerminalStateValue((event.update as TerminalUpdate.Full).state)
                }

                this == TERMINAL_UPDATE && event is ExperimentalFleetEvent.Terminal -> {
                    ProgramRuntimeActorValue.TerminalUpdateValue(event.update)
                }

                (this == INPUT || this == INTERRUPT_INPUT) && event is ExperimentalFleetEvent.InputResult -> {
                    when (val result = event.result) {
                        ExperimentalFleetInputResult.Accepted -> {
                            ProgramRuntimeActorValue.Accepted(true)
                        }

                        ExperimentalFleetInputResult.TerminalRejected -> {
                            ProgramRuntimeActorValue.Accepted(false)
                        }

                        is ExperimentalFleetInputResult.CanonicalRejected -> {
                            ProgramRuntimeActorValue.Rejected(
                                ProgramRuntimeActorFailure.CanonicalLine(result.failure),
                            )
                        }
                    }
                }

                this == FILE_STAT && event is ExperimentalFleetEvent.FileStat -> {
                    ProgramRuntimeActorValue.FileStatValue(event.stat)
                }

                this == FILE_LIST && event is ExperimentalFleetEvent.FileList -> {
                    ProgramRuntimeActorValue.FileListValue(event.listing)
                }

                this == FILE_READ && event is ExperimentalFleetEvent.FileRead -> {
                    ProgramRuntimeActorValue.FileChunkValue(event.chunk)
                }

                else -> {
                    error("unexpected native response for $this: ${event.javaClass.simpleName}")
                }
            }

        private fun decodeDeployment(result: ExperimentalFleetDeploymentResult): ProgramRuntimeActorValue {
            if (this == INVALID_DEPLOY && (
                    result == ExperimentalFleetDeploymentResult.Discarded ||
                        result == ExperimentalFleetDeploymentResult.Failed(ExperimentalFleetDeploymentFailure.INVALID_CANDIDATE)
                )
            ) {
                return ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.InvalidRequest("invalid deployment path or revision"))
            }
            if (result is ExperimentalFleetDeploymentResult.Failed) {
                val failure =
                    when (result.failure) {
                        ExperimentalFleetDeploymentFailure.VERIFICATION -> {
                            ProgramRuntimeActorFailure.Verification
                        }

                        ExperimentalFleetDeploymentFailure.ADMISSION, ExperimentalFleetDeploymentFailure.CAPACITY -> {
                            ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.ADMISSION)
                        }

                        ExperimentalFleetDeploymentFailure.INVALID_CANDIDATE -> {
                            if (this == DISCARD_DEPLOYMENT) return ProgramRuntimeActorValue.Accepted(false)
                            ProgramRuntimeActorFailure.InvalidRequest("unknown or consumed deployment token")
                        }

                        ExperimentalFleetDeploymentFailure.WRONG_MACHINE -> {
                            ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.WRONG_MACHINE)
                        }

                        ExperimentalFleetDeploymentFailure.PROFILE_CHANGED -> {
                            ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.PROFILE_CHANGED)
                        }

                        ExperimentalFleetDeploymentFailure.BUSY -> {
                            ProgramRuntimeActorFailure.Deployment(
                                if (this ==
                                    DEPLOY
                                ) {
                                    ProgramDeploymentFailure.CONFLICT
                                } else {
                                    ProgramDeploymentFailure.FILESYSTEM
                                },
                            )
                        }

                        else -> {
                            ProgramRuntimeActorFailure.Deployment(ProgramDeploymentFailure.FILESYSTEM)
                        }
                    }
                return ProgramRuntimeActorValue.Rejected(failure)
            }
            return when {
                this == PREPARE_DEPLOYMENT && result is ExperimentalFleetDeploymentResult.Prepared -> {
                    ProgramRuntimeActorValue.DeploymentPrepared(ProgramDeploymentToken(result.candidate))
                }

                this == EXECUTABLE_REVISION && result is ExperimentalFleetDeploymentResult.Revision -> {
                    ProgramRuntimeActorValue.ExecutableRevisionValue(result.revision)
                }

                this == DEPLOY && result is ExperimentalFleetDeploymentResult.Installed -> {
                    ProgramRuntimeActorValue.DeployedRevision(result.revision)
                }

                this == DISCARD_DEPLOYMENT && result == ExperimentalFleetDeploymentResult.Discarded -> {
                    ProgramRuntimeActorValue.Accepted(true)
                }

                else -> {
                    error("unexpected native deployment response for $this: $result")
                }
            }
        }

        private companion object {
            val DEPLOYMENT_RESPONSES = setOf(PREPARE_DEPLOYMENT, EXECUTABLE_REVISION, DEPLOY, DISCARD_DEPLOYMENT, INVALID_DEPLOY)
            val FILE_RESPONSES = setOf(FILE_STAT, FILE_LIST, FILE_READ)
        }
    }

    companion object {
        fun prepare(command: ProgramRuntimeActorCommand): NativeFleetRequest? {
            var path: VmVirtualPath? = null
            val response =
                when (command) {
                    is ProgramRuntimeActorCommand.FileSystemGeneration -> {
                        Response.GENERATION
                    }

                    is ProgramRuntimeActorCommand.TerminalFullState -> {
                        Response.TERMINAL_FULL
                    }

                    is ProgramRuntimeActorCommand.TerminalChangesSince -> {
                        Response.TERMINAL_UPDATE
                    }

                    is ProgramRuntimeActorCommand.SendTerminalKey -> {
                        if (
                            command.key == TerminalKey.C && command.action == TerminalKeyAction.PRESS &&
                            TerminalModifier.CONTROL in command.modifiers
                        ) {
                            Response.INTERRUPT_INPUT
                        } else {
                            Response.INPUT
                        }
                    }

                    is ProgramRuntimeActorCommand.SendTerminalText -> {
                        require(
                            command.value.length <= 8192 && command.value.codePointCount(0, command.value.length) <= 4096 &&
                                command.value.codePoints().allMatch { it !in 0xd800..0xdfff },
                        ) { "terminal text exceeds its bound or contains an unpaired surrogate" }
                        Response.INPUT
                    }

                    is ProgramRuntimeActorCommand.SubmitCanonicalLine -> {
                        require(command.lineLength <= 4096) { "canonical line exceeds its bound" }
                        Response.INPUT
                    }

                    is ProgramRuntimeActorCommand.FileStat -> {
                        Response.FILE_STAT
                    }

                    is ProgramRuntimeActorCommand.FileList -> {
                        require(command.maximumEntries in 1..256) { "directory listing exceeds its bound" }
                        val cursor = command.startAfter
                        require(
                            cursor == null || (cursor.isNotEmpty() && cursor.length <= 4096 && cursor.encodeToByteArray().size <= 4096),
                        ) {
                            "directory cursor exceeds its bound or is empty"
                        }
                        Response.FILE_LIST
                    }

                    is ProgramRuntimeActorCommand.FileRead -> {
                        require(command.maximumBytes in 1..32768) { "file read exceeds its bound" }
                        Response.FILE_READ
                    }

                    is ProgramRuntimeActorCommand.PrepareDeployment -> {
                        require(command.artifactSize <= 16 * 1024 * 1024) { "deployment artifact exceeds its bound" }
                        Response.PREPARE_DEPLOYMENT
                    }

                    is ProgramRuntimeActorCommand.ExecutableRevision -> {
                        path = VmVirtualPath.of(command.path)
                        Response.EXECUTABLE_REVISION
                    }

                    is ProgramRuntimeActorCommand.DiscardDeployment -> {
                        Response.DISCARD_DEPLOYMENT
                    }

                    is ProgramRuntimeActorCommand.Deploy -> {
                        path =
                            try {
                                VmVirtualPath.of(command.path)
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        if (path == null ||
                            command.expected == VmExecutableRevision.Present(0)
                        ) {
                            Response.INVALID_DEPLOY
                        } else {
                            Response.DEPLOY
                        }
                    }

                    else -> {
                        return null
                    }
                }
            return NativeFleetRequest(command, response, path)
        }
    }
}
