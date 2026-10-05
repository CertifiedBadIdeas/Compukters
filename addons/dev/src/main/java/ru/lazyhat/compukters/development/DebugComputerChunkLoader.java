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

package ru.lazyhat.compukters.development;

import com.mojang.brigadier.Command;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static net.minecraft.commands.Commands.literal;

/** Flight diagnostics owned solely by the non-distributable addon dev stand. */
public final class DebugComputerChunkLoader {
    private static final ResourceLocation COMPUTER = ResourceLocation.fromNamespaceAndPath("compukters", "compukter");
    private static final TicketType<String> TICKET = TicketType.create("compukters_dev_computer", Comparator.<String>naturalOrder(), 40);
    private final Map<ServerLevel, LevelState> levels = new IdentityHashMap<>();
    private MinecraftServer server;
    private boolean enabled;

    void register(IEventBus bus) {
        bus.addListener(this::start);
        bus.addListener(this::stop);
        bus.addListener(this::load);
        bus.addListener(this::unload);
        bus.addListener(this::tick);
        bus.addListener(this::commands);
    }

    private void start(ServerAboutToStartEvent event) {
        server = event.getServer();
        enabled = System.getProperty("neoforge.enabledGameTestNamespaces") == null;
    }

    private void stop(ServerStoppingEvent event) {
        if (event.getServer() != server) return;
        enabled = false;
        levels.values().forEach(state -> state.tracker.close());
        levels.clear();
        server = null;
    }

    private void load(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        level.getServer().execute(() -> {
            if (level.getServer() == server) state(level).chunks.add(chunk);
        });
    }

    private void unload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        level.getServer().execute(() -> {
            if (level.getServer() == server) state(level).chunks.remove(chunk);
        });
    }

    private LevelState state(ServerLevel level) {
        return levels.computeIfAbsent(level, key -> new LevelState(newTracker(key)));
    }

    private void tick(LevelTickEvent.Post event) {
        if (!enabled || !(event.getLevel() instanceof ServerLevel level) || level.getServer() != server || level.getGameTime() % 10 != 0) return;
        var state = state(level);
        var computers = new HashMap<String, DebugChunkAreaTracker.Chunk>();
        var container = SubLevelContainer.getContainer(level);
        for (var chunk : Set.copyOf(state.chunks)) {
            for (var entity : chunk.getBlockEntities().values()) {
                if (!computer(entity) || (container != null && container.inBounds(entity.getBlockPos()))) continue;
                add(computers, "world/" + entity.getBlockPos().asLong(), Vec3.atCenterOf(entity.getBlockPos()));
            }
        }
        if (container != null) {
            for (var candidate : container.getAllSubLevels()) {
                if (!(candidate instanceof ServerSubLevel body) || body.isRemoved()) continue;
                for (var holder : body.getPlot().getLoadedChunks()) {
                    var chunk = holder.getChunk();
                    if (chunk == null) continue;
                    for (var entity : chunk.getBlockEntities().values()) {
                        if (!computer(entity)) continue;
                        var position = body.logicalPose().transformPosition(Vec3.atCenterOf(entity.getBlockPos()));
                        add(computers, body.getUniqueId() + "/" + entity.getBlockPos().asLong(), position);
                    }
                }
            }
        }
        state.tracker.update(computers);
    }

    private static boolean computer(BlockEntity entity) {
        return !entity.isRemoved() && COMPUTER.equals(BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()));
    }

    private static void add(Map<String, DebugChunkAreaTracker.Chunk> computers, String id, Vec3 position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.z) || Math.abs(position.x) >= 30_000_000 || Math.abs(position.z) >= 30_000_000) return;
        computers.put(id, new DebugChunkAreaTracker.Chunk((int) Math.floor(position.x / 16.0), (int) Math.floor(position.z / 16.0)));
    }

    public static DebugChunkAreaTracker newTracker(ServerLevel level) {
        return new DebugChunkAreaTracker(new DebugChunkAreaTracker.Tickets() {
            @Override
            public void refresh(String computer, DebugChunkAreaTracker.Chunk chunk) {
                level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(chunk.x(), chunk.z()), 2, computer, true);
            }

            @Override
            public void remove(String computer, DebugChunkAreaTracker.Chunk chunk) {
                level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(chunk.x(), chunk.z()), 2, computer, true);
            }
        });
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("compuktersdev").requires(source -> source.hasPermission(2))
            .then(literal("forcechunks")
                .then(literal("on").executes(context -> {
                    enabled = true;
                    context.getSource().sendSuccess(() -> Component.literal("Computer chunk loading enabled (3x3, follows constructions)."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("off").executes(context -> {
                    enabled = false;
                    levels.values().forEach(state -> state.tracker.close());
                    context.getSource().sendSuccess(() -> Component.literal("Computer chunk loading disabled; temporary tickets released."), false);
                    return Command.SINGLE_SUCCESS;
                }))));
    }

    private static final class LevelState {
        final Set<LevelChunk> chunks = new HashSet<>();
        final DebugChunkAreaTracker tracker;

        LevelState(DebugChunkAreaTracker tracker) {
            this.tracker = tracker;
        }
    }
}
