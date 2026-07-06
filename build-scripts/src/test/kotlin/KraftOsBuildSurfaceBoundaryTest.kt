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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class KraftOsBuildSurfaceBoundaryTest {
    private val root = Path.of("..").toAbsolutePath().normalize()
    private val producerScript = root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts")
    private val consumerScript = root.resolve("build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts")

    @Test
    fun kraftOsProducerOwnsGuestSourceTreeAndProductionBundle() {
        val producer = producerScript.readText()

        assertTrue(producer.contains("guest/kraftos"))
        assertTrue(producer.contains("guest/firmware"))
        assertTrue(producer.contains("val assembleKraftOsProductionBundle ="))
        assertTrue(producer.contains("generated/kraftos-bundles"))
        assertTrue(producer.contains("firmware/k16-bios.kflash"))
        assertTrue(producer.contains("firmware/k16-system-storage0.kv"))
        assertTrue(producer.contains("firmware/kraftos-artifacts.properties"))
    }

    @Test
    fun modConsumerDoesNotDependOnKraftOsSourceTreeLayout() {
        val consumer = consumerScript.readText()

        assertTrue(consumer.contains("id(\"k16-firmware-producer-convention\")"))
        assertTrue(consumer.contains("assembleKraftOsProductionBundle"))
        assertTrue(consumer.contains("putK16DevelopmentStorage0TestPrograms"))
        assertTrue(consumer.contains("sourceSets.getByName(\"main\")"))
        assertFalse(consumer.contains("guest/kraftos"))
        assertFalse(consumer.contains("guest/firmware"))
    }
}
