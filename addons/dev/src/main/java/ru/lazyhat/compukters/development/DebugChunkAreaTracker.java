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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Temporary, independently owned tickets; every refresh renews the expiry time. */
public final class DebugChunkAreaTracker {
    public record Chunk(int x, int z) {}
    interface Tickets {
        void refresh(String computer, Chunk chunk);
        void remove(String computer, Chunk chunk);
    }

    private final Tickets tickets;
    private final Map<String, Set<Chunk>> areas = new HashMap<>();

    DebugChunkAreaTracker(Tickets tickets) {
        this.tickets = tickets;
    }

    public void update(Map<String, Chunk> computers) {
        var next = new HashMap<String, Set<Chunk>>();
        computers.forEach((computer, center) -> {
            var chunks = new HashSet<Chunk>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) chunks.add(new Chunk(center.x() + dx, center.z() + dz));
            }
            next.put(computer, chunks);
        });
        areas.forEach((computer, chunks) -> {
            var retained = next.getOrDefault(computer, Set.of());
            chunks.stream().filter(chunk -> !retained.contains(chunk)).forEach(chunk -> tickets.remove(computer, chunk));
        });
        next.forEach((computer, chunks) -> chunks.forEach(chunk -> tickets.refresh(computer, chunk)));
        areas.clear();
        areas.putAll(next);
    }

    public void close() {
        update(Map.of());
    }
}
