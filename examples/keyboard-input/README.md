# In-world keyboard input

Run `src/main.kt` on a Compukters computer. Hold RMB with the Peripheral Configurator, move to
Keyboard and release. Short-click the running computer to activate control, then put the tool away.
Selected keys reach the Guest program while mouse look remains available. A top-of-screen indicator
shows capture and the Escape exit key. Escape or opening a GUI releases control.

The observer records W press/release edges in its terminal and reports connection once per second.
To reconnect, use the same configurator action again. It requests only W/A/S/D, Space and Shift;
other keys keep normal behavior. This requires the matching base platform with Keyboard support;
no addon or keyboard block is needed.
