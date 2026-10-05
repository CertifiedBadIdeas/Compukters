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

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DebugChunkAreaTrackerTest {
    record Owned(String computer, DebugChunkAreaTracker.Chunk chunk) {}
    static final class Sink implements DebugChunkAreaTracker.Tickets {
        final Set<Owned> active = new HashSet<>();
        int refreshed;
        int removed;
        public void refresh(String computer, DebugChunkAreaTracker.Chunk chunk) { active.add(new Owned(computer, chunk)); refreshed++; }
        public void remove(String computer, DebugChunkAreaTracker.Chunk chunk) { assertTrue(active.remove(new Owned(computer, chunk))); removed++; }
    }

    @Test void movedComputerReleasesOldAreaAndKeepsTheOverlap() {
        var sink = new Sink();
        var tracker = new DebugChunkAreaTracker(sink);
        tracker.update(Map.of("a", new DebugChunkAreaTracker.Chunk(-1, 0)));
        assertEquals(9, sink.active.size());
        assertTrue(sink.active.contains(new Owned("a", new DebugChunkAreaTracker.Chunk(-2, -1))));
        tracker.update(Map.of("a", new DebugChunkAreaTracker.Chunk(0, 0)));
        assertEquals(9, sink.active.size());
        assertEquals(3, sink.removed);
        assertFalse(sink.active.contains(new Owned("a", new DebugChunkAreaTracker.Chunk(-2, -1))));
        tracker.close();
        assertTrue(sink.active.isEmpty());
    }

    @Test void overlappingComputersOwnIndependentTickets() {
        var sink = new Sink();
        var tracker = new DebugChunkAreaTracker(sink);
        var center = new DebugChunkAreaTracker.Chunk(1, 1);
        tracker.update(Map.of("a", center, "b", center));
        assertEquals(18, sink.active.size());
        tracker.update(Map.of("b", center));
        assertEquals(9, sink.active.size());
        assertTrue(sink.active.stream().allMatch(ticket -> ticket.computer().equals("b")));
        tracker.update(Map.of());
        assertTrue(sink.active.isEmpty());
    }

    @Test void unchangedAreaRefreshesExpiryAndCloseIsIdempotent() {
        var sink = new Sink();
        var tracker = new DebugChunkAreaTracker(sink);
        var center = Map.of("a", new DebugChunkAreaTracker.Chunk(0, 0));
        tracker.update(center);
        tracker.update(center);
        assertEquals(18, sink.refreshed);
        assertEquals(0, sink.removed);
        tracker.close();
        tracker.close();
        assertEquals(9, sink.removed);
    }
}
