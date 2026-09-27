# Area ledger — 100 MHz lane

Every 100 MHz build appends a row. **Area inside this lane is exact and comparable;
never compare a row here against a 200 MHz build** — synthesis retimes and replicates
harder against a tight constraint, so the same RTL reads lower on LUTs at 100 MHz.
`WNS` is recorded only to show the build closed trivially; it says **nothing** about
200 MHz closure.

Plan (owner, 2026-09-27): **track area as levers land, then do an area-reduction pass
once they are all in.** Reference points for that pass — the last 200 MHz builds were
m20 at 152,093 LUT and `areaonly200` at 147,045, and the area campaign that got there
removed 5,048 LUT with zero IPC cost.

| date | build | fpga_top LUT | socket_core LUT | FF | RAMB36 | WNS | note |
|---|---|---:|---:|---:|---:|---:|---|
| 09-27 | br0 (baseline) | 144831 | 94972 | 90798 | 172 | 0.023 | master+track5, both branch flags OFF |
| 09-27 | br5 | 146001 | 95813 | 90785 | 172 | 0.039 | +computeDirectTargets +deferSlot1Uncond |
