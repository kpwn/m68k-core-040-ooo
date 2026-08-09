# Post-route IPC-pipeline checkpoint

Date: 2026-08-09

Tool/device: Vivado 2025.2, `xcku5p-ffvb676-2-e`

Constraint: 4.000 ns (250 MHz optimization goal; 200 MHz deployment floor)

Generated-Verilog MD5: `113f4aa786d59d9eaed92605dce9e54d`

## Timing

| checkpoint | WNS | TNS | failing endpoints | achieved FMax |
|---|---:|---:|---:|---:|
| `f009623` routed baseline | -0.508 ns | -462.267 ns | 4,512 | 221.828 MHz |
| IPC pipeline branch | -1.766 ns | -16123.603 ns | 28,234 | 173.430 MHz |

The implementation completed with zero unrouted nets and no hold failures. The
current result is characterization, not timing acceptance.

## Global utilization

| resource | baseline | current | delta | current device use |
|---|---:|---:|---:|---:|
| LUT | 103,740 | 117,956 | +14,216 | 54.37% |
| LUT logic | 95,512 | 109,628 | +14,116 | 50.53% |
| LUTRAM | 8,228 | 8,328 | +100 | 8.34% of LUT-memory capacity |
| register | 47,343 | 50,075 | +2,732 | 11.54% |
| BRAM tile | 26 | 26 | 0 | 5.42% |
| DSP48E2 | 6 | 4 | -2 | 0.22% |

Global area is not the limiting resource. The two removed DSPs are the expected
result of strength-reducing MOVEM address arithmetic.

## Floorplan health

| region | parent-assigned LUT | total placed LUT | parent CLB | total used CLB / available |
|---|---:|---:|---:|---:|
| `pb_dcache` | 34,112 | 38,689 (88.57%) | 6,394 | 6,694 / 5,460 (122.60%) |
| `pb_decode` | 22,774 | 32,302 (73.95%) | 4,353 | 5,450 / 5,460 (99.82%) |

The D-cache hierarchy cannot fit in its current region. Decode's neighboring
geometry is also essentially full, so recovery needs connectivity-aware pblock
rebalancing rather than a blind one-edge expansion.

## Endpoint diagnosis

The capped 5,000-endpoint family census reports ROB 3,731, D-cache 566, and IQ
361. The worst path is ROB exception-FSM state to IQ integer-scoreboard state:
5.745 ns, 18 levels, 1.636 ns logic and 4.109 ns route (71.5%). It traverses the
overloaded D-cache corridor through exception/early-probe/translation gating.
The D-cache S3 and tag-memory probes are also failing families, but neither is
the sole critical cone.

## Decision

The owner explicitly selected a bounded finish-before-recovery sequence:

1. reshape the existing four-cycle, II=1 multiplier so the four DSP48E2s retain
   their internal A/B/M/P registers without increasing LUT count;
2. implement the already-scoped registered-token FTB; and
3. perform consolidated endpoint-driven FMax, area, and floorplan recovery.

This does not waive the targets. 250 MHz remains the optimization goal; 200 MHz
remains the hard deployment floor. Any area-gate breach is reported with exact
tradeoffs before rejection or rollback.
