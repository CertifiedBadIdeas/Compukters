# CKL — retired

The Compukter Kraft Language (CKL) and its in-game IDE/Workbench were removed
as part of the Rux MVP cutover (issue #26). The mod no longer ships a
player-facing high-level language. Current runtime work uses Rux16 binary
artifacts: `rux compile` produces raw guest code, `rux disasm --target ...`
prints readable instruction output, `k16 volume` prepares storage0 `.kv`
media, and filesystem-specific tooling lives under `rux fs`.

If you need the previous CKL design notes, see the git history of this file.
