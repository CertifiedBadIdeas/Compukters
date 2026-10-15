/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import boiler.control.BoilerControl
import boiler.control.BoilerSample
import boiler.control.Channel
import boiler.control.FuelMode
import boiler.control.Outputs
import boiler.control.Phase
import boiler.control.PowerSource
import boiler.control.outputSteps
import boiler.control.unavailableSample
import compukter.concurrent.Tasks
import compukter.display.Colors
import compukter.display.DisplayHistory
import compukter.display.DisplayMode
import compukter.display.GraphicalDisplay
import compukter.display.UiButton
import compukter.display.UiGauge
import compukter.display.UiIndicator
import compukter.display.UiText
import compukter.display.displayUi
import compukter.display.frame
import compukter.redstone.Redstone
import compukter.redstone.RedstonePort
import create.actuators.Clutch
import create.actuators.Pump
import create.boiler.Boiler
import create.kinetics.Speedometer
import create.kinetics.Stressometer
import createaddition.boiler.BurnerStatus
import createaddition.boiler.LiquidBurner
import kotlin.collections.ArrayList
import kotlin.collections.emptyList
import kotlin.math.Rect2i
import kotlin.math.Vector2i
import kotlin.math.abs
import kotlin.math.by
import kotlin.math.max
import kotlin.math.xy

const val SCREEN = "boiler_scada"
const val BOILER = "boiler"
const val LOAD_STRESS = "boiler_load_stress"
const val LOAD_CLUTCH = "boiler_load_clutch"
const val ENGINE_SPEED = "boiler_engine_speed"
const val BATTERY_DISCHARGE = "boiler_battery_discharge"
const val BATTERY_CLUTCH = "boiler_battery_clutch"
const val STEAM_CLUTCH = "boiler_steam_clutch"
const val FUEL_CLUTCH = "boiler_fuel_clutch"
const val FUEL_PUMP = "boiler_fuel_pump"

// Explicit names keep this installation separate from other boilers on the same network.
fun configuredBurners(): Array<String> =
    arrayOf(
        "boiler_burner_1",
        "boiler_burner_2",
        "boiler_burner_3",
        "boiler_burner_4",
        "boiler_burner_5",
        "boiler_burner_6",
        "boiler_burner_7",
        "boiler_burner_8",
        "boiler_burner_9",
    )

value class Telemetry(
    val sequence: Int,
    val sample: BoilerSample,
    val burners: List<BurnerStatus>,
)

fun area(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): Rect2i = Rect2i(Vector2i(x, y), Vector2i(width, height))

fun finite(value: Float): Boolean = value >= -Float.MAX_VALUE && value <= Float.MAX_VALUE

fun main() {
    runDashboard()
}

fun runDashboard(iterations: Int = -1) {
    val burnerNames = configuredBurners()
    require(burnerNames.size in 1..9)
    val screen = GraphicalDisplay.named(SCREEN)
    screen.setMode(DisplayMode.HIGH)
    require(screen.width >= 384 && screen.height >= 384) { "Assemble at least a 3x3 screen" }
    val control = BoilerControl()
    val history = DisplayHistory(96)
    val burnerLabels = ArrayList<UiIndicator>()
    var telemetry = Telemetry(0, unavailableSample(), emptyList())
    var actuators: ControlDevices? = null
    var running = true
    // A cooperative clock lets a stuck sampler invalidate old readings independently of I/O.
    var clock = 0
    var observedAt = 0
    var applying = false
    var revision = 0
    var requested = ""
    var applied = Outputs(PowerSource.NONE, FuelMode.OFF, false)
    val phaseText: UiIndicator
    val waterText: UiText
    val heatText: UiText
    val engineText: UiText
    val battery: UiIndicator
    val load: UiIndicator
    val pumps: UiIndicator
    val boiler: UiIndicator
    val engine: UiIndicator
    val fuelText: UiText
    val fuelGauge: UiGauge
    val alarm: UiIndicator
    val coolingText: UiText
    val start: UiButton
    val stop: UiButton
    val reset: UiButton
    val ui =
        displayUi(area(0, 0, 384, 384)) {
            at(12 xy 10) {
                text("BOILER SCADA", 360 by 24) {
                    scale = 2
                    color = 0xDAEAF4
                }
            }
            at(12 xy 42) { phaseText = indicator("NO TELEMETRY", 360 by 14) }
            at(12 xy 62) { waterText = text("Water: --", 360 by 14) }
            at(12 xy 80) { heatText = text("Heat: --", 360 by 14) }
            at(12 xy 98) { engineText = text("Engine: --", 360 by 14) }
            at(12 xy 122) { battery = indicator("BATTERY", 100 by 14) }
            at(138 xy 122) { pumps = indicator("PUMPS", 100 by 14) }
            at(260 xy 122) { boiler = indicator("BOILER", 112 by 14) }
            at(260 xy 145) { engine = indicator("ENGINE", 112 by 14) }
            at(12 xy 145) { load = indicator("LOAD: --", 235 by 14) }
            at(12 xy 168) { fuelText = text("Liquid fuel: --", 360 by 14) }
            at(12 xy 186) { fuelGauge = gauge(360 by 8) }
            for (index in 0 until burnerNames.size) {
                at((12 + index % 3 * 120) xy (202 + index / 3 * 14)) {
                    burnerLabels.add(indicator("B${index + 1}: --", 116 by 12))
                }
            }
            at(12 xy 248) { text("Boiler level history (0..18)", 360 by 14) }
            at(12 xy 268) {
                chart(360 by 36, history) {
                    minimum = 0f
                    maximum = 18f
                }
            }
            at(12 xy 314) {
                row(spacing = 8) {
                    start =
                        button("START", 112 by 26) {
                            enabled = false
                            onClick = { requested = "start" }
                        }
                    stop =
                        button("STOP / DRAIN", 112 by 26) {
                            enabled = false
                            onClick = { requested = "stop" }
                        }
                    reset =
                        button("RESET", 112 by 26) {
                            enabled = false
                            onClick = { requested = "reset" }
                        }
                }
            }
            at(12 xy 348) { alarm = indicator("NO TELEMETRY", 360 by 14) }
            at(12 xy 366) { coolingText = text("Burning fuel: --", 360 by 14) }
        }
    val ticker =
        Tasks.launch {
            while (running) {
                Tasks.sleepTicks(1)
                clock++
            }
        }
    val sampler =
        Tasks.launch {
            var boilerDevice: Boiler? = null
            var speedDevice: Speedometer? = null
            var stressDevice: Stressometer? = null
            val burners = ArrayList<LiquidBurner>()
            while (running) {
                while (running && applying) Tasks.sleepTicks(1)
                if (!running) break
                val sampleRevision = revision
                var sample = unavailableSample()
                val statuses = ArrayList<BurnerStatus>()
                try {
                    val output = actuators ?: ControlDevices()
                    actuators = output
                    output.validate() // Revalidate every output lease without issuing a command.
                    val source = boilerDevice ?: Boiler.named(BOILER)
                    val gauge = speedDevice ?: Speedometer.named(ENGINE_SPEED)
                    val stressometer = stressDevice ?: Stressometer.named(LOAD_STRESS)
                    stressDevice = stressometer
                    boilerDevice = source
                    speedDevice = gauge
                    if (burners.isEmpty()) for (name in burnerNames) burners.add(LiquidBurner.named(name))
                    val water = source.waterSupply()
                    val waterLevel = source.waterLevel()
                    val heat = source.heatLevel()
                    val level = source.level()
                    val rpm = gauge.speed()
                    val stress = stressometer.stress()
                    val capacityReading = stressometer.capacity()
                    require(finite(stress) && stress >= 0f && finite(capacityReading) && capacityReading >= 0f)
                    require(finite(water) && water >= 0f && finite(rpm) && waterLevel in 0..18 && heat in 0..18 && level in 0..18)
                    var fuel = 0
                    var capacity = 0
                    var burnTicks = 0
                    for (burnerDevice in burners) {
                        val status = burnerDevice.status()
                        require(status.fuelCapacity > 0 && status.fuelCapacity <= 1_000_000 && status.fuelAmount in 0..status.fuelCapacity)
                        statuses.add(status)
                        fuel += status.fuelAmount
                        capacity += status.fuelCapacity
                        burnTicks = max(burnTicks, status.burnTicks)
                    }
                    sample = BoilerSample(true, water, waterLevel, heat, level, rpm, fuel, capacity, burnTicks, stress, capacityReading)
                } catch (error: Exception) {
                    boilerDevice = null
                    speedDevice = null
                    stressDevice = null
                    burners.clear()
                    val failedDevices = actuators
                    actuators = null
                    try {
                        failedDevices?.close()
                    } catch (ignored: Exception) {
                    }
                }
                // Discard observations begun before source/pump switching.
                if (!applying && sampleRevision == revision) {
                    telemetry = Telemetry(telemetry.sequence + 1, sample, statuses)
                    observedAt = clock
                }
                Tasks.sleepTicks(5)
            }
        }
    var controlTick = clock
    var sequence = -1
    var iteration = 0
    var previouslyAvailable = false
    try {
        while (iterations < 0 || iteration < iterations) {
            val previousAvailability = previouslyAvailable
            val observation = telemetry
            var dirty = iteration == 0 || observation.sequence != sequence
            val fresh = clock - observedAt <= 80
            val sample = if (fresh) observation.sample else unavailableSample()
            if (observation.sequence != sequence || (!fresh && previouslyAvailable)) {
                sequence = observation.sequence
                val observedTick = clock
                control.observe(sample, observedTick - controlTick)
                controlTick = observedTick
                history.add(if (sample.available) sample.level.toFloat() else Float.NaN)
            }
            val available = sample.available && actuators != null
            start.enabled = available && control.canStart
            stop.enabled = available && control.phase != Phase.DRAINING && control.phase != Phase.COOLING
            reset.enabled =
                available && control.phase == Phase.FAULT && sample.fuelAmount == 0 && sample.heatLevel == 0 && sample.burnTicks == 0
            // Flush clicks on disconnect/recovery. Current button state rejects stale START/RESET clicks.
            val received = screen.pollClick()
            val click = if (available && previouslyAvailable) received else null
            if (!available || !previouslyAvailable) drainClicks(screen)
            previouslyAvailable = available
            requested = ""
            ui.dispatch(click)
            when (requested) {
                "start" -> control.start(sample)
                "stop" -> control.stop(sample)
                "reset" -> control.reset(sample)
            }
            dirty = dirty || requested.isNotEmpty() || available != previousAvailability
            val next = control.outputs
            if (next != applied) {
                dirty = true
                applying = true
                revision++
                try {
                    val output = actuators ?: error("Control devices are unavailable")
                    for (step in outputSteps(applied, next)) {
                        if (step.channel == Channel.WAIT) {
                            Tasks.sleepTicks(step.value)
                        } else {
                            if (step.value > 0) require(telemetry.sample.available && clock - observedAt <= 80)
                            output.set(step.channel, step.value)
                        }
                    }
                    applied = next
                } catch (error: Exception) {
                    control.fail("OUTPUT COMMAND FAILED")
                    try {
                        actuators?.close()
                    } catch (ignored: Exception) {
                    }
                    actuators = null
                    applied = Outputs(PowerSource.NONE, FuelMode.OFF, false)
                } finally {
                    applying = false
                }
                if (control.phase == Phase.FAULT) {
                    previouslyAvailable = false
                    drainClicks(screen)
                }
            }
            start.enabled = available && actuators != null && control.canStart
            stop.enabled = available && actuators != null && control.phase != Phase.DRAINING && control.phase != Phase.COOLING
            reset.enabled =
                available && actuators != null && control.phase == Phase.FAULT && sample.fuelAmount == 0 && sample.heatLevel == 0 &&
                sample.burnTicks == 0
            phaseText.label = if (available) "PHASE: ${control.phase.name}" else "NO TELEMETRY"
            phaseText.color = if (available && control.phase != Phase.FAULT) Colors.CYAN else Colors.RED
            waterText.text = if (available) "Water: ${sample.waterSupply.toInt()} mB/t  level ${sample.waterLevel}" else "Water: --"
            heatText.text = if (available) "Heat: ${sample.heatLevel}  boiler level: ${sample.level}" else "Heat: --"
            engineText.text = if (available) "Engine only: ${sample.engineRpm.toInt()} rpm" else "Engine only: --"
            battery.label =
                when (applied.power) {
                    PowerSource.BATTERY -> "DISCHARGE"
                    PowerSource.STEAM -> "CHARGE"
                    else -> "ISOLATED"
                }
            battery.color =
                if (applied.power ==
                    PowerSource.BATTERY
                ) {
                    Colors.YELLOW
                } else if (applied.power == PowerSource.STEAM) {
                    Colors.GREEN
                } else {
                    0x687D8C
                }
            load.label =
                if (available) {
                    "LOAD ${if (applied.load) "ON" else "OFF"}: ${sample.loadStress.toInt()}/${sample.loadCapacity.toInt()} SU"
                } else {
                    "LOAD: --"
                }
            load.color =
                if (!applied.load) {
                    0x687D8C
                } else if (sample.loadCapacity > 0f &&
                    sample.loadStress > sample.loadCapacity
                ) {
                    Colors.RED
                } else {
                    Colors.GREEN
                }
            pumps.label =
                when (applied.fuel) {
                    FuelMode.FEED -> "FUEL FEED"
                    FuelMode.DRAIN -> "FUEL DRAIN"
                    else -> "PUMPS OFF"
                }
            pumps.color = if (applied.fuel == FuelMode.OFF) 0x687D8C else Colors.CYAN
            boiler.color = if (available && sample.heatLevel > 0) Colors.YELLOW else 0x687D8C
            engine.color = if (available && abs(sample.engineRpm) >= 8f) Colors.GREEN else 0x687D8C
            fuelText.text = if (available) "Liquid fuel: ${sample.fuelAmount}/${sample.fuelCapacity} mB" else "Liquid fuel: --"
            fuelGauge.value = if (available) sample.fuelAmount.toFloat() else Float.NaN
            fuelGauge.maximum = if (available) sample.fuelCapacity.toFloat() else 1f
            for (index in 0 until burnerLabels.size) {
                val status = if (available) observation.burners[index] else null
                burnerLabels[index].label = if (status == null) "B${index + 1}: --" else "B${index + 1}: ${status.fuelAmount}"
                burnerLabels[index].color =
                    when {
                        status == null -> Colors.RED
                        status.fuelAmount > 0 -> Colors.GREEN
                        status.burnTicks > 0 -> Colors.YELLOW
                        else -> 0x687D8C
                    }
            }
            alarm.label =
                if (control.alarm.isNotEmpty()) {
                    control.alarm
                } else if (!available) {
                    "TELEMETRY UNAVAILABLE"
                } else if (control.warning.isNotEmpty()) {
                    control.warning
                } else {
                    "STATUS: NORMAL"
                }
            alarm.color =
                if (control.alarm.isNotEmpty() ||
                    !available
                ) {
                    Colors.RED
                } else if (control.warning.isNotEmpty()) {
                    Colors.YELLOW
                } else {
                    Colors.GREEN
                }
            coolingText.text = if (available) "Burning fuel: up to ${sample.burnTicks / 20}s left" else "Burning fuel: --"
            if (dirty) {
                screen.frame {
                    clear(0x101820)
                    ui.draw(this)
                }
            }
            iteration++
            Tasks.sleepTicks(1)
        }
    } finally {
        running = false
        try {
            sampler.join()
        } finally {
            try {
                ticker.join()
            } finally {
                actuators?.close()
            }
        }
    }
}

fun clutchNameFor(channel: Channel): String =
    when (channel) {
        Channel.BATTERY_CLUTCH -> BATTERY_CLUTCH
        Channel.STEAM_CLUTCH -> STEAM_CLUTCH
        Channel.FUEL_CLUTCH -> FUEL_CLUTCH
        Channel.LOAD_CLUTCH -> LOAD_CLUTCH
        else -> error("Channel has no clutch")
    }

/** Track each acquired device before the next lookup, so partial acquisition can be shut down. */
class ControlDevices {
    private val clutches = arrayOfNulls<Clutch>(Channel.entries.size)
    private var battery: RedstonePort? = null
    private var fuel: Pump? = null
    private var closed = false

    fun validate() {
        check(!closed)
        for (channel in Channel.entries) {
            if (channel == Channel.WAIT || channel == Channel.BATTERY || channel == Channel.FUEL_REVERSED) continue
            val device = clutches[channel.ordinal] ?: Clutch.named(clutchNameFor(channel))
            clutches[channel.ordinal] = device
            device.connected()
            check(!closed)
        }
        val pump = fuel ?: Pump.named(FUEL_PUMP)
        fuel = pump
        pump.reversed()
        val port = battery ?: RedstonePort.named(BATTERY_DISCHARGE)
        battery = port
        port.front.get()
        check(!closed)
    }

    fun set(
        channel: Channel,
        value: Int,
    ) {
        check(!closed)
        when (channel) {
            Channel.BATTERY -> {
                val port = battery ?: error("Battery port is unavailable")
                port.set(value, Redstone.Power.WEAK, RedstonePort.Side.ALL)
            }

            Channel.FUEL_REVERSED -> {
                val pump = fuel ?: error("Fuel pump is unavailable")
                check(pump.setReversed(value != 0) == (value != 0))
            }

            else -> {
                val clutch = clutches[channel.ordinal] ?: error("Clutch is unavailable")
                check(clutch.setConnected(value != 0) == (value != 0))
            }
        }
    }

    fun close() {
        closed = true
        // Disconnect fuel first; attempt every other shutdown even if one device has disappeared.
        for (channel in arrayOf(Channel.FUEL_CLUTCH, Channel.LOAD_CLUTCH, Channel.STEAM_CLUTCH, Channel.BATTERY_CLUTCH)) {
            val clutch = clutches[channel.ordinal] ?: continue
            clutches[channel.ordinal] = null
            try {
                clutch.setConnected(false)
            } catch (ignored: Exception) {
            }
        }
        val port = battery
        battery = null
        fuel = null
        try {
            port?.close()
        } catch (ignored: Exception) {
        }
    }
}

fun drainClicks(screen: GraphicalDisplay) {
    var discarded = 0
    while (discarded < 64 && screen.pollClick() != null) discarded++
}
