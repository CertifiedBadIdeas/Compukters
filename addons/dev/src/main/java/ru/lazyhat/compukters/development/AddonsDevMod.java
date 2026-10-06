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

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

@Mod("compukters_addons_dev")
public final class AddonsDevMod {
    public AddonsDevMod(IEventBus modBus) {
        new DebugComputerChunkLoader().register(NeoForge.EVENT_BUS);
        modBus.addListener(this::registerGameTests);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        try {
            event.register(Class.forName("ru.lazyhat.compukters.development.gametest.DebugChunkLoadingGameTests"));
            event.register(Class.forName("ru.lazyhat.compukters.development.gametest.HibernationLifecycleGameTests"));
        } catch (ClassNotFoundException ignored) {
            if (System.getProperty("neoforge.enabledGameTestNamespaces") != null) {
                throw new IllegalStateException("Development chunk-loading GameTest sources are missing", ignored);
            }
            // Test sources are absent from ordinary development runs.
        }
    }
}
