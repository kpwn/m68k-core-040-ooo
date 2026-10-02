# LS OoO: matched 200 MHz performance reference, 2026-10-02

Use the **LS OoO ON image as the performance reference** for subsequent core work. This is a measured board reference, not a 200 MHz timing-signoff claim. The production build-time default remains OFF until timing and remaining correctness gates close. An IPC figure was not captured during this Speedometer run; the values below are guest benchmark throughput, not retired macros per cycle.

The matched pair used CPU `33ae19f5c5b7a3d3a45cb250584129f88694cc88`, SoC `d01dbdc52ef78146ec033da0c67045bc1678df50`, the same real MIG checkpoint, 200 MHz throughput-v2, P1 branch defaults ON, 16-byte D-cache lines, and all other experiment flags OFF. `SHIPPING_CONFIG` differs only in `lsOooIssue`. The locally ADB-patched bitstreams were programmed and each booted System 7.5.3 with live 640×480 DAFB, no fatal halt, and advancing retire counters. The `0xD01DBDC5` JTAG build ID is shared by both arms; the image hashes below distinguish them.

| Arm | local bitstream SHA-256 | Dhrystones/s | Puzzle seconds | Speedometer CPU average |
|---|---|---:|---:|---:|
| LS OoO OFF | `322c4910d5995e9b0ad98d8e6d59e5e120a726fbf2b67f01040c783f3eed1033` | 116,073.376 | 0.168 | 7.529 |
| LS OoO ON | `8785e08927042c5b0d0d54c4963739067db984e2cc7b35966356cf14ec2606b3` | 117,711.315 | 0.113 | 8.024 |

Both screenshots show one iteration per Speedometer 4.02 test. ON improves Dhrystones/s **1.41%**, Puzzle throughput **about 49%** (6.500 to 9.662 in Speedometer's own ratio column), and CPU average **6.58%**. Integer Matrix rises from 7.158 to 8.241 in the ratio column, another substantial independent workload gain. The [OFF screenshot](evidence/2026-10-02-ls-ooo-off-speedometer.jpg) and [ON screenshot](evidence/2026-10-02-ls-ooo-on-speedometer.jpg) preserve the results. The user operated the benchmark; the agent captured the HDMI output and independently recorded programmed paths, hashes, boot checks, and the fixed build profile. This is a one-iteration A/B, so repeat longer runs and take phase-verified IPC windows before attributing a broad IPC percentage.

The same-source 200 MHz routed physical A/B gives OFF **WNS +0.001 ns** and ON **WNS −0.156 ns**, with 2,096 ON setup endpoints failing. Both arms have zero hold and pulse-width failures and the same 80 DRC warnings with no errors. ON costs **+935 FPGA-top LUTs and +534 FFs** (149,360 LUT / 92,022 FF ON versus 148,425 LUT / 91,488 FF OFF), with BRAM and DSP unchanged. The ON top ten endpoints span ROB→D-cache, Gshare→I-cache, FetchAlign→RAS, ROB→Rename, and IQ→Div. The timing miss is distributed; one LS datapath edit is not yet justified by these reports. Try a controlled placement-seed experiment on the same RTL and constraints, then target repeated fanout paths if the miss persists.

The separate D4/NB4/hot-door physical A/B intentionally keeps LS OoO OFF to isolate nonblocking memory-system cost. After that pair is assessed, test the combined ON configuration against **this** LS OoO performance reference. Preserve neutral but sound infrastructure needed for that integration even when its standalone IPC gain is small.
