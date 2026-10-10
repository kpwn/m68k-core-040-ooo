#!/usr/bin/env python3
"""gshare model matching frontend/Gshare.scala: idx = fold(pc[31:1], b) ^ ghr[h-1:0];
2-bit counters init 2 (weakly taken), predict counter>=2, ghr shifts in the outcome.
Predictor state is carried across windows (chained) -- windows are back-to-back slices of one boot.

Usage (sizes the CPU_GSHARE_ENTRIES / CPU_GSHARE_HIST_BITS knobs on a real 7.5.3 boot):
  cd ~/mame_q700_good
  BTRACE_OUT=/var/tmp/bt/b BTRACE_WINDOWS="3:3000000,6:3000000,...,30:3000000" \
    ./run.sh -autoboot_script <repo>/tools/gshare_trace/btrace.lua -seconds_to_run 33
  python3 extract.py ev.pkl $(ls /var/tmp/bt/b.w*.bin | sort -V)
  python3 model.py ev.pkl 11:11 12:12 13:13 14:14 13:10      # idxBits:histBits
Ideal model: every Bcc/DBcc/FBcc is predicted and trained immediately (no BTB gating,
no retire-delayed update), so read the RELATIVE mispredict change between sizes."""
import sys, pickle
def fold(v, w, b):
    acc=0; m=(1<<b)-1
    while w>0:
        acc^=v&m; v>>=b; w-=b
    return acc
def run(res, b, h, label):
    n=1<<b; pht=bytearray([2])*n; ghr=0; hm=(1<<h)-1
    tot=0; mis=0; perwin=[]
    fcache={}
    for (p,ninst,sk,pcs,kinds,ts) in res:
        wm=0; wt=0
        for pc,t in zip(pcs,ts):
            f=fcache.get(pc)
            if f is None: f=fold(pc>>1,31,b); fcache[pc]=f
            i=f^(ghr&hm)
            c=pht[i]
            if (c>=2)!=bool(t): wm+=1
            if t:
                if c<3: pht[i]=c+1
            elif c>0: pht[i]=c-1
            ghr=((ghr<<1)|t)&0xffff
            wt+=1
        tot+=wt; mis+=wm; perwin.append((ninst,wt,wm))
    ni=sum(x[0] for x in perwin)
    print("%-22s entries=%5d hist=%2d  cond=%d  mispred=%d  rate=%.3f%%  MPKI=%.3f"%(label,n,h,tot,mis,100*mis/tot,1000*mis/ni),flush=True)
    return mis, perwin
if __name__=='__main__':
    res=pickle.load(open(sys.argv[1],'rb'))
    cfgs=[(11,11),(12,12),(13,13),(14,14),(12,11),(13,11),(14,11),(13,12),(14,12),(16,16)]
    if len(sys.argv)>2: cfgs=[tuple(map(int,c.split(':'))) for c in sys.argv[2:]]
    base=None
    for b,h in cfgs:
        m,_=run(res,b,h,"b=%d h=%d"%(b,h))
        if base is None: base=m
        else: print("    vs 2048/h11: %+.2f%% mispredicts"%(100*(m/base-1)))
