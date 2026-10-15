The `compukters:empty` structure declares a 16 × 80 × 16 test area. It contains one air block at
(0, 0, 0), an empty entities list, and DataVersion 3955 (the older supported Minecraft target).
The height includes the peripheral range checks at relative Y=72; the width includes transfers at X=11.

Both target registrations must use this shared structure. The vanilla `bastion/mobs/empty` template is
only one block wide, so GameTest can place adjacent scenarios six blocks apart and allow their devices
to overlap. Correct structure bounds also let GameTest clear all devices between scenarios.
