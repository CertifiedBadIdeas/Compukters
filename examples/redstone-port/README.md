# Remote redstone relay

Place a Redstone Port, bind it and the computer to the same peripheral network with the Peripheral Configurator,
and name the port `relay`. Connect an input to its front and the controlled device to its right.

Open this project in the IDE, resolve dependencies and build it, then run the resulting program from the shell.
Input at level 7 or higher turns on direct output at level 15; returning input to zero turns it off. The faces
follow the port orientation. The acquiring program owns the port exclusively, including its input reads.
Stopping the program or disconnecting/unloading the port clears all outputs. No addon is needed.
