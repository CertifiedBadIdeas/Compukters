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

import ru.lazyhat.compukters.development.DebugChunkAreaTracker;
import ru.lazyhat.compukters.development.DebugComputerChunkLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Map;

@PrefixGameTestTemplate(false)
public final class DebugChunkLoadingGameTests {
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", batch = "dev_chunk_tickets", timeoutTicks = 100000)
    public static void temporaryTicketsLoadTheComputerAreaWithoutPersistingForceFlags(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(new BlockPos(0, 0, 0)));
        var center = new DebugChunkAreaTracker.Chunk(origin.x + 32, origin.z + 32);
        var tracker = DebugComputerChunkLoader.newTracker(level);
        helper.succeedWhen(() -> {
            tracker.update(Map.of("gametest-computer", center));
            // The unpaced GameTest server skips ordinary managed inter-tick waiting.
            // Give asynchronous chunk completions their owner-thread delivery opportunity.
            for (int task = 0; task < 8 && level.getChunkSource().pollTask(); task++) {}
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = center.x() + dx;
                    int z = center.z() + dz;
                    helper.assertTrue(level.getChunkSource().getChunkNow(x, z) != null, "temporary ticket did not load " + x + "," + z);
                    helper.assertTrue(!level.getForcedChunks().contains(ChunkPos.asLong(x, z)), "temporary ticket persisted a vanilla force-load flag");
                }
            }
            tracker.close();
        });
    }
}
