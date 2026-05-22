# CKL — retired

The Compukter Kraft Language (CKL) and its in-game IDE/Workbench were removed
as part of the Rux MVP cutover (issue #26). The mod no longer ships a
player-facing high-level language: all computers boot a precompiled
`rux-laptop.ruxi` image produced by the Rust toolchain in
`native/rux-compiler/` and executed by `native/rux-vm/`.

If you need the previous CKL design notes, see the git history of this file.
