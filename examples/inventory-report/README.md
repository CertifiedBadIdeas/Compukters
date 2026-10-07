# Inventory report

This Guest Kotlin project aggregates delivered batches into a stock table and reports items below their
configured minimum. It demonstrates `Pair`/`to`, `mapOf`, `mutableMapOf`, `mutableSetOf`, destructuring and
`getOrPut`. The input batches are local sample data, so the example runs without addons or peripherals.

Open this directory using the IDE's **Open directory** action, or copy `src/main.kt` into an existing Guest
project. Compile and run it on a computer using a platform that provides `stdlib:core` 1.11.0 or newer.

Expected output:

```text
iron: 14 / 12 (ready)
copper: 3 / 8 (low)
gold: 2 / 0 (ready)
Distinct items: 3
```

Change `minimum` to configure stock thresholds and `deliveries` to provide different batches. Repeated
items add to the same total; a missing total starts at zero through `getOrPut`. The set counts each item
once. Items without a configured minimum use zero. The report chooses its row order explicitly because
hash-table iteration order is unspecified.

The checked-in source is compiled by `MinimalScriptLoweringTest`, test
`hash collection inventory example compiles and executes`, and executed by the pinned Rust VM through
`testKotlinHashCollectionsVmConformance`. Its output is checked across bounded instruction slices.
