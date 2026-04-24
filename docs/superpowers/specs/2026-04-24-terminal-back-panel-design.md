# Terminal Back Panel Design

## Goal

Add a real back-side texture for the pocket terminal item by replacing the current flat `minecraft:item/generated` setup with a simple custom 3D item model.

## Current State

- The item model is defined in `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json`.
- It currently inherits from `minecraft:item/generated` and uses a single `layer0` texture.
- Because of that, the item has no true separate back face.

## Design Summary

The terminal should become a thin, pocket-sized 3D item with three texture roles:

- `front`: existing screen-and-keypad face
- `back`: new minimal rear panel
- `side`: narrow casing edge material

The geometry should stay intentionally simple: one thin box is enough unless rendering quality clearly requires a second element.

## Visual Direction

The back panel should be minimalist rather than highly technical or toy-like.

Required visual cues:

- slightly lighter central rear cover area
- two small screw details placed sparsely so the back reads as a service panel
- subtle bottom branding or marking in a single line
- darker side casing than the front face

Explicitly out of scope:

- dense greebles or vents
- large warning stickers
- battery-bay heavy detail
- extra rear buttons or ports unless the model later needs them for silhouette reasons

## File-Level Changes

- Replace the current generated item model with a custom JSON model using `elements`
- Split the existing texture into a dedicated front texture file
- Add a new back texture file
- Add a side texture file if a flat reused color is not sufficient inside the model JSON

## Rendering Expectations

- The item must remain readable in GUI scale and hotbar scale
- The front face should still read as the primary side
- The back face must be visibly different from the front when rotated in-hand or as a dropped item
- Item transforms may need adjustment after the first in-game preview

## Risks And Constraints

- At 16x16 resolution, too many rear details will become noise
- Overly thick geometry will undermine the pocket-terminal feel
- If side shading is too high-contrast, the item may look chunkier than intended

## Verification

Verify the asset in at least these views:

- GUI/inventory
- first-person or third-person held view
- dropped item entity

Success criteria:

- the back panel is clearly distinct from the front
- the terminal still reads cleanly at small scale
- the front remains the dominant visual identity