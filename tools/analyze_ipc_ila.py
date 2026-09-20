#!/usr/bin/env python3
"""Read-only analysis of the 23+6-event, two-retirement-lane IPC ILA CSV.

Each file is an independent consecutive-clock window. Never concatenate gaps
across captures or label short ILA samples as whole-workload performance.
"""
import argparse
import csv
from collections import Counter
from pathlib import Path

ROB_NAMES = (
    "empty retire_one_uop retire_two_uops stall_recovery stall_halt stall_exception "
    "head_load head_store head_branch head_complex head_other ready_serial retire_no_macro "
    "retire_one_macro retire_two_macros slot1_absent slot1_incomplete slot1_alone slot1_other "
    "mispred_return mispred_other head_mispred_wait rob_full"
).split()
DISPATCH_NAMES = "no_input blocked_rob blocked_iq accept_one accept_two blocked_both".split()
SUFFIXES = ("[31:0]", "[64:64]", "[63:32]", "[65:65]", "[88:66]", "[94:89]")


def read_capture(path):
    with Path(path).open(newline="") as source:
        reader = csv.DictReader(source)
        columns = []
        for suffix in SUFFIXES:
            matches = [name for name in reader.fieldnames if name.endswith(suffix)]
            if len(matches) != 1:
                raise ValueError(f"{path}: expected one column ending in {suffix}, got {matches}")
            columns.append(matches[0])
        rows = []
        for raw in reader:
            if raw["Sample in Buffer"].startswith("Radix"):
                if any(raw[column] != "HEX" for column in columns):
                    raise ValueError(f"{path}: expected HEX probe columns")
                continue
            index = int(raw["Sample in Buffer"])
            if index != len(rows) or int(raw["Sample in Window"]) != index:
                raise ValueError(f"{path}: nonconsecutive or multiple capture windows at {index}")
            p0, v0, p1, v1, rob, dispatch = (int(raw[column], 16) for column in columns)
            if v0 not in (0, 1) or v1 not in (0, 1) or rob >> 23 or dispatch >> 6:
                raise ValueError(f"{path}: incompatible probe width at {index}")
            if (rob & 0xfff).bit_count() != 1 or (dispatch & 0x1f).bit_count() != 1:
                raise ValueError(f"{path}: event partition is not one-hot at {index}")
            macros = v0 + v1
            if macros != ((rob >> 13) & 1) + 2 * ((rob >> 14) & 1):
                raise ValueError(f"{path}: retirement probes/event timing disagree at {index}")
            rows.append((tuple(pc for pc, valid in ((p0, v0), (p1, v1)) if valid), rob, dispatch))
        if not rows:
            raise ValueError(f"{path}: empty capture")
        return rows


def summarize(rows):
    counts = Counter()
    pcs = Counter()
    gaps = []
    start = None
    preceding = None
    for cycle, (retired, rob, dispatch) in enumerate(rows):
        counts["cycles"] += 1
        counts["macros"] += len(retired)
        pcs.update(retired)
        for bit, name in enumerate(ROB_NAMES):
            counts[name] += (rob >> bit) & 1
        for bit, name in enumerate(DISPATCH_NAMES):
            counts[f"dispatch_{name}"] += (dispatch >> bit) & 1
        if not retired and start is None:
            start = cycle
        if retired:
            if start is not None:
                # These PCs bracket a gap; neither is asserted to be its cause.
                gaps.append((cycle - start, start, cycle - 1, preceding, retired[0]))
                start = None
            preceding = retired[-1]
    if start is not None:
        gaps.append((len(rows) - start, start, len(rows) - 1, preceding, None))
    return counts, pcs, sorted(gaps, key=lambda gap: gap[0], reverse=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", nargs="+", type=Path)
    args = parser.parse_args()
    total, pcs = Counter(), Counter()
    for path in args.captures:
        counts, local_pcs, gaps = summarize(read_capture(path))
        total.update(counts)
        pcs.update(local_pcs)
        print(f"{path.name}: cycles={counts['cycles']} macros={counts['macros']} "
              f"IPC={counts['macros'] / counts['cycles']:.6f}")
        for length, start, end, before, after in gaps[:3]:
            fmt = lambda pc: "outside-window" if pc is None else f"0x{pc:08x}"
            print(f"  no-macro gap={length} cycles [{start},{end}] "
                  f"preceding={fmt(before)} following={fmt(after)}")
    print(f"DISJOINT_CAPTURE_TOTAL cycles={total['cycles']} macros={total['macros']} "
          f"IPC={total['macros'] / total['cycles']:.6f}")
    for name in ROB_NAMES + [f"dispatch_{n}" for n in DISPATCH_NAMES]:
        print(f"{name:26} {total[name]:8} {100 * total[name] / total['cycles']:8.3f}% of sampled cycles")
    print("Most frequent retired PCs (not stalled-head attribution):")
    for pc, count in pcs.most_common(16):
        print(f"  0x{pc:08x} {count}")


if __name__ == "__main__":
    main()
