# Create drive control panel

Requires Compukters with `compukter:core` 7.3.0 and the Create addon on Minecraft 1.21.1.
Open this project in the IDE, build it and deploy the executable to a computer.

1. Build a flat 3×3 display, all panels facing the same way. Use the configurator to assemble it and name the screen `scada`. The program selects HIGH density (384×384 pixels).
2. Build a real Create kinetic installation with a Rotation Speed Controller, Speedometer and Stressometer. Put the gauges on the controlled shaft/network and supply the controller through Create's normal gearing. The program does not create mechanical connections.
3. Bind the computer, screen and three Create devices to one peripheral network using the configurator. Name the devices `drive_control`, `drive_speed` and `drive_stress` respectively.
4. Run the program. Empty-hand right-click the front of the screen to adjust the target by 16 RPM, request zero RPM, or resume the last nonzero target observed during this run (32 RPM before any nonzero reading). Shift-click retains ordinary building behavior.

The panel reads live RPM, stress/capacity and the controller's accepted target every five game ticks. A schematic distinguishes requested rotation from observed shaft movement. The last 96 samples form the speed chart, with a five-tick pause between updates; missing telemetry breaks the line. Load at or above 90% raises an alarm. Device names and the alarm threshold are constants at the top of `src/main.kt`.

Controls are disabled if a device is unavailable or a reading is invalid. Handles are reacquired after failure, and queued commands are discarded while disconnected and on the first recovered sample. Command errors appear on the panel. The controller returns its accepted target; observed RPM is still measured independently. STOP requests a zero target and does not disable an upstream motor. No command is sent merely because the program starts or stops.

Stop the running program with **Ctrl+C** in the terminal. The last image remains on the screen, but clicks cannot execute commands after its writer lease is released. This example samples synchronously; it does not detect a loaded but mechanically disconnected gauge beyond the values Create reports.

To verify compilation against a local real-addon bundle after staging the workspace SDK:

```sh
# From addons/create:
./gradlew-sandbox-dev-parallel-summary assembleCompuktersAddon
# From the Compukters workspace, supply the absolute bundle path:
./gradlew-sandbox-dev-parallel-summary :compiler-k2:compileCreateScadaExample -PcompuktersCreateGuestApiBundle=/absolute/path/addons/create/build/compukters-addon/compukters-create.cagb
```

Run the addon build and parent checks sequentially: the addon composite build also uses the parent's build outputs.


The dashboard builds its `displayUi` tree once. `at` preserves explicit pixel placement and a `row`
arranges the command buttons. Construction contracts initialize non-null widget `val` references through nested groups. Telemetry updates widget properties, then one admitted click is dispatched
before drawing. Button handlers record command intent; the controller command remains outside the frame.
