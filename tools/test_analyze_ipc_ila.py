#!/usr/bin/env python3
import csv
import tempfile
import unittest
from pathlib import Path

from analyze_ipc_ila import read_capture, summarize


class IpcIlaTests(unittest.TestCase):
    def write_capture(self, directory, rows):
        path = Path(directory) / "capture.csv"
        with path.open("w", newline="") as out:
            writer = csv.writer(out)
            writer.writerow(["Sample in Buffer", "Sample in Window", "TRIGGER",
                             "pc0[31:0]", "v0[64:64]", "pc1[63:32]", "v1[65:65]",
                             "rob[88:66]", "dispatch[94:89]"])
            writer.writerow(["Radix - UNSIGNED", "UNSIGNED", "UNSIGNED"] + ["HEX"] * 6)
            writer.writerows(rows)
        return path

    def test_counts_and_gap_boundaries(self):
        rows = [
            [0, 0, 1, "100", 1, "102", 0, "2002", "10"],
            [1, 1, 0, "bad", 0, "bad", 0, "80", "4"],
            [2, 2, 0, "bad", 0, "bad", 0, "80", "4"],
            [3, 3, 0, "104", 1, "106", 1, "4004", "8"],
        ]
        with tempfile.TemporaryDirectory() as directory:
            counts, pcs, gaps = summarize(read_capture(self.write_capture(directory, rows)))
        self.assertEqual(counts["macros"], 3)
        self.assertEqual(counts["head_store"], 2)
        self.assertEqual(counts["dispatch_blocked_iq"], 2)
        self.assertNotIn(0xbad, pcs)  # invalid PCs are never interpreted
        self.assertEqual(gaps, [(2, 1, 2, 0x100, 0x104)])

    def test_bad_samples_fail_closed(self):
        for row in (
            [0, 0, 0, "100", 1, "102", 0, "2", "10"],  # macro/event mismatch
            [0, 0, 0, "100", 0, "102", 0, "3", "10"],  # two ROB buckets
            [0, 0, 0, "100", 0, "102", 0, "1", "0"],   # no dispatch bucket
            [1, 1, 0, "100", 0, "102", 0, "1", "1"],   # missing sample
            [0, 0, 0, "100", 0, "102", 0, "800001", "1"],  # wrong schema
        ):
            with self.subTest(row=row), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    read_capture(self.write_capture(directory, [row]))

    def test_empty_file_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                read_capture(self.write_capture(directory, []))

    def test_truncated_gap_has_no_invented_endpoint(self):
        _, pcs, gaps = summarize([((), 0x80, 4)] * 3)
        self.assertFalse(pcs)
        self.assertEqual(gaps, [(3, 0, 2, None, None)])


if __name__ == "__main__":
    unittest.main()
