# Cube with a rotation slider

Assemble and connect a 3x3 screen. Resolve Dependencies and Run this Guest project.
The UI occupies an inset pixel rectangle and draws the projected cube through a local, clipped canvas.
Hold right-click with an empty hand and move the crosshair along the slider to rotate around a fixed tilted axis.
Release right-click to end the drag. Leaving the display cancels it; release and press again to start another gesture.
Ctrl+C stops the program. The tree and handlers are built once; input dispatch and frame publication are explicit.

The rotation slider is retained as a non-null `val`, initialized through the nested UI construction contracts. The render loop reads its current `value` after input dispatch.
