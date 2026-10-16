#!/usr/bin/env python3
# Copy of claude/fable-tracemodel tools/predtrace/decode.py (3ff180a0); only change: decode() also returns `starts` (executed PCs) and `mem` (fetched words).
"""Decode mtrace.lua windows into control-flow events + I/D logical-page streams.

Input: <prefix>.wN.bin, 17-byte records <I4 pc><I4 curpc><I4 addr><I4 data><I1 rw<<4|size>.

MAME 0.285's 68040 walks the page tables before EVERY access (it has no ATC), e.g.
    R4 007fcc80 -> 007fca0a   root    (SRP/URP table, 128 x 4 B, VA[31:25])
    R4 007fca80 -> 007fa90a   pointer (512 B table,  128 x 4 B, VA[24:18])
    R4 007fa910 -> 40808039   page    (8K pages: 128 B table, 32 x 4 B, VA[17:13])
    R2 408099f6               the access (PHYSICAL)
The three descriptor ADDRESSES carry the logical address, so VA is reconstructed
exactly: VA = rootIdx<<25 | ptrIdx<<18 | pageIdx<<13 | (pa & 0x1FFF). System 7.5.3 on
the Q700 runs TC.P = 8K pages (page tables are 128 B; verified by the pa/pd check).
An access with no preceding walk is transparent-translated (TTR) and never touches
an ATC. Fetches are accesses whose VA == the live prefetch pc (Musashi's PC register).

Output pickle (one per window) = dict:
  ninst, ev (list of (pc, cls, taken, target, fallthrough)), dkeys/ikeys (logical
  page keys, deduplicated against the previous access of the same stream; exact for
  any TLB of >=1 entry), plus counters.
cls: 1 Bcc, 2 DBcc, 3 FBcc, 4 BRA, 5 BSR, 6 JMP direct, 7 JSR direct,
     8 JMP indirect, 9 JSR indirect, 10 RTS, 11 RTR, 12 RTD, 13 RTE,
     14 A-line trap, 15 other exception/irq (unexplained non-sequential flow)
Indirect EA sub-kind is kept in the high nibble of cls for 8/9:
     (An)=0, d16(An)=1, d8(An,Xn)=2, memory-indirect (full ext, I/IS)=3, d8(PC,Xn)=4
"""
import struct, sys, array, pickle, collections

def sx8(v): return v - 256 if v & 0x80 else v
def sx16(v): return v - 65536 if v & 0x8000 else v

def ea_ext_len(mode, reg, mem, x, size_imm=2):
    """bytes of EA extension for control EAs (jmp/jsr); None if unknown."""
    if mode in (2, 3, 4): return 0
    if mode == 5: return 2
    if mode == 7 and reg == 0: return 2
    if mode == 7 and reg == 1: return 4
    if mode == 7 and reg == 2: return 2
    if mode == 6 or (mode == 7 and reg == 3):
        e = mem.get(x)
        if e is None: return None
        if not (e & 0x100): return 2
        n = 2 + {2: 2, 3: 4}.get((e >> 4) & 3, 0)
        if (e & 7) not in (0, 4): n += {2: 2, 3: 4}.get(e & 3, 0)
        return n
    return None

def classify(x, mem):
    """-> (cls, length, direct_target or None, sub) or None for a non-CTI."""
    op = mem.get(x)
    if op is None: return None
    hi = op & 0xF000
    if hi == 0x6000:
        c = (op >> 8) & 0xF; d8 = op & 0xFF
        if d8 == 0:
            e = mem.get(x + 2)
            if e is None: return None
            ln = 4; tgt = x + 2 + sx16(e)
        elif d8 == 0xFF:
            e1 = mem.get(x + 2); e2 = mem.get(x + 4)
            if e1 is None or e2 is None: return None
            v = (e1 << 16) | e2; v = v - (1 << 32) if v & 0x80000000 else v
            ln = 6; tgt = x + 2 + v
        else:
            ln = 2; tgt = x + 2 + sx8(d8)
        return ({0: 4, 1: 5}.get(c, 1), ln, tgt & 0xffffffff, 0)
    if (op & 0xF0F8) == 0x50C8:
        e = mem.get(x + 2)
        if e is None: return None
        return (2, 4, (x + 2 + sx16(e)) & 0xffffffff, 0)
    if (op & 0xFF80) == 0xF280:
        if op & 0x40:
            e1 = mem.get(x + 2); e2 = mem.get(x + 4)
            if e1 is None or e2 is None: return None
            v = (e1 << 16) | e2; v = v - (1 << 32) if v & 0x80000000 else v
            return (3, 6, (x + 2 + v) & 0xffffffff, 0)
        e = mem.get(x + 2)
        if e is None: return None
        return (3, 4, (x + 2 + sx16(e)) & 0xffffffff, 0)
    if (op & 0xFF80) == 0x4E80:           # JSR 4E80, JMP 4EC0
        isjmp = bool(op & 0x40)
        mode = (op >> 3) & 7; reg = op & 7
        if mode == 7 and reg in (0, 1, 2):
            if reg == 0:
                e = mem.get(x + 2)
                if e is None: return None
                tgt = sx16(e) & 0xffffffff; ln = 4
            elif reg == 1:
                e1 = mem.get(x + 2); e2 = mem.get(x + 4)
                if e1 is None or e2 is None: return None
                tgt = (e1 << 16) | e2; ln = 6
            else:
                e = mem.get(x + 2)
                if e is None: return None
                tgt = (x + 2 + sx16(e)) & 0xffffffff; ln = 4
            return (6 if isjmp else 7, ln, tgt, 0)
        if mode in (2, 5, 6) or (mode == 7 and reg == 3):
            sub = {2: 0, 5: 1}.get(mode, 2)
            if mode == 7: sub = 4
            if mode == 6 or mode == 7:
                e = mem.get(x + 2)
                if e is not None and (e & 0x100) and (e & 7):
                    sub = 3
            n = ea_ext_len(mode, reg, mem, x + 2)
            return (8 if isjmp else 9, 2 + (n or 0), None, sub)
        return None
    if op == 0x4E75: return (10, 2, None, 0)
    if op == 0x4E77: return (11, 2, None, 0)
    if op == 0x4E74: return (12, 4, None, 0)
    if op == 0x4E73: return (13, 2, None, 0)
    if hi == 0xA000: return (14, 2, None, 0)
    return None

def decode(path):
    REC = struct.Struct('<IIIIB')
    blob = open(path, 'rb').read()
    # ---- learn root table bases (SRP/URP) from chain-consistent triples ----
    roots = collections.Counter()
    prev = []
    for i, r in enumerate(REC.iter_unpack(blob[:17 * 400000])):
        prev.append(r)
        if len(prev) > 3: prev.pop(0)
        if len(prev) == 3:
            a, b, c = prev
            if all(z[4] == 4 for z in prev) and (a[3] & 3) >= 2 and (b[3] & 3) >= 2 \
               and (b[2] & ~0x1FF) == (a[3] & ~0x1FF) and (c[2] & ~0x7F) == (b[3] & ~0x7F):
                roots[a[2] & ~0x1FF] += 1
    rootset = {k for k, v in roots.items() if v >= 50}
    rootid = {k: i for i, k in enumerate(sorted(rootset))}

    mem = {}
    starts = array.array('I')
    dkeys = array.array('I'); dkpos = array.array('I')
    ikeys = array.array('I'); ikpos = array.array('I')
    lastd = -1; lasti = -1
    st = 0; wr = wp = wg = None
    lastcp = -1; pend = False
    n_walk = n_tt = n_pdbad = n_dacc = n_fetch = n_descw = 0
    n_walk_d = 0
    ninst = 0
    for pc, cp, addr, data, fl in REC.iter_unpack(blob):
        rw = fl >> 4; sz = fl & 15
        if cp != lastcp:
            ninst += 1; starts.append(cp); lastcp = cp; pend = True
        # --- walk state machine ---
        if rw == 0 and sz == 4:
            if (addr & ~0x1FF) in rootset and st != 1 and st != 2:
                wr = (addr, data); st = 1; continue
            if st == 1 and (addr & ~0x1FF) == (wr[1] & ~0x1FF):
                wp = (addr, data); st = 2; continue
            if st == 2 and (addr & ~0x7F) == (wp[1] & ~0x7F):
                wg = (addr, data); st = 3
                if (data & 3) == 2: st = 4
                continue
            if st == 4 and addr == (wg[1] & ~3):
                wg = (wg[0], data); st = 3; continue
        if rw == 1 and st == 3 and addr in (wr[0], wp[0], wg[0]):
            n_descw += 1; continue
        if st == 3:
            va = (((wr[0] & 0x1FF) >> 2) << 25) | (((wp[0] & 0x1FF) >> 2) << 18) | \
                 (((wg[0] & 0x7F) >> 2) << 13) | (addr & 0x1FFF)
            if (addr & ~0x1FFF) != (wg[1] & ~0x1FFF): n_pdbad += 1
            key = (rootid[wr[0] & ~0x1FF] << 19) | (va >> 13)
            n_walk += 1; st = 0
        else:
            st = 0; va = addr; key = -1; n_tt += 1
        # --- fetch or data ---
        isf = False
        if rw == 0:
            if sz == 2 and va == pc:
                isf = True; mem[va] = (data >> 16) & 0xFFFF if not (va & 2) else data & 0xFFFF
            elif sz == 4 and (va == pc or va + 2 == pc) and not (va & 3):
                isf = True; mem[va] = data >> 16; mem[va + 2] = data & 0xFFFF
        if isf:
            n_fetch += 1
            if va == cp and pc == cp:
                if pend: pend = False
                else: ninst += 1; starts.append(cp)
            elif va != cp:
                pend = False
            if key >= 0 and key != lasti:
                ikeys.append(key); ikpos.append(ninst); lasti = key
        else:
            n_dacc += 1
            if key >= 0:
                n_walk_d += 1
                if key != lastd:
                    dkeys.append(key); dkpos.append(ninst); lastd = key
    # ---- control-flow events ----
    ev = []
    cnt = collections.Counter()
    flushes = []   # (instruction index, kind): pflush* / movec to TC,URP,SRP,ITTx,DTTx
    for i in range(len(starts) - 1):
        x = starts[i]; nx = starts[i + 1]
        op = mem.get(x)
        if op is not None and (op & 0xFFE0) == 0xF500:
            flushes.append((i + 1, 'pflush%d' % ((op >> 3) & 3), x)); cnt['pflush'] += 1
        elif op == 0x4E7B:
            e = mem.get(x + 2)
            if e is not None and (e & 0xFFF) in (0x003, 0x004, 0x005, 0x006, 0x007, 0x806, 0x807):
                flushes.append((i + 1, 'movec%03x' % (e & 0xFFF), x)); cnt['movec_mmu'] += 1
        c = classify(x, mem)
        if c is None:
            continue
        cls, ln, tgt, sub = c
        ft = (x + ln) & 0xffffffff
        if cls in (1, 2, 3):
            if tgt == ft: continue
            if nx == tgt: t = 1
            elif nx == ft: t = 0
            else: cnt['cond_skip'] += 1; continue
            ev.append((x, cls, t, tgt, ft))
        elif cls in (4, 5, 6, 7):
            if nx != tgt: cnt['dir_skip'] += 1; continue
            ev.append((x, cls, 1, tgt, ft))
        else:
            ev.append((x, cls | (sub << 4), 1, nx, ft))
        cnt[cls] += 1
    cols = list(zip(*ev)) if ev else [[]] * 5
    evd = dict(pc=array.array('I', cols[0]), cls=array.array('B', cols[1]), t=array.array('B', cols[2]),
               tgt=array.array('I', cols[3]), ft=array.array('I', cols[4]))
    return dict(starts=starts, mem=mem, path=path, ninst=ninst, ev=evd, dkeys=dkeys, dkpos=dkpos, ikeys=ikeys,
                ikpos=ikpos, flushes=flushes, roots=sorted(rootset), cnt=dict(cnt),
                n_walk=n_walk, n_tt=n_tt, n_pdbad=n_pdbad, n_dacc=n_dacc,
                n_walk_d=n_walk_d, n_fetch=n_fetch, n_descw=n_descw, nmem=len(mem))

if __name__ == '__main__':
    r = decode(sys.argv[1])
    pickle.dump(r, open(sys.argv[2], 'wb'))
    print("%s inst=%d ev=%d dacc=%d(walked %d, tt %d) fetch=%d pdbad=%d descw=%d roots=%s dkeys=%d ikeys=%d cnt=%s" % (
        r['path'], r['ninst'], len(r['ev']['pc']), r['n_dacc'], r['n_walk_d'], r['n_tt'], r['n_fetch'],
        r['n_pdbad'], r['n_descw'], [hex(x) for x in r['roots']], len(r['dkeys']), len(r['ikeys']),
        sorted(r['cnt'].items(), key=lambda kv: str(kv[0]))), flush=True)
