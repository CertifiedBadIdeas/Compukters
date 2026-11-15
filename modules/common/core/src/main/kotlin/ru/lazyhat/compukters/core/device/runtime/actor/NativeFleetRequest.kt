/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetInputResult
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

/**
 * Validates bounded carrier requests before queue admission and translates their typed replies.
 * Only the queued command owns its input payload; the response registry retains [Response] alone.
 * Limits match the existing native transports, including 4096 scalar text input and 32 KiB reads.
 */
internal class NativeFleetRequest private constructor(
    private val command: ProgramRuntimeActorCommand,
    val response: Response,
) : NativeFleetOutbox.Command {
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
        ;

        fun decode(event: ExperimentalFleetEvent): ProgramRuntimeActorValue =
            when {
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

        private companion object {
            val FILE_RESPONSES = setOf(FILE_STAT, FILE_LIST, FILE_READ)
        }
    }

    companion object {
        fun prepare(command: ProgramRuntimeActorCommand): NativeFleetRequest? {
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

                    else -> {
                        return null
                    }
                }
            return NativeFleetRequest(command, response)
        }
    }
}
