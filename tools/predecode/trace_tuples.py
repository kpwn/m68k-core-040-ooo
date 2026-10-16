#!/usr/bin/env python3
# Dynamic executed-instruction shape histogram (op + 3 lookahead words) from MAME mtrace windows,
# for PredecodeCutFreqSpec: python3 trace_tuples.py out.txt m.w10.bin [m.w8.bin ...]
import sys, collections
sys.path.insert(0, __import__('os').path.dirname(__file__))
import mtrace_decode as dec
out = collections.Counter(); miss = 0; n = 0
for path in sys.argv[2:]:
    r = dec.decode(path)
    mem = r['mem']; st = r['starts']
    for x in st:
        ws = [mem.get(x + 2 * k) for k in range(4)]
        if ws[0] is None: miss += 1; continue
        n += 1
        out[tuple(-1 if w is None else w for w in ws)] += 1
    print(path, len(st), file=sys.stderr, flush=True)
with open(sys.argv[1], 'w') as f:
    for t, c in out.most_common():
        f.write('%d %d %d %d %d\n' % (t + (c,)))
print('instr', n, 'nofetch', miss, 'unique', len(out), file=sys.stderr)
