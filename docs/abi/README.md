# ABI Specifications

This directory contains stable runtime and binary interface contracts.

Design notes and implementation plans may explain how an ABI was chosen, but the files here are the canonical references for external tools and compiler frontends.

Current specifications:

- `QUICKSTART.md`: practical first steps for external image producers.
- `rux-low-image-v1.md`: frozen Rux low image ABI v1.
- `rux-low-image-v1-opcodes.json`: machine-readable opcode table for Rux low image ABI v1.
- `rux-machine-profile-v1.md`: baseline machine profile for running Rux low images on a computer-class VM.
- `rux-machine-profile-v2.md`: draft optional-hardware machine profile with boot info, configurable page size, and hardware table discovery.
- `rux-low-errors-v1.md`: stable decode, validation, and runtime error categories for tooling.
- `cpp-frontend-notes.md`: advisory lowering notes for external C++ frontends.
- `PRE-FREEZE-GAPS.md`: reviewed v1 instruction-set gaps and decisions.
- `FREEZE-CHECKLIST.md`: gates and commands required before freezing v1.

Conformance material:

- `fixtures/*.ruxi`: golden and negative Rux low image fixtures.
- `fixtures/*.json`: machine-readable fixture manifests.
- `fixtures/README.md`: fixture execution notes, including `./rux run --memory` behavior.
- `CHANGELOG.md`: ABI history and freeze notes.
