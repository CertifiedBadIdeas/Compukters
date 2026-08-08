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

package ru.lazyhat.compukterkraft.impl.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.module.sdkArtifactIdentity
import ru.lazyhat.compukterkraft.common.notebook.item.NotebookItem
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.runtimeSnapshot
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceFailureState
import ru.lazyhat.compukterkraft.impl.ModRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

@GameTestHolder(MOD_ID)
@PrefixGameTestTemplate(false)
class ComputerBlockGameTest {
    private fun placeComputer(
        helper: GameTestHelper,
        pos: BlockPos,
        stack: ItemStack = ItemStack(ModRegistry.Items.NOTEBOOK.get()),
    ) {
        val block = ModRegistry.Blocks.NOTEBOOK.get()
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        helper.setBlock(pos, block)
        block.setPlacedBy(
            helper.level,
            helper.absolutePos(pos),
            helper.getBlockState(pos),
            player,
            stack,
        )
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun placingComputerCreatesComputerBlockEntity(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        placeComputer(helper, pos)

        helper.runAfterDelay(1L) {
            helper.assertTrue(
                ComputerGameTestEnvironment.serverComputerId(helper.level, absolutePos) > 0,
                "Expected computer block to allocate a server computer id",
            )
            helper.succeed()
        }
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun tickingComputerRegistersServerComputer(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        placeComputer(helper, pos)

        helper.runAfterDelay(5L) {
            helper.assertTrue(
                ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, absolutePos),
                "Expected placed computer block to register a server computer after ticking",
            )
            helper.succeed()
        }
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun sdkModuleBayControlsColdBootMountAndInvalidatesSnapshot(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        placeComputer(helper, pos)

        helper.runAfterDelay(5L) {
            val notebook = ComputerGameTestEnvironment.notebookAt(helper.level, absolutePos)
            val module = ItemStack(Items.PAPER).apply { sdkArtifactIdentity = SDK_FIXTURE_IDENTITY }
            helper.assertTrue(
                notebook.sdkModuleBay.setFromPlayer(module),
                "Expected powered-off notebook to accept the known SDK fixture module",
            )

            val device = notebook.getOrCreateRuntimeDevice()
            device.turnOn()
            waitForSavedTerminalSnapshot(helper, notebook, "initial shell prompt with SDK module") { terminal ->
                terminal.contains("K16> ")
            }
            dispatchText(device, "cat /sdk/fixture.txt\n")
            waitForSavedTerminalSnapshot(helper, notebook, "SDK fixture contents") { terminal ->
                commandResultPresent(terminal, "cat /sdk/fixture.txt", "sdk fixture")
            }

            val computerId = requireNotNull(notebook.computerID)
            val storage0Path = ComputerGameTestEnvironment.storage0Path(helper.level, computerId)
            val storage0DigestBefore = sha256(Files.readAllBytes(storage0Path))
            val snapshotPath = ComputerGameTestEnvironment.runtimeSnapshotPath(helper.level, computerId)
            val snapshotBackupPath = snapshotPath.resolveSibling("${snapshotPath.fileName}.bak")
            helper.assertTrue(Files.isRegularFile(snapshotPath), "Expected running notebook snapshot at $snapshotPath")
            helper.assertTrue(
                Files.isRegularFile(snapshotBackupPath),
                "Expected repeated running snapshot saves to preserve a backup at $snapshotBackupPath",
            )

            helper.assertTrue(
                notebook.sdkModuleBay.removeItemNoUpdate(0).isEmpty,
                "Expected SDK module removal to be rejected while the VM is running",
            )
            helper.assertTrue(
                notebook.sdkModuleBay.installedArtifactIdentity == SDK_FIXTURE_IDENTITY,
                "Expected rejected removal to preserve the installed SDK module",
            )

            device.shutdown()
            val removedModule = notebook.sdkModuleBay.removeItemNoUpdate(0)
            helper.assertTrue(
                removedModule.sdkArtifactIdentity == SDK_FIXTURE_IDENTITY,
                "Expected powered-off notebook to return the installed SDK module",
            )
            helper.assertTrue(
                notebook.sdkModuleBay.installedArtifactIdentity == null,
                "Expected powered-off SDK module bay to become empty",
            )
            helper.assertFalse(Files.exists(snapshotPath), "Expected hardware mutation to delete $snapshotPath")
            helper.assertFalse(Files.exists(snapshotBackupPath), "Expected hardware mutation to delete $snapshotBackupPath")

            device.turnOn()
            waitForSavedTerminalSnapshot(helper, notebook, "fresh shell prompt without SDK module") { terminal ->
                terminal.contains("K16> ")
            }
            dispatchText(device, "cat /sdk/fixture.txt\n")
            waitForSavedTerminalSnapshot(helper, notebook, "missing SDK mount after module removal") { terminal ->
                commandResultPresent(
                    terminal,
                    "cat /sdk/fixture.txt",
                    "cat: open failed: /sdk/fixture.txt",
                )
            }
            device.shutdown()

            helper.assertTrue(
                sha256(Files.readAllBytes(storage0Path)).contentEquals(storage0DigestBefore),
                "Expected SDK module removal and cold restart not to replace or mutate storage0",
            )
            helper.succeed()
        }
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun runtimeSnapshotResumeFailsClosedAfterBlockEntityReload(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        placeComputer(helper, pos)

        helper.runAfterDelay(5L) {
            val computer = ComputerGameTestEnvironment.computerAt(helper.level, absolutePos)
            computer.getOrCreateRuntimeDevice().turnOn()
        }

        helper.runAfterDelay(25L) {
            val originalComputer = ComputerGameTestEnvironment.computerAt(helper.level, absolutePos)
            val expectedId =
                requireNotNull(originalComputer.computerID) {
                    "Expected placed computer block entity to keep an allocated id"
                }
            waitForSavedTerminalSnapshot(helper, originalComputer, "initial shell prompt") { terminal ->
                terminal.contains("K16> ")
            }

            val savedTag = originalComputer.saveWithFullMetadata(helper.level.registryAccess())
            val savedSnapshot = savedTag.runtimeSnapshot

            helper.assertTrue(
                savedSnapshot != null && savedSnapshot.isNotEmpty(),
                "Expected live K16 runtime snapshot to be saved into block entity NBT",
            )

            helper.level.removeBlockEntity(absolutePos)
            helper.assertFalse(
                ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, absolutePos),
                "Expected removing the block entity to release the registered runtime device",
            )

            val restoredComputer =
                requireNotNull(
                    BlockEntity.loadStatic(
                        absolutePos,
                        helper.level.getBlockState(absolutePos),
                        savedTag,
                        helper.level.registryAccess(),
                    ) as? AbstractComputerBlockEntity,
                ) {
                    "Expected saved computer NBT to load an AbstractComputerBlockEntity"
                }
            helper.level.setBlockEntity(restoredComputer)

            val reloadedComputer = ComputerGameTestEnvironment.computerAt(helper.level, absolutePos)
            helper.assertTrue(
                reloadedComputer.computerID == expectedId,
                "Expected reloaded computer to preserve id $expectedId, actual ${reloadedComputer.computerID}",
            )

            val reloadedDevice = reloadedComputer.getOrCreateRuntimeDevice()
            reloadedDevice.turnOn()
            val failure = (reloadedDevice as RuntimeDeviceFailureState).runtimeFailureMessage
            helper.assertTrue(
                ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, absolutePos),
                "Expected reloaded computer to recreate a registered runtime device",
            )
            helper.assertFalse(
                reloadedDevice.isOn,
                "Expected display-less K16SNAP v1 resume to leave the runtime powered off",
            )
            helper.assertTrue(
                failure?.contains("cannot preserve retained gpu0 state") == true,
                "Expected explicit retained gpu0 snapshot failure, actual: $failure",
            )
            helper.succeed()
        }
    }

    @Suppress("DEPRECATION")
    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun creativeDestroyDropsComputerItemWithIdAndLabel(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        val expectedId = 42
        val expectedLabel = "Atlas"
        val creativePlayer = helper.makeMockServerPlayerInLevel()
        val sourceStack = (ModRegistry.Items.NOTEBOOK.get() as NotebookItem).create(expectedId, expectedLabel)

        creativePlayer.abilities.instabuild = true

        placeComputer(helper, pos, sourceStack)

        helper.runAfterDelay(3L) {
            helper.assertBlockEntityData<AbstractComputerBlockEntity>(pos, {
                helper.assertTrue(
                    it.computerID == expectedId,
                    "Entity: expected placed computer block entity to have computer id $expectedId, actual ${it.computerID}",
                )
                helper.assertTrue(
                    it.label == expectedLabel,
                    "Entity: expected placed computer block entity to have computer label $expectedLabel, actual ${it.label}",
                )
                true
            }, { "" })
        }

        helper.runAfterDelay(5L) {
            helper.assertTrue(
                creativePlayer.gameMode.destroyBlock(absolutePos),
                "Expected creative mock player to destroy the placed computer block",
            )
        }

        helper.runAfterDelay(7L) {
            helper.assertItemEntityPresent(ModRegistry.Items.NOTEBOOK.get(), pos, 2.0)

            val droppedStack =
                helper
                    .getEntities(EntityType.ITEM, pos, 2.0)
                    .single { it.item.`is`(ModRegistry.Items.NOTEBOOK.get()) }
                    .item

            with(droppedStack.computerDataTagCopy()!!) {
                helper.assertTrue(
                    computerID == expectedId,
                    "Expected creative drop to preserve computer id $expectedId, actual $computerID",
                )
                helper.assertTrue(
                    computerLabel == expectedLabel,
                    "Expected creative drop to preserve computer label $expectedLabel, actual $computerLabel",
                )
            }
            helper.succeed()
        }
    }

    private fun waitForSavedTerminalSnapshot(
        helper: GameTestHelper,
        computer: AbstractComputerBlockEntity,
        description: String,
        predicate: (String) -> Boolean,
    ): ByteArray {
        var lastTerminal = "<no snapshot>"
        repeat(80) {
            computer.serverTick()
            val snapshot = computer.saveWithFullMetadata(helper.level.registryAccess()).runtimeSnapshot
            if (snapshot != null) {
                val terminal = terminalText(snapshot)
                if (predicate(terminal)) return snapshot
                lastTerminal = terminal
            }
            Thread.sleep(10)
        }
        error("Expected $description in K16 terminal snapshot; terminal: $lastTerminal")
    }

    private fun dispatchText(
        device: RuntimeDevice,
        text: String,
    ) {
        for (byte in text.encodeToByteArray()) {
            DeviceEvents.dispatch(device, KeyInputEvent.Character(byte))
        }
    }

    private fun commandResultPresent(
        terminal: String,
        command: String,
        expectedOutput: String,
    ): Boolean {
        val commandIndex = terminal.lastIndexOf("K16> $command")
        val outputIndex = terminal.indexOf(expectedOutput, startIndex = commandIndex + command.length)
        val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex + expectedOutput.length)
        return commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
    }

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(snapshot, start = K16_TERMINAL_CELLS_ADDR, size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS)
            .map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

private const val SDK_FIXTURE_IDENTITY = "sdk_fixture_v1"
private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
