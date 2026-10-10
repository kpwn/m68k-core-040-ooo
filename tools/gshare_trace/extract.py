#!/usr/bin/env python3
"""Turn btrace.lua instruction-stream records into conditional-branch events.
Record: <I4 curpc><I4 pc><I2 word>, pc = address of the word read (Musashi prefetch).
Instruction start = a new curpc, or a refill read at curpc itself (self-loops).
Output (per window, appended): array of (pc, kind, taken) as 3 parallel arrays pickled."""
import struct, sys, array, pickle
def sx8(v): return v-256 if v&0x80 else v
def sx16(v): return v-65536 if v&0x8000 else v
def events(path):
    d=open(path,'rb').read()
    mem={}
    starts=array.array('I')
    lastcp=-1
    for cp,pc,w in struct.iter_unpack('<IIH',d):
        mem[pc]=w
        if cp!=lastcp or cp==pc:
            starts.append(cp)
        lastcp=cp
    ev_pc=array.array('I'); ev_kind=array.array('B'); ev_t=array.array('B')
    skipped=0; ninst=len(starts)
    for i in range(len(starts)-1):
        x=starts[i]; op=mem.get(x)
        if op is None: continue
        nx=starts[i+1]
        kind=0
        if (op&0xF000)==0x6000 and ((op>>8)&0xF)>=2:
            kind=1; d8=op&0xFF
            if d8==0:
                e=mem.get(x+2);
                if e is None: continue
                ln=4; tgt=x+2+sx16(e)
            elif d8==0xFF:
                e1=mem.get(x+2); e2=mem.get(x+4)
                if e1 is None or e2 is None: continue
                ln=6; v=(e1<<16)|e2; v=v-(1<<32) if v&0x80000000 else v; tgt=x+2+v
            else:
                ln=2; tgt=x+2+sx8(d8)
        elif (op&0xF0F8)==0x50C8:
            kind=2; e=mem.get(x+2)
            if e is None: continue
            ln=4; tgt=x+2+sx16(e)
        elif (op&0xFF80)==0xF280:
            kind=3
            if op&0x40:
                e1=mem.get(x+2); e2=mem.get(x+4)
                if e1 is None or e2 is None: continue
                ln=6; v=(e1<<16)|e2; v=v-(1<<32) if v&0x80000000 else v; tgt=x+2+v
            else:
                e=mem.get(x+2)
                if e is None: continue
                ln=4; tgt=x+2+sx16(e)
        else:
            continue
        tgt&=0xffffffff
        if tgt==x+ln: continue  # degenerate
        if nx==tgt: t=1
        elif nx==x+ln: t=0
        else: skipped+=1; continue
        ev_pc.append(x); ev_kind.append(kind); ev_t.append(t)
    return ninst, skipped, ev_pc, ev_kind, ev_t
if __name__=='__main__':
    out=sys.argv[1]; res=[]
    for p in sys.argv[2:]:
        r=events(p); res.append((p,)+r)
        print("%s inst=%d condbr=%d skipped(exc/irq)=%d taken=%.1f%%"%(p,r[0],len(r[2]),r[1],100*sum(r[4])/max(1,len(r[4]))),flush=True)
    pickle.dump(res,open(out,'wb'))
