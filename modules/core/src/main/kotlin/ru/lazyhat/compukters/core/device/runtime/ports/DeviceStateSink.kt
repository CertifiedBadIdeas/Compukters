package ru.lazyhat.compukters.core.device.runtime.ports

/** Sink notified when a runtime device's on/off power state changes.
 *  Block-side carriers translate this to their blockstate property; other carriers
 *  (e.g. item-resident devices) may persist it differently or ignore it. */
fun interface DeviceStateSink {
    fun onPowerStateChanged(isOn: Boolean)
}
