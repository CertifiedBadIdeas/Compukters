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

package ru.lazyhat.compukters.development.gametest;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState;
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry;
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey;
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction;
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState;
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Characterizes current upstream lifetimes; does not implement or claim execution hibernation. */
@PrefixGameTestTemplate(false)
public final class HibernationLifecycleGameTests {
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", batch = "hibernation_lifecycle", timeoutTicks = 100000)
    public static void sablePlotComputersOutliveOrdinaryChunkAndPhysicsPrecedesColdBoot(GameTestHelper helper) {
        var fixture = new SableFixture(helper);
        cleanupAfterTest(helper, fixture::close);
        var sequence = helper.startSequence();
        sequence.thenWaitUntil(fixture::awaitWorldChunks);
        sequence.thenExecute(fixture::assemble);
        sequence.thenWaitUntil(fixture::awaitComputers);
        sequence.thenExecute(fixture::releaseWorldTickets);
        sequence.thenWaitUntil(fixture::awaitOrdinaryUnload);
        sequence.thenExecute(() -> fixture.echo("loaded-plot"));
        sequence.thenWaitUntil(() -> fixture.awaitEcho("loaded-plot"));
        sequence.thenExecute(fixture::unloadConstruction);
        sequence.thenWaitUntil(fixture::awaitConstructionUnload);
        sequence.thenExecute(fixture::reloadConstruction);
        sequence.thenWaitUntil(fixture::awaitComputers);
        sequence.thenWaitUntil(fixture::awaitRestorePhysics);
        sequence.thenExecute(() -> fixture.echo("reloaded-plot"));
        sequence.thenWaitUntil(() -> fixture.awaitEcho("reloaded-plot"));
        sequence.thenExecute(fixture::report);
        sequence.thenExecute(fixture::releaseConstructionTicket);
        sequence.thenWaitUntil(fixture::awaitConstructionUnload);
        sequence.thenExecute(fixture::requestNaturalReload);
        sequence.thenWaitUntil(fixture::awaitNaturalReload);
        sequence.thenWaitUntil(fixture::awaitComputers);
        sequence.thenExecute(() -> fixture.echo("natural-reload"));
        sequence.thenWaitUntil(() -> fixture.awaitEcho("natural-reload"));
        sequence.thenExecute(() -> LogUtils.getLogger().info("Hibernation lifecycle: Sable naturally unloaded after ticket release and reloaded with its world chunks"));
        sequence.thenSucceed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", batch = "hibernation_lifecycle", timeoutTicks = 100)
    public static void createBearingCapturesComputerDataWithoutMovingExecution(GameTestHelper helper) throws Exception {
        var bearing = helper.absolutePos(new BlockPos(2, 2, 2));
        var computer = bearing.above();
        var level = helper.getLevel();
        level.setBlockAndUpdate(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState());
        level.setBlockAndUpdate(computer, CompuktersRegistry.INSTANCE.getCOMPUTER().get().defaultBlockState());
        var original = (ComputerBlockEntity) level.getBlockEntity(computer);
        var contraption = new BearingContraption(false, Direction.UP);
        helper.assertTrue(contraption.assemble(level, bearing), "bearing refused to assemble the computer");
        var captured = contraption.getBlocks().get(BlockPos.ZERO);
        helper.assertTrue(captured != null && captured.nbt() != null, "bearing did not capture computer NBT");
        helper.assertTrue(MovementBehaviour.REGISTRY.get(captured.state()) == null, "computer now has a moving behavior");
        helper.assertTrue(contraption.getActors().stream().noneMatch(actor -> actor.left.state().getBlock() == captured.state().getBlock()),
            "bearing registered a computer actor despite its missing movement behavior");
        helper.assertTrue(level.getBlockEntity(computer) == original, "capture alone unexpectedly moved the source computer");
        LogUtils.getLogger().info("Hibernation lifecycle: Create bearing captured computer NBT but registered no computer movement actor; Sable installed");
        helper.succeed();
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", batch = "hibernation_lifecycle", timeoutTicks = 10000)
    public static void createTrainAdvancesWithoutCarriageEntityWithSableInstalled(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(BlockPos.ZERO).offset(4096, 0, 4096);
        var graph = new TrackGraph();
        var start = new DiscoveredLocation(level, new Vec3(origin.getX(), origin.getY(), origin.getZ()))
            .withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
        var end = new DiscoveredLocation(level, new Vec3(origin.getX() + 256, origin.getY(), origin.getZ()))
            .withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
        graph.createNodeIfAbsent(start);
        graph.createNodeIfAbsent(end);
        graph.connectNodes(level, start, end, null);
        var node1 = graph.locateNode(start);
        var node2 = graph.locateNode(end);
        var edge = graph.getConnectionsFrom(node1).get(node2);
        var front = new TravellingPoint(node1, node2, edge, 32, false);
        var back = new TravellingPoint(node1, node2, edge, 30, false);
        var bogey = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, new CompoundTag(), front, back);
        var carriage = new Carriage(bogey, null, 0);
        var train = new Train(UUID.randomUUID(), UUID.randomUUID(), graph, List.of(carriage), List.of(), false, 0);
        Create.RAILWAYS.trackNetworks.put(graph.id, graph);
        Create.RAILWAYS.addTrain(train);
        cleanupAfterTest(helper, () -> {
            Create.RAILWAYS.removeTrain(train.id);
            Create.RAILWAYS.trackNetworks.remove(graph.id);
        });
        helper.assertTrue(!level.isPositionEntityTicking(origin), "train fixture must begin outside entity-ticking chunks");
        // A real serialized carriage is needed, but it must not be instantiated in the unloaded world.
        var contraption = new CarriageContraption(Direction.EAST);
        contraption.anchor = BlockPos.ZERO;
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 1);
        carriage.setContraption(level, contraption);
        double initial = front.position;
        long until = helper.getTick() + 20;
        helper.onEachTick(() -> {
            train.speed = 0.25;
            train.manualTick = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= until, "waiting for railway-manager turns");
            helper.assertTrue(front.position > initial + 1, "global railway manager did not advance the unloaded train");
            helper.assertTrue(carriage.anyAvailableEntity() == null, "unloaded carriage unexpectedly has a live entity");
            helper.assertTrue(!level.isPositionEntityTicking(BlockPos.containing(front.getPosition(graph))), "train moved into loaded chunks");
            helper.assertTrue(MovementBehaviour.REGISTRY.get(CompuktersRegistry.INSTANCE.getCOMPUTER().get().defaultBlockState()) == null,
                "computer gained a moving behavior; revise the mounted-computer characterization");
            LogUtils.getLogger().info("Hibernation lifecycle: Create train advanced {} blocks with no carriage entity; Sable installed", front.position - initial);
        });
    }

    private static final class SableFixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ServerSubLevelContainer container;
        private final BlockPos anchor;
        private final BlockPos ordinaryPosition;
        private final List<ChunkPos> worldTickets = new ArrayList<>();
        private final Consumer<ForgeSablePrePhysicsTickEvent> physicsListener;
        private ServerSubLevel body;
        private PhysicsConstraintHandle pin;
        private ComputerBlockEntity ordinary;
        private List<ComputerBlockEntity> computers;
        private List<Long> epochs;
        private SubLevelData saved;
        private boolean observingRestore;
        private int restoreSubsteps;
        private int restoreSubstepsBeforeActor;
        private int restoreSubstepsBeforeReady;
        private List<CompletableFuture<Boolean>> commands;
        private List<CompletableFuture<TerminalState>> terminals;

        SableFixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            container = SubLevelContainer.getContainer(level);
            anchor = helper.absolutePos(BlockPos.ZERO).offset(2048, 80, 2048);
            ordinaryPosition = anchor.offset(512, 0, 512);
            var center = new ChunkPos(anchor);
            for (int x = center.x - 1; x <= center.x + 5; x++) {
                for (int z = center.z - 1; z <= center.z + 1; z++) worldTickets.add(new ChunkPos(x, z));
            }
            worldTickets.add(new ChunkPos(ordinaryPosition));
            worldTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, true));
            physicsListener = event -> {
                if (!observingRestore || event.getPhysicsSystem() != container.physicsSystem() || body == null || body.isRemoved()) return;
                restoreSubsteps++;
                if (computers == null || computers.stream().anyMatch(computer -> computer.getTerminalMachineId() == null)) restoreSubstepsBeforeActor++;
                if (computers == null || computers.stream().anyMatch(computer -> !(computer.getRuntimeState() instanceof ProgramComputerState.WaitingForInput))) restoreSubstepsBeforeReady++;
            };
            NeoForge.EVENT_BUS.addListener(physicsListener);
        }

        void awaitWorldChunks() {
            pollChunks();
            for (var chunk : worldTickets) {
                helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null, "fixture chunk is still loading: " + chunk);
            }
            helper.assertTrue(level.isPositionEntityTicking(anchor), "fixture has not entered ticking status");
        }

        void assemble() {
            var positions = new ArrayList<BlockPos>();
            for (int x = 0; x <= 64; x++) {
                var pos = anchor.east(x);
                level.setBlockAndUpdate(pos, x == 0 || x == 64
                    ? CompuktersRegistry.INSTANCE.getCOMPUTER().get().defaultBlockState() : Blocks.STONE.defaultBlockState());
                positions.add(pos);
            }
            level.setBlockAndUpdate(ordinaryPosition, CompuktersRegistry.INSTANCE.getCOMPUTER().get().defaultBlockState());
            ordinary = (ComputerBlockEntity) level.getBlockEntity(ordinaryPosition);
            body = SubLevelAssemblyHelper.assembleBlocks(level, anchor, positions,
                new BoundingBox3i(anchor.getX(), anchor.getY(), anchor.getZ(), anchor.getX() + 65, anchor.getY() + 1, anchor.getZ() + 1));
            container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            pinBody();
            findComputers();
        }

        void pinBody() {
            // Fixture restraint prevents a long boot from dropping below world bounds. It is NOT an activation barrier.
            var local = body.getPlot().getCenterBlock();
            pin = container.physicsSystem().getPipeline().addConstraint(body, null,
                new FixedConstraintConfiguration(new Vector3d(local.getX(), local.getY(), local.getZ()),
                    new Vector3d(anchor.getX(), anchor.getY(), anchor.getZ()), new Quaterniond()));
        }

        void findComputers() {
            var local = body.getPlot().getCenterBlock();
            computers = List.of((ComputerBlockEntity) level.getBlockEntity(local), (ComputerBlockEntity) level.getBlockEntity(local.east(64)));
        }

        void awaitComputers() {
            pollChunks();
            for (var computer : computers) {
                helper.assertTrue(computer.getTerminalMachineId() != null, "plot computer has not booted automatically");
                helper.assertTrue(computer.getRuntimeState() instanceof ProgramComputerState.WaitingForInput, "plot shell is not ready: " + computer.getRuntimeState());
            }
            if (!observingRestore) helper.assertTrue(ordinary.getRuntimeState() instanceof ProgramComputerState.WaitingForInput,
                "ordinary control has not booted; ticking=" + level.isPositionEntityTicking(ordinaryPosition) + "; state=" + ordinary.getRuntimeState());
            if (epochs == null) epochs = computers.stream().map(ComputerBlockEntity::getTerminalMachineId).toList();
        }

        void releaseWorldTickets() {
            helper.assertTrue(ordinary.getTerminalMachineId() != null, "ordinary control computer never booted");
            worldTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
        }

        void awaitOrdinaryUnload() {
            pollChunks();
            var chunk = new ChunkPos(ordinaryPosition);
            helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "ordinary control chunk has not unloaded");
            helper.assertTrue(ordinary.isRemoved() && ordinary.getTerminalMachineId() == null, "ordinary computer survived actual chunk unload");
            helper.assertTrue(!body.isRemoved(), "force-loaded construction unexpectedly unloaded");
            for (int i = 0; i < computers.size(); i++) {
                helper.assertTrue(!computers.get(i).isRemoved(), "off-center plot computer unloaded");
                helper.assertTrue(epochs.get(i).equals(computers.get(i).getTerminalMachineId()), "loaded construction replaced its computer runtime");
            }
            for (var source : worldTickets) helper.assertTrue(!level.getForcedChunks().contains(source.toLong()), "fixture retained a vanilla force-load flag");
        }

        void echo(String marker) {
            terminals = null;
            commands = computers.stream().map(computer -> computer.submitTerminalTextAsync("echo " + marker)
                .thenCompose(accepted -> {
                    if (!accepted) throw new IllegalStateException("computer rejected echo text");
                    return computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS, java.util.Set.of());
                })).toList();
        }

        void awaitEcho(String marker) {
            for (var command : commands) helper.assertTrue(command.isDone() && command.join(), "echo input is pending/rejected");
            if (terminals == null) terminals = computers.stream().map(ComputerBlockEntity::terminalFullStateAsync).toList();
            for (var terminal : terminals) helper.assertTrue(terminal.isDone(), "terminal observation is pending");
            var observed = terminals;
            terminals = null;
            for (var terminal : observed) {
                var state = terminal.join();
                helper.assertTrue(state != null, "computer has no terminal");
                boolean foundOutput = false;
                for (int y = 0; y < state.getHeight(); y++) {
                    var row = new StringBuilder();
                    for (int x = 0; x < state.getWidth(); x++) row.appendCodePoint(state.getCells().get(y * state.getWidth() + x).getCodePoint());
                    // Require the output row, not the echoed input line containing "echo <marker>".
                    foundOutput |= row.toString().stripTrailing().equals(marker);
                }
                helper.assertTrue(foundOutput, "shell did not execute echo after world ticket release/reload");
            }
        }

        void unloadConstruction() {
            saved = SubLevelSerializer.toData(body, List.of());
            pin.remove();
            pin = null;
            container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            container.removeSubLevel(body, SubLevelRemovalReason.UNLOADED);
        }

        void awaitConstructionUnload() {
            for (var computer : computers) helper.assertTrue(computer.isRemoved() && computer.getTerminalMachineId() == null, "construction unload did not close plot computer");
            helper.assertTrue(container.getSubLevel(body.getUniqueId()) == null, "unloaded construction remains registered");
        }

        void releaseConstructionTicket() {
            observingRestore = false;
            pin.remove();
            pin = null;
            container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        }

        void requestNaturalReload() {
            worldTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, true));
        }

        void awaitNaturalReload() {
            pollChunks();
            var restored = container.getSubLevel(body.getUniqueId());
            helper.assertTrue(restored instanceof ServerSubLevel && restored != body, "holding-chunk manager has not restored the construction");
            body = (ServerSubLevel) restored;
            container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            findComputers();
            pinBody();
            observingRestore = true;
        }

        void reloadConstruction() {
            body = SubLevelSerializer.fullyLoad(level, saved);
            container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            findComputers();
            helper.assertTrue(computers.stream().allMatch(computer -> computer.getTerminalMachineId() == null), "restored block entity unexpectedly has a runtime already");
            helper.assertTrue(container.physicsSystem().getPhysicsHandle(body).isValid(), "restored body has no physics handle before computer boot");
            observingRestore = true;
            pinBody();
        }

        void awaitRestorePhysics() {
            helper.assertTrue(!container.physicsSystem().getPaused(), "physics is paused by another fixture");
            helper.assertTrue(restoreSubsteps > 0, "no real physics substep was observed after plot reload");
            for (int i = 0; i < computers.size(); i++) helper.assertTrue(!epochs.get(i).equals(computers.get(i).getTerminalMachineId()), "current implementation unexpectedly retained execution epoch");
        }

        void report() {
            LogUtils.getLogger().info("Hibernation lifecycle: ordinary chunk unloaded; two plot computers retained epochs; reload physics handle preceded actors; {} substeps, {} before actor attachment, {} before shell ready", restoreSubsteps, restoreSubstepsBeforeActor, restoreSubstepsBeforeReady);
        }

        void pollChunks() {
            for (int task = 0; task < 8 && level.getChunkSource().pollTask(); task++) {}
        }

        void close() {
            observingRestore = false;
            NeoForge.EVENT_BUS.unregister(physicsListener);
            if (pin != null && pin.isValid()) pin.remove();
            if (body != null && container.getSubLevel(body.getUniqueId()) == body) {
                container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            }
            worldTickets.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
        }
    }

    private static void cleanupAfterTest(GameTestHelper helper, Runnable cleanup) {
        helper.testInfo.addListener(new GameTestListener() {
            private boolean cleaned;
            private void finish() { if (!cleaned) { cleaned = true; cleanup.run(); } }
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { finish(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { finish(); }
            @Override public void testAddedForRerun(GameTestInfo info, GameTestInfo rerun, GameTestRunner runner) { finish(); }
        });
    }
}
