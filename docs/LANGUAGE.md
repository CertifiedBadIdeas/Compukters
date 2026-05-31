# CKL — retired

The Compukter Kraft Language (CKL) and its in-game IDE/Workbench were removed
as part of the Rux MVP cutover (issue #26). The mod no longer ships a
player-facing high-level language. The remaining Rux surface is the temporary
`.rx` language frontend: `rux compile` lowers source into Kraft16 artifacts.
Machine and artifact tooling lives under `k16`: disassembly, inspect, volume,
filesystem, runtime-helper, link, and run commands.

If you need the previous CKL design notes, see the git history of this file.
