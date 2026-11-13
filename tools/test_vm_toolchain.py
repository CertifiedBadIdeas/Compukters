#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("vm_toolchain", Path(__file__).with_name("vm-toolchain.py"))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class VmToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "checkout"
        self.root.mkdir()
        self.tools = self.root / ".toolchain/vm-toolchain"
        self.input = self.root / "workloads"
        self.input.mkdir()
        (self.input / "manifest.tsv").write_text("id\tchecksum\nexample\t7\n")
        (self.input / "example.cpkt").write_bytes(b"artifact")
        self.binary = self.tools / "bin/timing"
        self.binary.parent.mkdir(parents=True)
        self.binary.write_bytes(b"prepared runner")
        self.descriptor = self.binary.with_suffix(".json")
        self.descriptor.write_text(json.dumps({"runner": "timing", "sha256": tool.sha256(self.binary)}))
        self.output = Path(self.temporary.name) / "result"
        self.root_patch = patch.object(tool, "ROOT", self.root)
        self.tools_patch = patch.object(tool, "TOOLS", self.tools)
        self.root_patch.start()
        self.tools_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.addCleanup(self.tools_patch.stop)

    def arguments(self, *extra):
        return tool.parser().parse_args(["run", "--inputs", str(self.input), "--output", str(self.output), *extra])

    def test_run_uses_only_prepared_binary_and_records_identities(self):
        with patch.object(tool, "git_identity", return_value={"commit": "test", "dirty": False}), \
             patch.object(tool.subprocess, "run") as run:
            tool.run(self.arguments("--filter", "example"))
        command = run.call_args.args[0]
        self.assertEqual(str(self.binary), command[0])
        self.assertEqual("example", command[-1])
        self.assertEqual(1, run.call_count)
        record = json.loads((self.output / "toolchain-identity.json").read_text())
        self.assertEqual(tool.sha256(self.binary), record["runner"]["sha256"])
        self.assertEqual(tool.sha256(self.input / "example.cpkt"), record["inputs"]["example.cpkt"])

    def test_changed_binary_is_rejected_before_execution(self):
        self.binary.write_bytes(b"different")
        with patch.object(tool.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "does not match"):
                tool.run(self.arguments())
        self.assertFalse(self.output.exists())
        run.assert_not_called()

    def test_results_cannot_overwrite_or_live_in_checkout(self):
        with self.assertRaisesRegex(ValueError, "outside"):
            tool.result_directory(self.root / "reports/run")
        other = Path(self.temporary.name) / "another-checkout"
        other.mkdir()
        (other / ".git").mkdir()
        (other / ".git/HEAD").write_text("ref: refs/heads/main\n")
        with self.assertRaisesRegex(ValueError, "outside"):
            tool.result_directory(other / "reports/run")
        self.output.mkdir()
        with self.assertRaises(FileExistsError):
            tool.result_directory(self.output)

    def test_invalid_run_does_not_create_a_result(self):
        with self.assertRaisesRegex(ValueError, "odd"):
            tool.run(self.arguments("--samples", "2"))
        self.assertFalse(self.output.exists())

    def test_instrumented_binary_is_not_used_for_timing(self):
        memory = self.tools / "bin/memory"
        memory.write_bytes(b"memory runner")
        memory.with_suffix(".json").write_text(json.dumps({"runner": "memory", "sha256": tool.sha256(memory)}))
        with self.assertRaisesRegex(ValueError, "timing runner"):
            tool.run(self.arguments("--runner", "memory"))
        self.assertFalse(self.output.exists())

    def test_prepare_builds_only_the_selected_runner(self):
        def build(command, **kwargs):
            binary = Path(kwargs["env"]["CARGO_TARGET_DIR"]) / "release/guest-benchmark"
            binary.parent.mkdir(parents=True)
            binary.write_bytes(b"new runner")

        with patch.object(tool, "VM", self.root), \
             patch.object(tool, "git_identity", return_value={"commit": "test", "dirty": False}), \
             patch.object(tool.subprocess, "run", side_effect=build) as run:
            tool.prepare(["timing"], [])
        self.assertEqual(1, run.call_count)
        self.assertEqual("guest-benchmark,baseline-jit", run.call_args.args[0][-1])
        self.assertEqual(tool.sha256(self.binary), json.loads(self.descriptor.read_text())["sha256"])


if __name__ == "__main__":
    unittest.main()
