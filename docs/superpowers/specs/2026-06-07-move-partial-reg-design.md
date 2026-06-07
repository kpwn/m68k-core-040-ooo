# MOVE.B/.W to Dn partial-register write (correctness fix)

BUG: MOVE.B/.W to a DATA register overwrites the full 32 bits instead of preserving Dn upper bytes (68k partial-register semantics). Root cause (`execute/AluEuPlugin.scala:127`): `mergedResult = Mux(isMove, rsp.result, sizeMerged)` — MOVE skips the size-merge because MOVE does NOT read its destination, so the old Dn is not available as a merge source (`s1Src1`). For ALU ops the dest IS src1 (Dn op= ...), so the merge works; MOVE has no such read.

FIX: for MOVE.B/.W with a DATA-register destination, decode/rename to READ the old Dn as the merge source (src1 = dest reg), then DROP the `isMove` exclusion so MOVE uses the same size-merge. Unchanged: MOVE.L to Dn (full 32 write), MOVE to memory (byte/word write), MOVEA (An always full-32 sign-extended). This is a partial-register dependency (the .B/.W MOVE now depends on the old Dn — rename adds the source).

GATE: lock-step vs Musashi x2 — MOVE.B/.W to Dn preserves upper bytes (init Dn=0x11223344, move.b #0xAA,d0 -> 0x112233AA; move.w -> 0x1122xxxx); MOVE.L full; MOVE to mem unchanged; MOVEA.W sign-extend. ALL existing UNCHANGED. + OOC sanity (FMax-neutral). Reference: `execute/AluEuPlugin.scala:116-152`, the decode MOVE handling in `decode/OperationDecoder.scala`+`MicroOpAssembler.scala` (add the dest-read for MOVE.B/.W-to-Dn), `rename/`.
