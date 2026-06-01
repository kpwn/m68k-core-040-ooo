#!/usr/bin/env python3
"""Post-process SpinalHDL multi-write-port Mems into single-driver register
files so they synthesize on FPGA (no FPGA RAM primitive has 2 write ports).

For each module region, finds all reset-less `always @(posedge clk) begin ... end`
blocks that write a memory array `NAME[idx] <= val;`, groups them by array, and
merges each group into ONE clocked process. The if-bodies are concatenated in
original program order, so a later write port wins on a same-address collision
(WAW) — exactly the multi-write-bypass semantics the RAT/ROB already intend.
Also retargets those arrays from ram_style=distributed to registers.
"""
import re, sys

path = sys.argv[1]
src = open(path).read()
# NOTE: do NOT globally flip ram_style here — single-write async arrays (e.g. the
# I-cache tag/pred LUTRAMs) must stay distributed RAM. Only the multi-write arrays
# we actually merge below get retargeted to registers (see end of script).
lines = src.split('\n')

# Split into module regions [start, end] inclusive by 'module'/'endmodule'.
regions = []
start = None
for i, l in enumerate(lines):
    if re.match(r'^module\s', l):
        start = i
    elif re.match(r'^endmodule', l) and start is not None:
        regions.append((start, i)); start = None
# Top-level (outside any module) also holds payload — treat the whole file head
# region (before first module) and gaps; simplest: also scan the full file as one
# extra region for arrays not inside a module.
regions.append((0, len(lines) - 1))

ALWAYS_RE = re.compile(r'^\s*always @\(posedge clk\) begin\s*$')
WRITE_RE  = re.compile(r'^\s*(\w+)\[[^\]]+\]\s*<=')

def find_blocks(lo, hi):
    """Return list of (start,end,array) for reset-less posedge-clk always blocks."""
    out = []
    i = lo
    while i <= hi:
        if ALWAYS_RE.match(lines[i]):
            depth = 0; j = i; arr = None
            while j <= hi:
                toks = lines[j]
                depth += len(re.findall(r'\bbegin\b', toks))
                depth -= len(re.findall(r'\bend\b', toks))
                m = WRITE_RE.match(lines[j])
                if m: arr = m.group(1)
                if depth == 0 and j > i:
                    break
                j += 1
            out.append((i, j, arr))
            i = j + 1
        else:
            i += 1
    return out

# Process each module region; merge groups of blocks writing the same array.
# Build a set of line-ranges to delete and text to inject at the first block.
edits = []  # (start, end, replacement_lines or None)
seen_ranges = []
merged_arrays = set()   # array names we merged -> retarget their ram_style to registers

def overlaps(a, b):
    return not (a[1] < b[0] or b[1] < a[0])

for (lo, hi) in regions:
    blocks = find_blocks(lo, hi)
    # skip blocks already covered by a processed (smaller) region to avoid double
    groups = {}
    for (bs, be, arr) in blocks:
        if arr is None: continue
        groups.setdefault(arr, []).append((bs, be))
    for arr, blks in groups.items():
        if len(blks) < 2:
            continue
        rng = (blks[0][0], blks[-1][1])
        if any(overlaps(rng, r) for r in seen_ranges):
            continue
        seen_ranges.append(rng)
        merged_arrays.add(arr)
        # extract inner bodies (lines strictly between block's begin and its final end)
        bodies = []
        for (bs, be) in blks:
            bodies.extend(lines[bs+1:be])  # everything between 'always..begin' and final 'end'
        merged = ['  always @(posedge clk) begin'] + bodies + ['  end']
        edits.append((blks[0][0], blks[0][1], merged))
        for (bs, be) in blks[1:]:
            edits.append((bs, be, []))  # delete

# Apply edits from bottom to top.
edits.sort(key=lambda e: e[0], reverse=True)
for (bs, be, repl) in edits:
    lines[bs:be+1] = repl

# Retarget ram_style to registers ONLY on the declarations of arrays we merged
# (multi-write). Single-write async arrays (tag/pred LUTRAM) keep distributed.
decl_re = re.compile(r'\(\* ram_style = "distributed" \*\)\s*reg\b.*\b(\w+)\s*\[')
for i, l in enumerate(lines):
    m = decl_re.search(l)
    if m and m.group(1) in merged_arrays:
        lines[i] = l.replace('ram_style = "distributed"', 'ram_style = "registers"')

open(path, 'w').write('\n'.join(lines))
print(f"applied {len(edits)} block edits across {len(seen_ranges)} merged arrays: {sorted(merged_arrays)}")
