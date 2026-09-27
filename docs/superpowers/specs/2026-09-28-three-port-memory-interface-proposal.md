# Proposal: three CPU memory ports — wide I, wide D, and a dedicated uncacheable port

**Status: PROPOSAL, owner-originated 2026-09-28. NOT ratified — and one question must be
answered before it can be.** Filed so the analysis is not lost; `AGENTS.md` fixes
architecture to the design doc, so nothing here is built until the owner decides.

Owner's words: *"what if we restructure the core into 3 access ports: two 64b wide ports
for i and d and one axi port for every uncacheable access"*.

## ⚠️ The open question: "64b" = 64 BITS or 64 BYTES?

It changes the work completely, so it is recorded rather than assumed.

- **64 bits** would *halve* the D port (128 → 64) and quarter the I port (256 → 64). That
  cuts the bandwidth ceiling and contradicts the stated GB/s goal, so it is almost
  certainly not the intent.
- **64 bytes** (one L2 line per transfer, 512-bit) is the reading consistent with the goal.

**Recommended target: D at 256-bit with 64-byte lines — the I side's already-proven shape**,
two 32 B beats per 64 B line. 512-bit would exceed the MIG's 256-bit backend and is novel;
256-bit is precedented in-tree and matches the fabric.

## What the current interface actually is (verified, `rtl/soc/cpu_socket.vh`)

| | I side | D side |
|---|---|---|
| socket data width | **256-bit** (`CPU_SOCKET_AXI_I_DW`) | 128-bit (`CPU_SOCKET_AXI_D_DW`) |
| line size | **64 B** | 16 B |
| MSHRs | **5** (1 demand + 4 prefetch) | 1 |
| path to L2 | **dedicated `f_axi_*` port, bypasses `axi_xbar.v`** | through the crossbar |
| outstanding | pre-latched (task #269); 8 L2 MSHRs below | **1** (`rs_state[mi] == RS_IDLE`) |

**The D side is the poor relation in every dimension.** The socket header already records
why I is that shape: *"The 040-OoO core ('v2') fetches natively at 256b (len=1/size=5, two
32B beats = one 64B line)"*. So "widen I and D" is largely **"make D look like I"**, against
a shipping precedent rather than a new design.

## The third port is the strongest part, and it is a correctness argument as much as perf

Uncacheable/MMIO traffic shares the D path today. That is implicated in four separate
recorded problems:

1. The **150 MHz death** was a self-re-arming **ARBITER_WEDGE on a cache-inhibited ASC
   store** — NOT timing (150 MHz closed at WNS +0.043, zero failing endpoints).
2. The **P4 park deadlock**: an inhibited op parks in P4 for ROB-head while an older op
   behind it in P3 can never pass. Confirmed pre-existing in `master aa312145`.
3. **Inhibited accesses are memory barriers by design** (and `GOAL.txt` says keep them so),
   so today **MMIO ordering throttles cacheable bandwidth**. The two classes have opposite
   requirements and share one port.
4. Design doc slice **D4** already wants "at-ROB-head non-speculative MMIO loads + the
   `dQuiesce` primitive". A separate port is a cleaner way to obtain that property than
   adding ordering to the shared path.

### ✅ The expensive part is already paid for

`axi_xbar.v:70-80`: the fan-in arrays are `NW=4, NR=4`, but *"only 2 of 4 write slots and 3
of 4 read slots have a live top-level port now; the rest are permanently tied to 'never
valid'"* — and the round-robin picker is **hardcoded to 4 named inputs** and explicitly
called out as *"load-bearing, hand-written … not parameterized over width"*.

So **a third CPU master consumes an existing spare slot and requires no arbiter resize.**
That is normally the costly part of adding a master, and it is already there.

## Costs and risks

- **`cpu_socket.vh` IS the CPU↔SoC contract**, and it is explicitly versioned — `AXI_I_DW`
  was split out of a single shared width (task #266 / SOC-1) precisely so a v1-shaped and a
  v2-shaped CPU could coexist without distorting each other. A third master is a
  coordinated two-repo change, not a CPU-side refactor.
- **Cross-port ordering.** A store on the uncacheable port and an access on the cacheable
  port must not reorder against each other. Mitigating fact: the two are disjoint by MMU
  page attribute, so aliasing requires the same physical page mapped both ways — which
  already demands a flush. **Verify rather than assume**; the MMU config-latch-vs-live
  defect family in this core is exactly the shape that breaks such an assumption.
- **Overlap with work in flight.** The line-size half is already being built (sectored 64 B
  lines, ratified `9229fcb1`). Only the **port widening** and the **third port** are new.

## Sequencing note

Do **not** fold this into the sectored-lines agent's scope. That agent's predecessor was
reprioritised four times mid-task and rightly objected; this is a separate piece of work
with a separate repo boundary.
