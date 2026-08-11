# VM Profiling

The RV32 VM, its benchmark runners, input workloads, contract scripts, and
historical benchmark evidence are owned by
[Compukter-VM](https://github.com/CertifiedBadIdeas/Compukter-VM).

Initialize the pinned `host/compukter-vm` submodule before running the parent
verification suite:

```bash
git submodule update --init --recursive
./gradlew-sandbox-dev-parallel verifyLocalFull
```

Run standalone VM profiling and the optional C/QEMU comparison from a clone of
Compukter-VM, following its [profiling guide](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/docs/PROFILING.md).
