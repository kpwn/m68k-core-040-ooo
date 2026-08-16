package m68k040.decode

import m68k040.isa.{Cluster, Size, MemOp}
import spinal.core._

object DecOp extends SpinalEnum {
  val MOVE, ADD, SUB, AND, OR, EOR, CMP, BRANCH, ILLEGAL,
      // CPLX-cluster (DivEu) ops: bound-check trap + integer divide. DIV carries
      // {signed, the .W/.L/64-form, quotient-only-vs-remainder} via size + the
      // divForm/divSigned/div64 µop fields below.
      CHK, DIV, DIVREM,
      // CPLX-cluster integer multiply (MULU/MULS). MUL writes the product low word
      // (Dn[31:0] for .W/.L32, Dl[31:0] for .L64); MULHI is the trailing crack µop
      // for the .L64 form (writes the LATCHED high product Dh from MulCore). Sign +
      // form carried via size + divSigned (reused for MULS) + div64 (.L64 marker).
      MUL, MULHI,
      // Line-E register-form shift/rotate (ASL/ASR/LSL/LSR/ROXL/ROXR/ROL/ROR). The
      // op carries `shiftOp` (tt: 0=AS,1=LS,2=ROX,3=RO) + `shiftDir` (1=left) + size;
      // the count is the immediate (useImm/imm, the `i=0` form) or the 2nd data-reg
      // source Dc (srcB, the `i=1` form). Routed to the ALU EU's barrel shifter.
      SHIFT,
      // Line-4 single-operand data-register ops (the unary family). All read the dst
      // Dn as srcA (the .B/.W partial-merge / operand source) and write Dn (except TST).
      //   CLR  : Dn(size):=0 (N=0,Z=1,V=0,C=0).
      //   NEG  : Dn := 0-Dn (NZVCX; X=C; V overflow).
      //   NEGX : Dn := 0-Dn-X (NZVCX; reads X + old Z; Z is CLEAR-ONLY).
      //   NOT  : Dn := ~Dn (NZ; V=0,C=0).
      //   TST  : flags only (NZ; V=0,C=0); no write.
      //   SWAP : Dn := {Dn[15:0],Dn[31:16]} (full-32; NZ from the 32-bit result).
      //   EXT  : sign-extend the low byte/word -> .W/.L (NZ); `extByte` selects the
      //          byte-source form (EXT.W byte->word .W; EXTB.L byte->long; EXT.L
      //          word->long when extByte=0).
      //   TAS  : N/Z from Dn[7:0] (V=0,C=0); then Dn[7]:=1 (byte partial merge).
      CLR, NEG, NEGX, NOT, TST, SWAP, EXT, TAS,
      // Extended add/subtract (register form, Dy,Dx). Like ADD/SUB but with the X
      // (extend) bit folded into the carry/borrow-in, and the 68k CLEAR-ONLY Z
      // (Z := Z_old && result==0) — exactly the NEGX rule. srcA=Dx (read+written),
      // srcB=Dy. Reuse NEGX's X-in (cmd.xIn) + old-Z (readsNzvc) machinery; no new field.
      //   ADDX : Dx := Dx + Dy + X (NZVCX; reads X + old Z; Z CLEAR-ONLY, like NEGX).
      //   SUBX : Dx := Dx - Dy - X (NZVCX; reads X + old Z; Z CLEAR-ONLY, like NEGX).
      ADDX, SUBX,
      // Packed-BCD add/subtract (ABCD/SBCD, register form, BYTE). One op with a 1-bit
      // `bcdSub` sub-kind (mirrors SHIFT/shiftDir): False=ABCD (Dx+Dy+X with decimal
      // adjust), True=SBCD (Dx-Dy-X). srcA=Dx (dst + .B-merge source), srcB=Dy, dst=Dx.
      // C/X = decimal carry/borrow (res>0x99); Z is CLEAR-ONLY (Z &= res==0, like NEGX);
      // N=res[7] and V=bit7(~rawLoSum & res) are computed to MATCH Musashi (officially
      // "undefined" but the full-CCR lock-step compares them). Routed to the ALU EU.
      BCD,
      // NBCD Dn (negate packed-BCD, register form ONLY -- memory-EA NBCD stays deferred/
      // illegal, task #159). Dn := BCD(0 - Dn - X); reuses the SBCD (subtract) formula
      // with the minuend forced to 0 in the ALU datapath (AluEuPlugin: `dx := 0` when
      // isNbcd), while srcA=srcB=Dn (EASRC, same register read twice: srcA supplies the
      // .B-merge upper-24 AND the "dy" subtrahend via srcB) so the merge still preserves
      // Dn[31:8]. Trivial case (Dn[7:0]==0 && X==0): result unchanged, X=C=V=0, Z sticky-
      // preserved (falls out of the SBCD formula/clear-only-Z rule automatically, no
      // special-casing needed). Flags: readsX/readsNzvc (old Z) + writesNzvc/writesX,
      // exactly like SBCD.
      NBCD,
      // Bit op (BTST/BCHG/BCLR/BSET): tests bit n -> Z = complement of that bit; all
      // but BTST then set/clear/toggle it. The op carries `bitOp` (tt: 00 BTST, 01
      // BCHG, 10 BCLR, 11 BSET). Bit number = the immediate (static, useImm) or srcB=Dn
      // (dynamic). Dest width: LONG (Dn, bit mod 32) or BYTE (memory, bit mod 8) — set
      // by the assembler from the EA. Z-only flag write (preserve N/V/C; X untouched).
      BITOP,
      // PACK/UNPK register forms (68020+, no memory form this slice).
      // PACK Dy,Dx,#adj : src16=(Dy+adj)&0xffff; Dx[7:0]:=(src16[11:8]##src16[3:0]); Dx[31:8] preserved.
      // UNPK Dy,Dx,#adj : src16=Dy&0xffff; Dx[15:0]:=((src16[7:4]##4'b0##src16[3:0])+adj)&0xffff; Dx[31:16] preserved.
      // Size: BYTE (PACK, .B merge writes Dx[7:0]) / WORD (UNPK, .W merge writes Dx[15:0]).
      // adj16 rides `imm` (useImm=True); srcA=Dx (old-value .B/.W merge source),
      // srcB=Dy (the data source), dst=Dx. NO CCR effect.
      PACK, UNPK,
      // Bit-field op (020+, REGISTER form, STATIC offset/width — slice 1). One ALU/
      // shifter slow µop carrying `bfOp` (op[10:8], real 020 encoding): 0=BFTST,1=BFEXTU,
      // 2=BFCHG,3=BFEXTS,4=BFCLR,5=BFFFO,6=BFSET,7=BFINS. srcA=Dy (field reg); BFINS srcB=Dn2; dst=
      // Dn2 (EXTU/EXTS/FFO) / Dy (CHG/CLR/SET/INS) / none (TST). The static offset(5)
      // + width(5, raw, 0->32) are packed into `imm`. Writes NZ (V=C=0, X untouched).
      BITFIELD,
      // Bit-field DYNAMIC offset/width resolve µop (slice 2/3, FAST ALU, lat-1). The
      // leading crack µop of a Do/Dw bit-field: computes the PACKED offset/width that
      // the trailing BITFIELD µop (bfDynamic) reads via srcC=T0. srcA=offset-Dn (read
      // iff Do), srcB=width-Dn (read iff Dw); `imm` carries the static offset(imm[4:0])
      // + static raw-width(imm[9:5]) + Do(imm[10]) + Dw(imm[11]). Result T0 =
      // (Do?srcA[4:0]:imm[4:0]) | ((Dw?srcB[4:0]:imm[9:5]) << 5) — the SAME packed
      // layout the static imm uses (offset[4:0], raw width[9:5]). T0 is a temp dst
      // (kept-for-RAW); the macro architectural commit is the trailing BITFIELD µop.
      BFRESOLVE,
      // CMP2/CHK2 bounds-compare µop (020+, CPLX/DivEu). The 2-load+compare crack puts
      // the LOWER bound in srcA (T0, LS-loaded) and the UPPER bound in srcB (T1, LS-
      // loaded); the compared register Rn rides srcC (psrcC, a normal reg). The EU
      // sign-extends lower/upper from the loaded size, masks/sign-extends Rn per
      // size + the A/D bit (`divSigned` reused as adReg: True=An, no .B/.W sign-ext),
      // and writes the CCR RMW {oldN, Z, oldV, C} (readsNzvc + writesNzvc, preserving
      // N/V). For CHK2 (`isChk2`) an out-of-bounds C raises EuFault{vector 6}; CMP2
      // never traps. Z := Rn==lower||Rn==upper; C := signed(Rn<lower||Rn>upper).
      CMP2CHK2,
      // CAS/CAS2 atomic compare-and-swap compute (020+, ALU EU). The whole instruction
      // is cracked through the v2 microcode engine; CASOP is the compute kernel, with the
      // exact sub-operation selected by the µop's `casForm` field (see DecodedUop.casForm).
      // It reuses the existing CMP NZVC datapath (res = loaded - Dc) for the flags and a
      // match predicate `eq = (loaded.sz == Dc.sz)` for the always-store mux + the Dc
      // merge. NO conditional store (the engine always stores the mux'd value; see the
      // CAS/CAS2 design spec §6). Default-inert for every non-CAS µop.
      CASOP,
      // MOVES (010+ PRIVILEGED move to/from alternate address space). The whole
      // instruction is cracked through the v2 microcode engine; this is just the
      // OperationDecoder marker (the engine picks the WRITE vs READ µop chain from the
      // ext-word dr bit in DecodeStage.ucBegin). The access is FLAT (Musashi `(void)fc`),
      // so the data movement is byte-identical to a normal sized MOVE + the An side effect.
      // Privileged: the first µop carries needsSupervisor (ROB vector-8 if committed S==0).
      MOVES,
      // F-line FP-generic (cpGEN) family: ONE DecOp for the whole hardware-native FP op
      // set, with the concrete operation carried in `fpuOp` (the raw 7-bit extension-word
      // opmode, ext[6:0]) -- the same family+sub-kind idiom SHIFT/shiftOp, BITOP/bitOp,
      // BITFIELD/bfOp and CASOP/casForm already use. Deliberately NOT 12-14 separate
      // DecOp elements: IssueQueuePlugin.scala:914-924 documents a MEASURED post-route
      // case where the `op` field's MuxOH leaking into a second cone became the design's
      // WNS holder, so the enum stays narrow. Routed to Cluster.CPLX (spec Decision 9:
      // fold into the existing CPLX cluster, no new Cluster value, no new IQ/ROB port).
      FPU,
      // ── Task 9b: FMOVEM control-register LIST form, STORE direction ────────────
      // Reads ONE of {FPCR, FPSR, FPIAR} into an integer temp, at EXECUTE time, in the
      // CPLX EU (DivEuPlugin) -- the EU that already owns the FPCC physical register
      // file, which FPSR's condition-code nibble must come from.
      //
      // WHICH register is read is NOT baked into the op: the µop's immediate carries
      // {position[5:4], batch(3), mask[2:0]} and the EU picks the `position`-th SELECTED
      // register out of the mask in the architectural FPCR->FPSR->FPIAR order (M68000PRM
      // p. 5-91, Divergence Register D9). That one runtime mux is what lets all seven
      // register-list masks share three popcount-keyed microcode programs instead of
      // seven mask-keyed ones.
      //
      // Safety of the FPCR/FPSR/FPIAR read itself (design spec
      // docs/superpowers/specs/2026-08-16-fp-control-multiword-transfer-design.md,
      // Decision 3): those three are plain non-renamed Regs whose ONLY writer is
      // ExceptionUnit's S_APPLY, always via a serializing sysOp whose retirement
      // unconditionally squashes + re-fetches everything younger -- so a speculative
      // read of a stale value can never retire. This is structurally the SAME property
      // MmuControlService's urp/srp/dtt0/dtt1 live reads already rely on today
      // (DtlbPlugin/LsEuPlugin/ItlbPlugin). FPCC is the exception and is NOT read that
      // way: it is renamed, so this op declares a REAL `readsFpcc` dependency and is
      // gated by the existing CPLX dynamic-wakeup scoreboard.
      FPCTRLRD
      = newElement()
}

/** Where an FP-generic uop's SOURCE operand comes from (DecodedUop.fpSrcKind).
  *   FPREG    : extension-word opclass 000 -- the source is FP register FPm (fpSrcBReg).
  *   INTREG   : opclass 010 with an integer/single source specifier and a Dn <ea> --
  *              the source is a 32-bit INT register read, riding the ordinary int
  *              srcA/psrcA rename path (no 80-bit value ever enters IqContext).
  *   ROMCONST : opclass 010 / source specifier 111 -- FMOVECR; the source is the FPU's
  *              internal constant ROM, indexed by `imm[6:0]` (the raw offset).
  *   ── Immediate-source forms (this deliverable) ──────────────────────────
  *   INTIMM   : a 32-bit SIGN-EXTENDED integer immediate (Long/Word/Byte source
  *              specifiers all normalize to this -- MicroOpAssembler already did the
  *              sign-extension at decode time). Converts like INTREG, sourced from
  *              fpWideImm(31 downto 0) instead of a register read.
  *   SINGLEIMM: a 32-bit single-precision BIT PATTERN immediate (NOT an integer --
  *              converting it as one would turn 0x3F800000 (1.0f) into 1065353216.0,
  *              a completely wrong result). fpWideImm(31 downto 0).
  *   DOUBLEIMM: a 64-bit double-precision BIT PATTERN immediate. fpWideImm(63 downto 0).
  *   EXTIMM   : an 80-bit extended-precision immediate -- the SAME internal layout as
  *              an FP register (Decision 1), so this is the simplest case: route
  *              fpWideImm(79 downto 0) directly as the extended-precision source, no
  *              format conversion at the EU at all.
  *   Task 6b (memory-source loads) reuses INTREG unmodified for its 1-chunk formats
  *   (Byte/Word/Long/Single via a temp register) and adds two SEPARATE kinds of its
  *   own, MEMPAIR/MEMEXT, for the 2/3-chunk Double/Extended memory loads -- all sharing
  *   this SAME fpSrcFmt-based format-disambiguation mechanism, not a parallel one.
  * FMOVEM and the FMOVE-to-<ea> direction remain unowned by any task in this plan. */
object FpSrcKind extends SpinalEnum {
  val FPREG, INTREG, ROMCONST,
      INTIMM, SINGLEIMM, DOUBLEIMM, EXTIMM,
      // ── Task 6b: genuine memory-source loads (F<op> <mem>,FPn) ─────────────────
      // Both reuse the SAME srcA/srcB/srcC -> psrcA/psrcB/psrcC int-register-read
      // machinery every other CPLX op already uses (Desc.srcC/RenamedUop.psrcC,
      // already fully wired end-to-end through IssueQueuePlugin's CPLX scoreboard/
      // wakeup logic and already read by DivEuPlugin for DIVL's dividend-high word)
      // -- no new EU port, only new dispatch logic in DivEuPlugin (Task 8's Step 5,
      // which switches on these two values).
      //   MEMPAIR : Double-format memory load. srcA=T0(mem+0, hi), srcB=T1(mem+4, lo).
      //             The EU concatenates {srcA,srcB} into a 64-bit IEEE double bit
      //             pattern and converts it to extended via the SAME doubleToExtended
      //             helper the DOUBLEIMM case already needs.
      //   MEMEXT  : Extended-format memory load. srcA=T0(mem+0, sign+exp in [31:16]),
      //             srcB=T1(mem+4, mantissa hi), srcC=T2(mem+8, mantissa lo). NO
      //             numeric conversion -- pure bit placement, {srcA[31:16],srcB,srcC}
      //             IS the 80-bit extended value.
      // Byte/Word/Long/Single memory loads do NOT get a new FpSrcKind: after the
      // crack's load micro-op lands the value in a temp register, the terminal
      // FP-issue uop is INDISTINGUISHABLE from the existing register-source INTREG
      // case (srcAReg points at a temp T0 instead of Dn; the EU-side conversion,
      // dispatched by fpSrcFmt, is identical either way) -- INTREG is reused verbatim.
      MEMPAIR, MEMEXT = newElement()
}

/** Commit-time privileged-system-op kind (DecodedUop.sysOp / .sysKind). Selects how
  * the ExceptionUnit applies the op at the serializing retire. NONE for non-sysOps. */
object SysKind extends SpinalEnum {
  val NONE,
      MOVE_TO_SR,   // <ea>.W -> SR (system byte + CCR); re-banks A7 on an S flip.
      MOVE_USP,     // An <-> USP (direction in sysReadDir).
      MOVEC,        // Rn <-> Rc {VBR/USP/CACR/...} (direction in sysReadDir, Rc in imm).
      // RESET (0x4E70): privileged; asserts the external reset line. Architecturally a NOP
      // (no state change); the FSM consumes it + advances PC (serializing). S=0 -> vector 8.
      RESET,
      // STOP (0x4E72) + imm16: privileged; SR := imm16 (reuses the MOVE-to-SR SR-write +
      // banking) then HALT until an interrupt with level > the new I-mask. SR write is the
      // S_APPLY path; the halt is the ROB `stopped` state. S=0 -> vector 8.
      STOP,         // (RESET=4, STOP=5 — needs the 3-bit FSM ctx widened in T0.)
      // CPUSH (line-1111, top byte 0xF4 — bit[5]=1 selects CPUSH vs CINV's bit[5]=0,
      // per Task P5.1's cross-checked encoding): privileged cache push (writeback of
      // dirty lines) + invalidate.
      // Task P5.2: this decoder now produces CPUSH/CINV as distinct, correctly-decoded
      // kinds (bit[5] selects: 1=CPUSH, 0=CINV), but the actual cache-maintenance effect
      // is STILL a deliberate temporary NOP as of this task — same "no state change,
      // just serialize + advance PC" treatment as RESET. This is NOT a permanent
      // architectural choice: Slice P4 already made the D-cache's copyback/dirty-bit
      // hierarchy fully real, and the rest of THIS slice (P5.4's DcachePlugin
      // maintenance engine + P5.5's ExceptionUnit dispatch) wires CPUSH/CINV into real
      // push-dirty-lines/invalidate-lines effects. S=0 -> vector 8.
      CPUSH,
      // CINV (line-1111, same top byte 0xF4 as CPUSH -- bit[5]=0 selects CINV vs
      // CPUSH's bit[5]=1, per Task P5.1's cross-checked encoding): discard (invalidate)
      // matching cache lines WITHOUT writeback, regardless of dirty state -- the
      // corpus's own cinv_line_basic.s / cpush_line_basic.s header comments are the
      // authoritative spec for this. Like CPUSH above, real cache-maintenance effect
      // lands in P5.4/P5.5; this task only adds correct decode.
      CINV,
      // PFLUSHA (line-1111, 0xF518): privileged "flush all ATC/TLB entries". A REAL
      // effect (unlike CPUSH/RESET) — pulses a flushAll signal that both DtlbPlugin and
      // ItlbPlugin clear their TLB + walk-result latch on, mirroring the existing
      // umFlush top-level fan-out pattern. S=0 -> vector 8. (value 8 as of Task P5.2's
      // CINV insertion — was value 7/"last slot in the old 3-bit field" before PTEST
      // bumped the field to 4 bits; CINV's insertion above bumps every element from
      // here down by one more ordinal. The field stays 4 bits either way — 10 elements
      // still fit.)
      PFLUSHA,
      // PTEST{R,W} (An) (line-1111, 0xF548-0xF54F/0xF568-0xF56F — task #198): privileged
      // MMU-probe "translate An, latch status in MMUSR". This core's MMU has no real
      // per-page R/W/CM/fault status to probe (same "stub MMU" limitation PFLUSHA/PFLUSH
      // already lean on): when the MMU is disabled — the only configuration the ported
      // corpus's ptest_w_an exercises — PA=VA and the page is always "resident" (R=1),
      // which is architecturally EXACT (not an approximation) for that configuration.
      // ExceptionUnit's S_APPLY writes MMUSR := (An & 0xFFFFF000) | 1 (page-aligned
      // address, R bit set, every other status bit 0). Direction is always "write" (An
      // -> MMUSR; sysReadDir=False) — the result is read back separately via a normal
      // MOVEC %mmusr,Rn (Rc id 0x805, a new MmuControlPlugin-owned register). S=0 ->
      // vector 8. (value 9 as of Task P5.2's CINV insertion — was value 8/"the 9th
      // SysKind element" when it first bumped the field from 3 to 4 bits; see
      // ExceptionUnit.scala's `sysKind`/`sysCapKind` + RobPlugin's `.resize(...)` call,
      // all updated together. Still fits comfortably in 4 bits with 10 elements total.)
      PTEST,
      // FMOVE to/from a floating-point CONTROL register (FPCR / FPSR / FPIAR).
      // Opword 0xF200|<ea> (line-F, cpID=001 in op[11:9], opclass 000 in op[8:6]) + a
      // command extension word whose ext[15:13] selects the direction (100 = <ea>->ctrl,
      // 101 = ctrl-><ea>) and ext[12:10] is the one-hot register-select mask
      // {FPCR, FPSR, FPIAR}. NOT a MOVEC variant -- a real dedicated 68881/68040
      // instruction; encoding independently confirmed via `m68k-linux-gnu-as -m68040
      // -m68881` (Task 9 Step 1: F200 9000 / F200 8800 / F200 8400 / F200 B000 /
      // F200 A800 / F200 A400 / F23C 8800 00000000 / F208 8400) and corroborated by the
      // vendored corpus's own documented opwords (fpu_fsave_frestore_idle_roundtrip.s,
      // fpu_fmovem_ctrl_reg.s) and by the real Q700 ROM FPSP prologue pair `F227 BC00`
      // quoted in the design spec.
      //
      // A COMMIT-TIME SYSTEM op, exactly like MOVEC: the FPCR/FPSR/FPIAR copies are
      // non-renamed single-copy state (spec Decision 5), so the read/write happens at the
      // serializing retire in ExceptionUnit's S_APPLY. Unlike EVERY other SysKind it is
      // NOT privileged -- real 68040 FMOVE-to/from-FPcr is a user instruction (only
      // FSAVE/FRESTORE are privileged in this band), so RobPlugin's `sysPrivFault` carries
      // an explicit exclusion for it and `sysTriggerSig` fires regardless of committed S.
      //
      // UNLIKE every other SysKind, this one is NOT produced by `OperationDecoder`:
      // `decode()` sees the OPWORD ONLY (it runs at I-cache refill time, PredecodeWord.
      // scala), and the direction/mask live entirely in the extension word. It is
      // produced by `MicroOpAssembler` instead, which does see `pkt.words(1)` -- the same
      // established precedent ANDI/ORI/EORI #imm,SR already uses (`isToSr` sets sysOp/
      // sysKind directly, with `spec.sysOp` False).
      //
      // Scope: single-register masks with a register-direct <ea> (mode 000 Dn / 001 An)
      // only. Multi-register masks (the FMOVEM control-list form), memory <ea>s, and the
      // `#imm` form all keep the existing vector-11 fall-through.
      FMOVE_FPCTRL,
      // ── Task 9b: FMOVEM control-register LIST form, LOAD direction value capture ──
      // NOT a sysOp. A µop carrying this kind has `sysOp = False`, so it never reaches
      // `sysRetire`/`sysTriggerSig`/`sysPrivFault`/ExceptionUnit at all -- the kind is a
      // pure RETIRE-TIME MARKER on the microcode program's ordinary `MLoad` rows.
      //
      // Why it exists: the load direction's terminal sysOp must apply up to THREE 32-bit
      // values (one per selected control register) in one retirement, but the sysOp
      // side-channel carries exactly one (`RobPlugin.sysValStore(h0)` -> `sysVal`). A
      // marked load's own already-captured `sysValStore` entry is copied, AT ITS
      // IN-ORDER RETIREMENT, into one of RobPlugin's three `sysAux` registers, indexed by
      // the load's destination temp (T0/T1/T2 = arch 16/17/18 -> slot 0/1/2 = the
      // transfer's POSITION in the list). Retirement is strictly in-order and the
      // terminal sysOp is the youngest µop of the same program, so every slot it reads
      // was written by its OWN program's loads and by nothing younger -- the capture
      // cannot be clobbered by an overlapping second FMOVEM.
      //
      // Deliberately a `sysKind` (an existing, already-allocated RobPayload field)
      // rather than a new payload bit: `sysKind` is only ever CONSUMED behind
      // `p0.sysOp` (via `sysRetire`), so an entry carrying it with `sysOp = False` is
      // invisible to every existing consumer.
      FPCTRL_CAP,
      // ── Task 11: FSAVE <ea> (0xF300|<ea>) / FRESTORE <ea> (0xF340|<ea>) ──────────
      // Line-F, cpID = 001 (op[11:9]), opclass 100 (FSAVE) / 101 (FRESTORE) in op[8:6].
      // Encodings re-confirmed via `m68k-linux-gnu-as -m68040 -m68881`:
      //   fsave -(%sp) -> F327 | fsave (%a0) -> F310
      //   frestore (%sp)+ -> F35F | frestore (%a0) -> F350
      // and independently corroborated by the vendored corpus's own raw literals
      // (0xF327 / 0xF35F, commented as FSAVE -(A7) / FRESTORE (A7)+).
      //
      // BOTH ARE PRIVILEGED (real 68040). Unlike FMOVE_FPCTRL above, these need NO
      // `sysPrivFault` exclusion -- the default "any sysOp head retiring at committed
      // S == 0 takes vector 8" behavior is exactly right.
      //
      // COMMIT-TIME SYSTEM ops, deliberately NOT microcoded and deliberately NOT routed
      // through Task 9b's "all memory movement rides ordinary microcode LS rows"
      // mechanism. Both were evaluated and rejected on a real constraint (see
      // docs/superpowers/specs/2026-08-16-fp-control-multiword-transfer-design.md, the
      // post-Task-9b Addendum): straight-line microcode needs a transfer count fixed at
      // PROGRAM-CONSTRUCTION time, and FMOVEM-control's register mask is decode-time
      // resident so it fits -- but FSAVE's frame length is selected at EXECUTE time from
      // live FpuControlPlugin flops (4 vs 44 bytes), and FRESTORE's pop size is
      // discovered from a header byte READ OUT OF MEMORY at runtime, an unavoidable
      // data-dependent branch no straight-line microcode can express. ExceptionUnit's own
      // frame machinery already IS "emit N words where N is runtime-selected, then read a
      // frame back and dispatch on its format byte", so these reuse it -- with a REAL
      // D-side DTLB translation added (the addendum's whole point), time-multiplexed onto
      // the single DTranslationService port via the already-proven `excActive` MUX.
      FSAVE,
      FRESTORE
      = newElement()
}

/** Pre-rename µop: the decode→rename contract. Architectural operands
  * (D0-7 = 0..7, A0-7 = 8..15, T0/T1 temps = 16/17). Reg ids are 5 bits so the
  * cracker's temp targets fit. Rename maps these to physical MicroOp fields. */
case class DecodedUop() extends Bundle {
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val nextPc       = UInt(32 bits)   // POST-instruction PC = pc + length (all µops of an instr share it)
  val op           = DecOp()
  val cluster      = Cluster()
  val size         = Size()
  val memOp        = MemOp()
  val srcAReg      = UInt(5 bits); val srcAValid = Bool()
  val srcBReg      = UInt(5 bits); val srcBValid = Bool()
  // Third source: ONLY the DIVU.L/DIVS.L 64/32 form (the dividend HIGH word Dr); all
  // other µops leave srcCValid=False. Threaded through rename to a 3rd physical source.
  val srcCReg      = UInt(5 bits); val srcCValid = Bool()
  val dstReg       = UInt(5 bits); val dstValid  = Bool()
  val useImm       = Bool();       val imm       = Bits(32 bits)
  val readsNzvc    = Bool();       val readsX    = Bool()
  val writesNzvc   = Bool();       val writesX   = Bool()
  val isBranch     = Bool()
  // Indirect / computed-target branch (JSR/JMP/RTS/RTR): when set, the branch EU
  // forms its target from a SOURCE OPERAND (`psrcA + imm`) instead of `pc+2+disp`,
  // and ALWAYS redirects (unconditional). For JSR/JMP psrcA is the EA base An (imm =
  // displacement / folded absolute / folded PC) — a tiny AGU in the branch EU; for
  // RTS/RTR psrcA is the popped target value (imm = 0). Default False (a plain Bcc/
  // BRA/BSR forms pc+2+disp as before).
  val ibranch      = Bool()
  // Stack-pop postincrement folded into the trailing ibranch (RTS/RTR): when the
  // ibranch has an int dst (dstReg = A7), the branch EU writes A7 := psrcB + anInc
  // (psrcB = the pre-pop A7). anInc = 4 (RTS) / 6 (RTR: word CCR + long PC). 0 = no
  // postinc (JSR/JMP — no An write). 3 bits hold 0/4/6. Default 0.
  val anInc        = UInt(3 bits)
  val cond         = Bits(4 bits)
  val branchDisp   = Bits(32 bits)
  val unimplemented= Bool()
  // Precise-fault capture (exception slice 1): `faulted` marks this µop as raising
  // a synchronous fault at retire; `faultVector` is the m68k exception vector
  // NUMBER (4 = illegal instruction, 8 = privilege violation). Default: no fault.
  val faulted      = Bool()
  val faultVector  = UInt(8 bits)
  // Which PC the exception frame stacks for this fault. Most faults (illegal,
  // privilege, I-fetch) stack the FAULTING instruction's PC (= `pc`, the default).
  // TRAP/TRAPV stack the PC of the NEXT instruction (not restartable): the assembler
  // sets this flag and the ROB stacks `nextPc` instead of `pc` (a 1-bit select keeps
  // the rename/dispatch pipeline narrow — no extra 32-bit field).
  val faultUsesNextPc = Bool()
  // ── Recognized-FPU-instruction software completion (Task 11's FSAVE trigger) ──
  // True for an F-line opword this core RECOGNIZES as the register-to-register FPU
  // general form (Task 6's fpFormIsReg) that is NOT hardware-native (so it is routed to
  // FPSP via Task 6's own faultUsesNextPc mechanism). A subsequent FSAVE, if this bit was
  // the most recent trap, emits the 52-byte unimplemented-instruction frame instead of the
  // 4-byte idle frame. DELIBERATELY NARROWER than Task 6's own faultUsesNextPc gate
  // (fpLenKnown, which covers every cpGEN form including memory-source, Task 6b): Task 11's
  // operand capture reads fpuCmdWord's ext[12:10]/ext[9:7] AS FP REGISTER NUMBERS, which is
  // only a valid interpretation for the register-to-register form. Broadening this bit's
  // population without also fixing Task 11's operand capture would silently address the
  // wrong physical FP register for memory/immediate-source traps. See the note at the top
  // of this task's text for the full argument.
  val fpuSoftwareComplete = Bool()
  // The FPU COMMAND extension word (words(1)) of a recognized FPU instruction matching the
  // predicate above. Zero for everything else. Task 11 stacks this as the unimplemented-
  // instruction state frame's CMDREG1B field, and ALSO reads ext[12:10]/ext[9:7] out of it
  // to address the FP RAT for the frame's operand fields -- captured HERE, at decode,
  // because by the time the frame is emitted (a later FSAVE) the instruction words are
  // long gone.
  val fpuCmdWord = Bits(16 bits)
  // Access-fault (vector 2) extras for the format-$7 frame, used for an
  // INSTRUCTION-FETCH fault (the I-cache raised DecodePacket.fault). `faultAddr` is
  // the faulting fetch PC (the EA stacked in the $7 frame); `sswInstr` set => the
  // fault was an instruction fetch (the exception FSM stacks a program-space SSW
  // instead of data-space). Both default to 0/False (data faults / no fault).
  val faultAddr    = UInt(32 bits)
  val sswInstr     = Bool()
  // Task #211: which cause raised an INSTRUCTION-FETCH fault — True (default) for
  // the pre-existing ITLB/MMU-detected translation fault (non-resident / supervisor
  // I-page), False for a physical AXI bus error (SLVERR/DECERR) on an I-cache
  // REFILL. Meaningful only when `sswInstr` is set; selects ExceptionUnit's SSW ATC
  // bit exactly like LsFault.atc already does for D-side faults (task #189).
  val faultAtc     = Bool()
  // RTE (return-from-exception): a serializing exception-return µop. Default False.
  val isRte        = Bool()
  // isCondTrap: a branch-class execute-time CONDITIONAL trap µop (isBranch + readsNzvc).
  // The branch EU evaluates the 16-condition `cond` field (via `taken`); if taken it
  // drives a trapvFault (vector 7, faultPc = nextPc). If not taken it retires as a no-op.
  // TRAPV (0x4E76) uses isCondTrap with cond=9 (VS, V=1). TRAPcc uses cond=cccc.
  // The µop NEVER causes a branch redirect regardless of `taken` (the EU suppresses it).
  val isCondTrap   = Bool()
  // ── CPLX-cluster (DivEu) control (CHK / DIV) ────────────────────────────────
  // `divSigned` = DIVS (vs DIVU) / CHK is always signed-compare. `div64` = the
  // 64-bit-dividend form (Dr:Dq); `divForm` selects the writeback/iteration width.
  // For the 64/32 + 32/32-with-remainder forms the decode CRACKS into a DIV µop
  // (quotient -> Dq) + a DIVREM µop (the latched remainder -> Dr); `divIsRem` marks
  // the trailing remainder-move µop. These default to harmless values for non-CPLX.
  val divSigned    = Bool()
  val div64        = Bool()     // 64-bit dividend (Dr:Dq) form
  val divIsRem     = Bool()     // this µop is the trailing remainder-move (DIVREM)
  // ── CMP2/CHK2 bounds-compare sub-kind (DecOp.CMP2CHK2) ───────────────────────
  // isChk2: True = CHK2 (out-of-bounds C raises EuFault{vec6}); False = CMP2 (flags
  // only, never traps). The A/D bit (whether Rn is a data or address reg, for the
  // .B/.W sign-extension rule) is carried in `divSigned` (reused: True = An, NOT
  // sign-extended for .B/.W; False = Dn, sign-extended). Default False.
  val isChk2       = Bool()
  // ── Stack-push store (BSR/JSR call: push return-PC to -(A7)) ────────────────
  // A predecrement-store µop: address = base An (psrcA) - sizeBytes; the STORE DATA
  // is the immediate (`imm` carries retPC = nextPc, NOT a displacement); and the
  // µop's single int dst = the SAME predecremented address (the new A7) — a store
  // has no data dst, so this lone int write is the A7 side-effect. Reuses one int
  // dst + the imm field (no 2nd write port / no 2nd 32-bit field). Default False.
  val stkPush      = Bool()
  // ── EA auto-update (general -(An)/(An)+, modes 4/3) ─────────────────────────
  // A mem µop whose base An gets `An := An ± eaDelta` folded in (generalizing stkPush
  // to ANY An and size). POSTINC: access addr = An (psrcA), An := An + eaDelta.
  // PREDEC: access addr = An - eaDelta (the same addr stkPush computes for A7, now any
  // delta), An := An - eaDelta. The An write rides the µop's int dst (dstReg=An) when
  // the µop produces the An value (a STORE / RMW-store with no data dst, or a dedicated
  // An-update mem µop); a LOAD that ALSO produces a value writes the value to its dst
  // and the An update is a SEPARATE crack µop (the load can write only one int reg).
  // eaAuto is carried on BOTH the load AND the store of a predec RMW so they compute the
  // SAME decremented address; only the An-writer carries the int dst. NONE/0 = no update.
  val eaAuto       = EaAuto()
  val eaDelta      = UInt(3 bits)
  // ── RTR CCR-restore load (pop the saved CCR word) ──────────────────────────
  // A LOAD µop that, instead of writing an int reg, RESTORES the CCR from the loaded
  // word's low byte: NZVC := data[3:0], X := data[4]. RTR restores ONLY the CCR (SR
  // low byte), never the system byte. The LS EU writes the renamed NZVC + X PRFs.
  // Default False. (writesNzvc/writesX on this µop select the flag dests.)
  val ccrRestore   = Bool()
  // ── ANDI/ORI/EORI #imm,CCR (the non-privileged to-CCR forms) ────────────────
  // An ALU-cluster µop that READS the current CCR {X,N,Z,V,C} and WRITES it back:
  // ccr5' = ccr5 <op> imm[4:0], op = AND/OR/EOR (in `op`), imm[4:0] in `imm`. It
  // reads NZVC (pNzvcSrc) + X (pXSrc) and writes NZVC (pNzvcDst) + X (pXDst); no int
  // dst. The ALU EU assembles ccr5, applies the logical op, and splits the result
  // back into NZVC/X. Default False (every other op leaves it clear). CCR only — the
  // privileged system-byte (to SR) forms are deferred.
  val toCcr        = Bool()
  // ── Line-E shift/rotate control (DecOp.SHIFT) ───────────────────────────────
  // shiftOp = tt (0=ASL/ASR, 1=LSL/LSR, 2=ROXL/ROXR, 3=ROL/ROR); shiftDir = d
  // (1=left). The count is useImm/imm (i=0, ccc 1-8 with 0->8) or srcB=Dc (i=1).
  // Default 0/False (non-shift µops). Threaded through rename to the ALU EU.
  val shiftOp      = Bits(2 bits)
  val shiftDir     = Bool()
  // ── Packed-BCD add/subtract sub-kind (DecOp.BCD) ─────────────────────────────
  // bcdSub: False = ABCD (Dx := BCD(Dx + Dy + X)); True = SBCD (Dx := BCD(Dx - Dy - X)).
  // BYTE only. The ALU EU runs the decimal-adjust datapath + the BCD flag merge (C/X =
  // decimal carry/borrow, clear-only Z, Musashi-exact N/V). Default False.
  val bcdSub       = Bool()
  // ── Bit op (BTST/BCHG/BCLR/BSET): tt = 00 BTST, 01 BCHG, 10 BCLR, 11 BSET. ──────
  // The bit number is the immediate (static form, useImm) or srcB=Dn (dynamic). Dest
  // width: LONG (Dn, bit mod 32) or BYTE (memory, bit mod 8) — set by the assembler
  // from the EA. The ALU EU runs the bit-op datapath + a Z-only flag write. Default 0.
  val bitOp        = Bits(2 bits)
  // ── Bit-field op sub-kind (DecOp.BITFIELD): bfOp = op[10:8] ─────────────────
  // 0=BFTST,1=BFEXTU,2=BFCHG,3=BFEXTS,4=BFCLR,5=BFFFO,6=BFSET,7=BFINS. The static
  // offset(5b) + raw width(5b, 0->32) are packed into `imm` (imm[4:0]=offset,
  // imm[9:5]=width). Default 0. The ALU EU's bit-field datapath keys off bfOp.
  val bfOp         = Bits(3 bits)
  // ── Bit-field DYNAMIC marker (DecOp.BITFIELD, Do||Dw) ────────────────────────
  // When set, the BITFIELD µop is the trailing half of a Do/Dw crack: the EU takes
  // offset = srcC[4:0] and raw-width = srcC[9:5] (the BFRESOLVE-packed T0 read via
  // psrcC) INSTEAD of the static imm. False = the slice-1 static form (offset/width
  // from imm). Default False.
  val bfDynamic    = Bool()
  // ── Bit-field MEMORY load-only marker (DecOp.BITFIELD, mem EA, slice 3a) ─────
  // When set, this BITFIELD µop is the trailing compute half of a memory bit-field
  // load crack: srcA = T0 (the misaligned LONG `lo` loaded at byteAddr), srcB = T1
  // (the spill BYTE `hi` at byteAddr+4, valid only when bitOff+width>32). The ALU EU
  // funnels field32 = (lo<<bitOff) | (needHi ? hi>>(8-bitOff) : 0) and feeds the
  // datapath with rotate offset=0, ffoBase=origOffset (for BFFFO). The static
  // bitOff/needHi/origOffset are packed into imm: imm[12:10]=bitOff, imm[13]=needHi,
  // imm[18:14]=origOffset (imm[4:0]=0 rotate offset, imm[9:5]=rawWidth — same layout
  // as the register-form static imm). Default False (register form). Only the
  // load-only ops (BFTST/BFEXTU/BFEXTS/BFFFO) use this; the RMW mem forms (slice 3b)
  // set it too AND additionally carry a bfStoreForm marker (below).
  val bfMem        = Bool()
  // ── Bit-field RMW STORE-FORM marker (DecOp.BITFIELD bfMem, slice 3b) ─────────
  // The memory RMW ops (BFCHG/BFCLR/BFSET/BFINS) route through the v2 microcode
  // engine. Their compute µops are different outputs of the SAME inverse-funnel
  // datapath, selected here (the funnel field32 = (lo<<bitOff)|(needHi?hi>>(8-bitOff):0),
  // res = chg/clr/set/ins over field32, lomask = bitOff==0 ? 0 : 0xffffffff<<(32-bitOff),
  // himask = 0xff>>bitOff):
  //   0 = RES  : output = res (the modify result) + NZ flags from field32. The load-only
  //              3a value AND the 5-byte chain's separate res compute (b2; srcC=Dn2 BFINS).
  //   1 = LO4  : the 4-byte chain's COMBINED compute. Computes res INTERNALLY from the
  //              funnel (lo = srcA = T0, needHi=False) + carries the NZ flags, and outputs
  //              lo' = (lo & lomask) | (res >> bitOff). BFINS insert source Dn2 rides srcC.
  //   2 = LO5  : the 5-byte chain's lo' = (lo & lomask) | (res >> bitOff), where lo = srcA
  //              (T0) and res = srcB (T2, the b2 result). NO flags (the b2 RES µop wrote them).
  //   3 = HI5  : the 5-byte chain's hi' = (hi & himask) | ((res << (8-bitOff)) & 0xff), where
  //              hi = srcA (T1) and res = srcB (T2). NO flags. The stored spill BYTE.
  // Default 0 (RES / load-only / non-bit-field).
  val bfStoreForm  = UInt(3 bits)
  // ── Line-4 EXT/EXTB source-width marker (DecOp.EXT) ──────────────────────────
  // EXT sign-extends the low byte/word of Dn. `extByte` = the source is a BYTE
  // (Dn[7:0]) rather than a word (Dn[15:0]): EXT.W (byte->word, size WORD, extByte)
  // + EXTB.L (byte->long, size LONG, extByte). EXT.L (word->long, size LONG) leaves
  // extByte=False. Default False (every non-EXT op). The destination width is `size`.
  val extByte      = Bool()
  // MOVEA (MOVE / MOVEQ-class with an ADDRESS-register destination): An is ALWAYS
  // written full-32 and sets NO flags; the .W form SIGN-EXTENDS the 16-bit source to
  // 32 bits. The ALU EU keys off this to bypass the .B/.W partial-register merge
  // (which is for DATA-reg destinations only) and to sign-extend a .W MOVEA source.
  // Default False (every other op, incl. MOVE-to-Dn, leaves it clear).
  val isMovea      = Bool()
  // ── Line-5 Scc / DBcc (branch-EU condition-path µops) ───────────────────────
  // Scc (`0101 cccc 11 000rrr`): set Dn[7:0] := cond(cccc) ? 0xFF : 0x00, preserve the
  // upper 24 bits, NO flags. A branch-class µop (isBranch, readsNzvc, cond=cccc) that
  // reads its destination Dn (psrcA, the merge source) and writes it (pdst); it NEVER
  // redirects (not a control transfer). Default False.
  val isScc        = Bool()
  // DBcc (`0101 cccc 11001rrr` + disp16): decrement-and-branch. If cond FALSE -> Dn.W
  // -= 1 (partial, preserve Dn[31:16]); branch to pc+2+disp if Dn.W (after dec) != -1.
  // If cond TRUE -> Dn unchanged, fall through. A branch-class µop (isBranch, readsNzvc,
  // cond=cccc) reading Dn (psrcA) + writing Dn (pdst, the merged decrement-or-unchanged)
  // + a PC-relative redirect gated on `!cond && (decW != -1)`. Default False.
  val isDbcc       = Bool()
  // Macro-instruction boundary marker: True for the FIRST µop of an instruction.
  // The cracker emits 1 or 2 µops/instruction; the only 2-µop case is a memSimple
  // source crack -> [load, op] where the LOAD is first. An interrupt may be taken
  // only when the ROB head is a first µop (never mid-cracked-instruction). Default
  // True (every single-µop instruction is its own first µop).
  val firstOfInstr = Bool()
  // ── Brief-format indexed EA (the AGU index term) ────────────────────────────
  // For a mem µop whose address is an indexed EA, the index register rides srcCReg/
  // srcCValid (psrcC after rename), and these two fields tell the LS-EU AGU how to
  // size+scale it: indexLong => use the full 32-bit Xn (else sign-extend Xn[15:0]);
  // indexScale = the 2-bit scale exponent (0=*1,1=*2,2=*4,3=*8). EA = base + disp +
  // (Xn sized) << indexScale. NONE/0 for every non-indexed µop (srcCValid gates it).
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
  // ── LEA address-generate (DecOp.MOVE, LS cluster) ───────────────────────────
  // Compute the control-EA ADDRESS (base + disp + Xn*scale, via the LS-EU AGU) and
  // write it to the int dst — NO memory access, NO translate (so it never faults).
  // The LS EU completes it in IDLE with s1Va as the result (reusing the stkPush
  // address-writeback precedent). Default False.
  val leaAddr  = Bool()
  // ── MOVES write-form Rn==An aliasing (A2 fix) ───────────────────────────────
  // The MOVES write µop (STORE.sz Rn -> (ea) with the (An)+/-(An) auto side effect
  // folded into the SAME store) reads Rn and computes the auto-updated An in one
  // atomic cycle. When Rn IS the EA's An register, Musashi computes the EA (mutating
  // An FIRST via the GET_EA_AY macro) and only THEN reads the register for the store
  // data — so the byte pattern written to memory is the NEW (auto-updated) An value,
  // not the value Rn held before this instruction. True only for that one ROM row
  // (µPC45) AND only when Rn statically aliases the EA base register (decode-time
  // comparison of two opcode-field-derived register numbers, not a runtime value).
  // The LS-EU muxes its store-data source between the raw register read and the
  // already-computed auto-update value (s1AnWb) on this bit. Default False.
  val movesAliasStore = Bool()
  // ── MOVE from/to SR/CCR (ALU cluster) ───────────────────────────────────────
  // fromCcr: the int result = the CCR byte {X,N,Z,V,C} zero-extended (.W). fromSr:
  // the int result = the 16-bit SR = {srSysIn, CCR byte} zero-extended (.W). Both
  // READ NZVC+X (the toCcr read ports). needsSupervisor: a privileged op (MOVE-from-
  // SR) — the ROB converts the head to a faulted vector-8 entry at retire if the
  // committed S bit is 0. Default False (every other op).
  val fromCcr  = Bool()
  val fromSr   = Bool()
  val needsSupervisor = Bool()
  // ── Lock-step macro-commit marker (sim whitebox only; no hardware effect) ────
  // Forces the lock-step whitebox to KEEP this µop's commit as the macro instruction's
  // single oracle step, even though it writes only a TEMP / is an otherwise-dropped crack
  // µop (stkPush / rmwStore). Used by ops whose every µop would otherwise be dropped so
  // the instruction vanishes from the commit stream: PEA (push T0 -> -(A7), the kept A7-
  // updating commit) and the mem-dest MOVE-from-CCR/SR op µop (-> T1, the kept step; its
  // trailing store is dropped). The EUs OR it into wbObs.keepCommit. Default False.
  val keepCommit = Bool()
  // ── Commit-time PRIVILEGED SYSTEM ops (MOVE-to-SR / MOVE-USP / MOVEC) ─────────
  // These ops write/read COMMITTED architectural system state (srSys/usp/isp/msp/vbr/
  // cacr) owned by the commit-side ExceptionUnit/SystemState — they CANNOT execute
  // out-of-order. `sysOp` marks such a µop: the ROB retires it ALONE (serializing,
  // like RTE), the ExceptionUnit applies the effect at retire (re-banking A7 +
  // redirecting younger work), and a committed S=0 converts it into a vector-8
  // privilege-violation trap (the check is at COMMIT because S is committed state).
  //   sysKind  : which system op (SysKind enum below).
  //   sysReadDir: read SYSTEM-reg -> Rn (True) vs write Rn -> SYSTEM-reg (False).
  // For a WRITE the source register rides the normal srcA (read in the OoO datapath,
  // its writeback VALUE captured per-ROB-entry like nzvcValStore -> fed to the FSM).
  // For a READ the destination Rn rides the normal dstReg/dstValid (the FSM writes
  // the int PRF arch-Rn with the committed system value). The MOVEC control-reg id
  // (12-bit Rc) rides `imm` (no value source competes for it on a MOVEC). Default:
  // not a sysOp. (Reuses srcA/dstReg/imm to keep the rename/dispatch width minimal.)
  val sysOp        = Bool()
  val sysKind      = SysKind()
  val sysReadDir   = Bool()
  // ── Fetch-time branch prediction carry-down (BTB + bimodal, slice 1) ─────────
  // predTaken : this µop's instruction was predicted-taken at fetch (the front-end
  //   redirected to predTarget on its behalf). predTarget : the redirected-to target
  //   (meaningful iff predTaken). Both ride DecodePacket -> here -> RenamedUop ->
  //   IqContext -> branch EU `u1`, where the branch EU computes mispredict =
  //   (predTaken != actualTaken) || (actualTaken && actualTarget != predTarget).
  //   Every builder defaults these to False/0 (non-branch / not-predicted); the
  //   DecodeStage choke point STAMPS the real fetch-time values onto a packet's µops.
  val predTaken    = Bool()
  val predTarget   = UInt(32 bits)
  // ── gshare direction-predictor carry-down (slice 3) ─────────────────────────
  // phtValid : a CONDITIONAL gshare-predicted branch carrying its fetch-time 11-bit
  //   folded-XOR `phtIndex` to retire (the ROB trains pht[phtIndex] := saturate±1
  //   (actualTaken)). Non-conditional / not-gshare-predicted µops carry phtValid=False.
  val phtValid     = Bool()
  val phtIndex     = UInt(11 bits)
  // ── CAS/CAS2 compute sub-form (DecOp.CASOP) ─────────────────────────────────
  // Selects which CAS/CAS2 compute kernel this µop runs in the ALU EU. The two
  // address-loaded operands ride srcA (= the loaded memory value, T0/T1) and srcB
  // (= the compare register Dc/Dc1/Dc2); srcC carries either Du (CASS) or the packed
  // eq/bothEq status temp (the CAS2 forms). `eq = (srcA.sz == srcB.sz)` is the match.
  //   CASS  : store-data    = eq ? Du(srcC) : T0(srcA)            ; no flags.
  //   CASC  : Dc'           = eq ? Dc(srcB) : merge.sz(Dc,T0)     ; NZVC = cmp(T0,Dc).
  //   CAS2C1: status T2[0]  = eq1                                  ; NZVC = cmp(T0,Dc1).
  //   CAS2C2: status T2     = {bothEq[2],eq2[1],eq1[0]} (eq1 from srcC[0]);
  //           NZVC          = eq1 ? cmp(T1,Dc2) : oldNZVC (reads NZVC to preserve).
  //   CAS2DC: Dc'           = bothEq(srcC[2]) ? Dc(srcB) : casUpd.sz(Dc,T0,daBit=imm[0]);
  //           no flags (a dropped crack µop; the PRF write still lands).
  //   CAS2SEL: store-data   = bothEq(srcC[2]) ? Du(srcB) : T0(srcA) ; no flags.
  // imm[0] carries the Rn D/A bit (BIT_1F/BIT_F) for the CAS2.W Dc sign-extend rule.
  // Default 0 (a non-CAS µop never reads casForm — gated by op === CASOP).
  val casForm      = Bits(3 bits)
  // ── F-line FP-generic operand routing (DecOp.FPU) ───────────────────────────
  // Architectural FP register numbers (FP0-FP7, 3 bits) -- rename maps them to the
  // 4-bit physical FP tags in RenameStage (Task 2's fpRat). Split exactly like the
  // integer srcA/srcB/dst convention this decoder already uses:
  //   fpSrcAReg / usesFpSrcA : the DESTINATION FPn read back as an operand. Set ONLY
  //     for the DYADIC ops (FADD/FSUB/FMUL/FDIV/FCMP), which compute FPn <op> src.
  //     The monadic ops (FMOVE/FABS/FNEG/FSQRT/FINT/FINTRZ/FTST/FMOVECR) do NOT read
  //     their destination -- their result is a function of the source alone -- so
  //     usesFpSrcA stays False for them (an unnecessary source would create a false
  //     RAW dependency and serialize independent FP work for nothing).
  //   fpSrcBReg / usesFpSrcB : the SOURCE FPm, valid only for the register-to-register
  //     form (fpSrcKind === FPREG). For INTREG the source rides srcAReg/psrcA (int
  //     rename); for ROMCONST there is no register source at all.
  //   fpDstReg / writesFp    : the destination FPn. False for FCMP and FTST, which
  //     write ONLY the condition codes.
  val fpSrcAReg   = UInt(3 bits); val usesFpSrcA = Bool()
  val fpSrcBReg   = UInt(3 bits); val usesFpSrcB = Bool()
  val fpDstReg    = UInt(3 bits); val writesFp   = Bool()
  // Renamed FPCC {N,Z,I,NAN} (spec Decision 4). EVERY hardware-native FP op writes it
  // (Musashi calls SET_CONDITION_CODES on every arm, including FMOVE-to-FPn and
  // FMOVECR). NOTHING reads it yet -- the first readers are FBcc/FScc/FDBcc and
  // FMOVE-from-FPSR, all deferred -- but the field and its IQ scoreboard (Task 3)
  // exist now so that path is a pure addition later.
  val readsFpcc   = Bool(); val writesFpcc = Bool()
  // The raw extension-word opmode, ext[6:0], verbatim (0x00 FMOVE, 0x01 FINT, 0x03
  // FINTRZ, 0x04 FSQRT, 0x18 FABS, 0x1A FNEG, 0x20 FDIV, 0x22 FADD, 0x23 FMUL,
  // 0x28 FSUB, 0x38 FCMP, 0x3A FTST -- confirmed against tools/musashi/musashi/
  // m68kfpu.c's fpgen_rm_reg opmode switch). For FMOVECR this field is NOT an opmode
  // (the ROM offset rides `imm` instead) -- gate on fpSrcKind === ROMCONST first.
  val fpuOp       = Bits(7 bits)
  val fpSrcKind   = FpSrcKind()
  // The raw extension-word source SPECIFIER (ext[12:10]), verbatim. Meaningful whenever
  // fpSrcKind is one of {INTREG, INTIMM, SINGLEIMM, DOUBLEIMM, EXTIMM} (every opclass-010
  // form); ignored by the EU for FPREG/ROMCONST. This is the field that RESOLVES the
  // Long-vs-Single ambiguity this task previously left open for Task 8 (see that section's
  // updated note): `size` alone cannot distinguish a 32-bit INTEGER from a 32-bit BIT
  // PATTERN, but fpSrcFmt (000 vs 001) can.
  val fpSrcFmt  = Bits(3 bits)
  // The immediate VALUE for every fpWideImm-routed fpSrcKind above, right-justified /
  // zero-padded to 80 bits regardless of the real format width (32/64/80 bits meaningful,
  // per fpSrcFmt). Carried through rename/IQ exactly like `imm` already is -- IqContext
  // embeds the WHOLE RenamedUop, so this costs nothing beyond its own bit-width, the same
  // class of cost as `imm`/`fpuCmdWord`. Deliberately NOT reusing `imm` (32 bits, and
  // already committed to FMOVECR's ROM offset) -- see this task's routing-contract note.
  val fpWideImm = Bits(80 bits)

  /** Drive every FP field to its inert (non-FP-uop) default. Called by every
    * DecodedUop construction site that is not building an FP uop -- SpinalHDL requires
    * every bundle field to be driven (PhaseCheck_noLatchNoOverride), and there are
    * multiple such sites across MicroOpAssembler/Microcode/MicroOpQueue/DecodeStage. */
  def fpInert(): Unit = {
    fpSrcAReg := 0; usesFpSrcA := False
    fpSrcBReg := 0; usesFpSrcB := False
    fpDstReg  := 0; writesFp   := False
    readsFpcc := False; writesFpcc := False
    fpuOp     := 0; fpSrcKind := FpSrcKind.FPREG
    fpSrcFmt  := 0; fpWideImm := B(0, 80 bits)
  }
}

/** CAS/CAS2 compute sub-form codes (DecodedUop.casForm). */
object CasForm {
  val CASS    = 0   // store-data mux (single CAS)
  val CASC    = 1   // Dc compare+merge + NZVC (single CAS)
  val CAS2C1  = 2   // first compare (NZVC=res1; eq1 -> status)
  val CAS2C2  = 3   // second compare (final NZVC; bothEq/eq2 -> status)
  val CAS2DC  = 4   // Dc update on mismatch (bothEq?Dc:update)
  val CAS2SEL = 5   // store-data mux (CAS2)
}
