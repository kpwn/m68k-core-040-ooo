package m68k040.decode

import m68k040.isa.{Cluster, Size, MemOp}
import spinal.core._

/** Straight-line microcode engine (v1).
  *
  * A ROM-driven generalization of the MOVEM micro-sequencer (the DecodeStage FSM): a
  * compile-time Scala table of µop DESCRIPTORS (a µop template + operand SELECTORS +
  * an isLast marker), resolved by a hardware mux from a latched-instruction CONTEXT.
  * The DecodeStage sequencer walks the ROM from `ucEntry`, emitting `resolve(rom(ucPc),
  * ctx)` each cycle (1/cycle in v1) until the descriptor with `isLast` releases `fed`.
  * `pipeFlush` aborts (the queue flush squashes the partial µops; the op re-decodes from
  * scratch on re-fetch) — exactly the MOVEM FSM contract, parameterized by the ROM.
  *
  * v1 = STRAIGHT-LINE only: nextUpc = µPC+1, a single `isLast`; NO data-dependent
  * loop/branch. The data-dependent cold ops (MOVEM's mask loop, bit-field, CAS retry)
  * stay bespoke FSMs until a v2 loop primitive (conditional nextUpc + a loop counter).
  * The ROM is ready for the next STRAIGHT-LINE cold customers (MOVEC/MOVES/STOP/PACK-
  * UNPK): add rows + an entry constant + the selectors they need.
  *
  * FIRST CUSTOMERS: the ABCD/SBCD/ADDX/SUBX -(Ay),-(Ax) MEMORY forms (6 µops each):
  *   [load(Ay)->T0][Ay-=d (drop)][load(Ax)->T1][Ax-=d (drop)][op T1,T0->T2 +flags][store T2->(Ax)]
  * — exceeding the 3-µop fast crack budget, which is exactly why they are µcode.
  */
object Microcode {

  // Engine temps. T0/T1 are the existing int temps the mem-RMW/predec cracks reuse
  // (MicroOpAssembler.T0/T1 = 16/17); T2 is the 3rd temp (the bit-field RMW `res`, kept
  // distinct from the two loaded operands lo/hi). The DecodedUop reg-id space is 5 bits,
  // so 18 fits the temp pool. The int RAT/Freelist depth is now ARCH_INT_REGS = 19.
  val T0 = MicroOpAssembler.T0   // 16
  val T1 = MicroOpAssembler.T1   // 17
  val T2 = MicroOpAssembler.T2   // 18 — the bit-field 5-byte-chain `res` temp

  /** Operand-selector vocabulary (the ROM's small mux set), resolved by the engine from
    * the latched opword fields + size. */
  sealed trait Sel
  case object SNone       extends Sel
  case object SAy         extends Sel   // 8 + op[2:0]   (source An / Ay)
  case object SAx         extends Sel   // 8 + op[11:9]  (dest   An / Ax)
  case object ST0         extends Sel
  case object ST1         extends Sel
  case object ST2         extends Sel   // the 3rd temp (bit-field RMW `res`)
  case object SNegDeltaAy extends Sel   // -deltaAy (Ay predec write-back imm, LONG)
  case object SNegDeltaAx extends Sel   // -deltaAx (Ax predec write-back imm, LONG)
  case object SDeltaAy    extends Sel   // +deltaAy (Ay postinc write-back imm, LONG — CMPM)
  case object SDeltaAx    extends Sel   // +deltaAx (Ax postinc write-back imm, LONG — CMPM)
  // ── v2 bit-field RMW selectors ─────────────────────────────────────────────
  case object SEaBase     extends Sel   // (eaBase, eaBaseValid) — the bit-field EA base An
  case object SDn2        extends Sel   // (bfDn2, True) — the BFINS insert source register
  case object SEaDispLo   extends Sel   // selImm: the byteAddr disp (EA disp + offset>>3)
  case object SEaDispHi   extends Sel   // selImm: the byteAddr+4 disp (the spill byte)
  case object SBfImm      extends Sel   // selImm: the packed bfMem imm (bitOff/needHi/width/origOff)
  // ── full-format MEMORY-INDIRECT host-op selectors ──────────────────────────
  case object SMiOther    extends Sel   // (miOther, miOtherValid) — the host op's other reg (Dn/Dm)
  case object SMiOd       extends Sel   // selImm: the outer displacement od (host access disp)
  case object SMiImm      extends Sel   // selImm: the host op immediate (line-0 imm op)
  // ── CAS / CAS2 selectors ────────────────────────────────────────────────────
  case object SCasDc      extends Sel   // (casDc, True)   — CAS compare reg Dc = ext[2:0]
  case object SCasDu      extends Sel   // (casDu, True)   — CAS update reg  Du = ext[8:6]
  case object SCas2Rn1    extends Sel   // (cas2Rn1, True) — CAS2 addr reg 1 (REG_DA ext1[15:12])
  case object SCas2Rn2    extends Sel   // (cas2Rn2, True) — CAS2 addr reg 2 (REG_DA ext2[15:12])
  case object SCas2Dc1    extends Sel
  case object SCas2Du1    extends Sel
  case object SCas2Dc2    extends Sel
  case object SCas2Du2    extends Sel
  case object SCas2Da1    extends Sel   // selImm: bit0 = ext1[15] (BIT_1F, the Rn1 D/A bit)
  case object SCas2Da2    extends Sel   // selImm: bit0 = ext2[15] (BIT_F,  the Rn2 D/A bit)
  // ── MOVES selectors ──────────────────────────────────────────────────────────
  case object SMovesRn    extends Sel   // (movesRn, True) — the moved register Rn = REG_DA[ext15:12] (0..15)
  case object SMovesAn    extends Sel   // (eaBase An, casAutoMode =/= NONE) — the (An)+/-(An) write-back target
  case object SMovesDelta extends Sel   // selImm: the SIGNED An delta (+size POSTINC / -size PREDEC / 0)

  /** The op kind of a descriptor's template. */
  sealed trait UOp
  case object UMove      extends UOp    // plain move / load / store data move (DecOp.MOVE)
  case object UAddDrop   extends UOp    // ADD.L dst:=srcA+imm, divIsRem (dropped An write-back)
  case object UOpFromCtx extends UOp    // the latched op (BCD/ADDX/SUBX), flags from ctx
  case object UBfMem     extends UOp    // BITFIELD bfMem compute (RES/LO/HI funnel form); op=BITFIELD
  // ── full-format MEMORY-INDIRECT host-op kinds ──────────────────────────────
  case object UMiPtrLoad extends UOp    // LOAD.L pointer (eaBase + eaDispLo (+ pre-index)) -> T0
  case object UMiHostMove extends UOp   // host MOVE load/store at (T0 + od (+post-index)); op=MOVE
  case object UMiHostOp  extends UOp     // host ALU/unary compute (ctx.miOp): srcA,srcB -> dst + flags
  // ── CAS / CAS2 compute kind. The Desc carries the casForm + the flag mask; the op is
  //    DecOp.CASOP and the ALU EU runs the compare/merge/select datapath. ──────────────
  case class UCasOp(form: Int, writesNzvc: Boolean = false, readsNzvc: Boolean = false,
                    dropCommit: Boolean = false) extends UOp
  // MOVES read writeback: MOVE T0 -> Rn. An (ext15=1): sign-extend.sz(T0) to 32 (isMovea
  // path, extended to .B). Dn (ext15=0): size-merge.sz(Dn, T0) (read Rn as srcA = the merge
  // source). The An-vs-Dn choice + isMovea is resolved from ctx.movesRnIsA. NO CCR write.
  case object UMovesRead extends UOp

  /** Memory role. */
  sealed trait Mem
  case object MNone  extends Mem
  case object MLoad  extends Mem
  case object MStore extends Mem

  /** Auto-update (PREDEC) of the µop's base An, keyed to which An. */
  sealed trait Auto
  case object ANoAuto    extends Auto
  case object APredecAy  extends Auto
  case object APredecAx  extends Auto
  case object APostincAy extends Auto   // (Ay)+ — CMPM: addr = Ay (unmodified); delta write-back separate
  case object APostincAx extends Auto   // (Ax)+ — CMPM: addr = Ax (unmodified); delta write-back separate
  // CAS auto-inc/dec EA: the LOAD + STORE both carry eaAuto/eaDelta from the latched ctx
  // (ctx.casAutoMode/Delta) so they compute the SAME effective address (matching Musashi's
  // M68KMAKE_GET_EA_AY single side-effect); the An := An ± size write-back rides the STORE
  // (AEaCasStore writes the int dst = eaBase An when ctx.casAutoMode =/= NONE). NONE for the
  // plain control modes (no An side effect) -> a harmless inert eaAuto.
  case object AEaCasLoad  extends Auto   // load: eaAuto from ctx, NO An write (dst = T0)
  case object AEaCasStore extends Auto   // store: eaAuto from ctx + An write-back (dst = eaBase)

  /** Explicit µop size for a row (overrides the ctx-size default). The bit-field chain
    * rows are LONG (lo load/store, the compute) or BYTE (the hi spill-byte load/store);
    * the BCD chain rows use the ctx-size default (SzCtx). */
  sealed trait Sz
  case object SzCtx  extends Sz   // = ctx.size (BCD chain)
  case object SzLong extends Sz
  case object SzByte extends Sz
  case object SzHost extends Sz   // = ctx.miHostSize (the full-format mem-indirect host access)

  /** One ROM row: a µop template + operand selectors + sequencing. */
  case class Desc(
      uop:    UOp,
      mem:    Mem     = MNone,
      auto:   Auto    = ANoAuto,
      srcA:   Sel     = SNone,
      srcB:   Sel     = SNone,
      srcC:   Sel     = SNone,    // 3rd operand: BFINS insert source (Dn2) for the RES/LO4 compute
      dst:    Sel     = SNone,
      useImm: Boolean = false,
      imm:    Sel     = SNone,    // when useImm, the imm comes from this selector
      sz:     Sz      = SzCtx,    // explicit µop size (SzCtx = ctx.size, the BCD default)
      writesFlags: Boolean = false,  // reads X+old-Z, writes NZVCX (the op µop)
      nzvcOnly:    Boolean = false,  // writes NZVC only (X UNTOUCHED, no NZVC/X read) — CMPM
      indexFromEa: Boolean = false,  // LS row: srcC + indexLong/indexScale come from Ctx EA
      bfStoreForm: Int     = 0,      // UBfMem: 0=RES,1=LO4,2=LO5,3=HI5 (the funnel form)
      bfWritesNz:  Boolean = false,  // UBfMem: this compute writes the NZ flags (RES / LO4)
      miPtrIndex:  Boolean = false,  // UMiPtrLoad: add the PRE-index (eaIndex) to the pointer addr
      miHostIndex: Boolean = false,  // UMiHostMove LS row: add the POST-index (eaIndex) to (T0+od)
      miMoveFlags: Boolean = false,  // UMiHostMove: this IS the host MOVE (sets NZVC per ctx.miWNzvc)
      keepCommit:  Boolean = false,  // force-keep the macro commit (a no-flags store that IS the
                                     // single oracle step, e.g. the MOVES write store) — overrides
                                     // the EaAutoDrop / RMW-store DROP in the whitebox
      isFirst: Boolean = false,   // firstOfInstr (the macro boundary)
      isLast:  Boolean = false    // releases `fed`
  )

  /** The ROM (index = µPC). v1 has one customer family (BCD/ADDX/SUBX mem). The op kind
    * (BCD vs ADDX vs SUBX), size, and bcdSub come from the CONTEXT, so ONE 6-row sequence
    * serves all four mem forms (they differ only in ctx fields). */
  val BCD_MEM_ENTRY = 0
  val rom: Vector[Desc] = Vector(
    // µPC0: LOAD.sz (Ay) -> T0  (predec Ay address; the load writes T0; NO An write here —
    //        the LS-EU writes An only on an eaAuto STORE, so the Ay write-back is µPC1).
    Desc(UMove, mem = MLoad, auto = APredecAy, srcA = SAy, dst = ST0, isFirst = true),
    // µPC1: ADD.L Ay - deltaAy -> Ay   (the Ay predec write-back; dropped crack µop)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SNegDeltaAy),
    // µPC2: LOAD.sz (Ax) -> T1  (predec Ax address; the load writes T1)
    Desc(UMove, mem = MLoad, auto = APredecAx, srcA = SAx, dst = ST1),
    // µPC3: ADD.L Ax - deltaAx -> Ax   (the Ax predec write-back; dropped crack µop)
    Desc(UAddDrop, srcA = SAx, dst = SAx, useImm = true, imm = SNegDeltaAx),
    // µPC4: <BCD|ADDX|SUBX>.sz srcA=T1 (dst byte = dx), srcB=T0 (src byte = dy) -> T1,
    //        + NZVCX/X (reads X + old-Z, the clear-only-Z rule like the register form).
    //        The RESULT reuses T1 (the dst-byte temp, dead after this read) — no 3rd temp
    //        is needed, so the rename int-RAT depth (18 = D0-7/A0-7/T0/T1) is unchanged. The
    //        same-µop T1 read(srcA)+write(dst) is a normal RAW the renamer resolves.
    Desc(UOpFromCtx, srcA = ST1, srcB = ST0, dst = ST1, writesFlags = true),
    // µPC5: STORE.sz T1 -> (Ax)  (Ax was ALREADY decremented at µPC3 -> NO auto; isLast).
    Desc(UMove, mem = MStore, auto = ANoAuto, srcA = SAx, srcB = ST1, isLast = true),

    // ════════════════════════════════════════════════════════════════════════
    // Bit-field MEMORY RMW (BFCHG/BFCLR/BFSET/BFINS), slice 3b — ALL through engine.
    // The EA + bit-field static params (bitOff/needHi/width/Dn2/disp) are carried in the
    // v2 Ctx (populated at ucBegin via EaDecoder on the EA-ext words). needHi (static,
    // bitOff+width>32) selects the ENTRY POINT (no runtime ROM branch). All rows are
    // straight-line. The address = SEaBase + SEaDispLo|SEaDispHi (+ Ctx index).
    //
    // 4-byte chain (needHi=False — the common case), 3 rows @ BF_RMW_4B_ENTRY=6:
    //   a0 LOAD.L  [byteAddr] -> T0 (lo)                                   (isFirst)
    //   a1 BITFIELD bfMem LO4  srcA=T0(lo), srcC=Dn2(BFINS) -> T1 (lo')    (+NZ flags;
    //        funnels field32 from lo only, computes res INTERNALLY, outputs lo')
    //   a2 STORE.L T1(lo') -> [byteAddr]                                   (isLast)
    Desc(UMove,  mem = MLoad,  srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true, isFirst = true),                     // µPC6 (a0)
    Desc(UBfMem, srcA = ST0, srcC = SDn2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 1, bfWritesNz = true),                     // µPC7 (a1)
    Desc(UMove,  mem = MStore, srcA = SEaBase, srcB = ST1, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true, isLast = true),                      // µPC8 (a2)

    // 5-byte chain (needHi=True — bitOff+width>32), 7 rows @ BF_RMW_5B_ENTRY=9:
    //   b0 LOAD.L  [byteAddr]   -> T0 (lo)                                  (isFirst)
    //   b1 LOAD.B  [byteAddr+4] -> T1 (hi, the spill byte)
    //   b2 BITFIELD bfMem RES  srcA=T0(lo), srcB=T1(hi), srcC=Dn2 -> T2 (res; +NZ flags)
    //   b3 BITFIELD bfMem LO5  srcA=T0(lo), srcB=T2(res)         -> T0 (lo'; no flags)
    //   b4 BITFIELD bfMem HI5  srcA=T1(hi), srcB=T2(res)         -> T1 (hi'; no flags)
    //   b5 STORE.L T0(lo') -> [byteAddr]
    //   b6 STORE.B T1(hi') -> [byteAddr+4]                                  (isLast)
    // 3 simultaneously-live temps {T0=lo,T1=hi,T2=res} across b2..b4 (the lo'/hi' stores
    // overwrite their dead source temp AFTER its last read).
    Desc(UMove,  mem = MLoad,  srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true, isFirst = true),                     // µPC9  (b0)
    Desc(UMove,  mem = MLoad,  srcA = SEaBase, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC10 (b1)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = SDn2, dst = ST2, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 0, bfWritesNz = true),                     // µPC11 (b2)
    Desc(UBfMem, srcA = ST0, srcB = ST2, dst = ST0, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 2),                                        // µPC12 (b3)
    Desc(UBfMem, srcA = ST1, srcB = ST2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 3),                                        // µPC13 (b4)
    Desc(UMove,  mem = MStore, srcA = SEaBase, srcB = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC14 (b5)
    Desc(UMove,  mem = MStore, srcA = SEaBase, srcB = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true, isLast = true),                      // µPC15 (b6)

    // ════════════════════════════════════════════════════════════════════════
    // FULL-format MEMORY-INDIRECT (68020+) host-op crack (spec §5). The shared
    // POINTER LOAD reads the 32-bit pointer at base + bd (+ PRE-index for pre-index
    // modes) -> T0 (UMiPtrLoad; miPtrIndex set for pre, clear for post). Then the
    // HOST op re-runs as a (T0 + od (+ POST-index))-base access. Five entries, one
    // per host shape (picked in DecodeStage by op + src/dst-EA). T0/T1 only (deepest
    // = ALU-src/RMW: pointer T0 + operand T1 = 2 temps, no archDepth bump).

    // MI_MOVE_SRC @16: MOVE src-EA -> a register. The LS load cannot compute the moved
    // value's N/Z (it computes STORE-data flags only), so mirror the normal MOVE mem->Dn
    // crack: load to T1, then a MOVE T1 -> Dn ALU µop that sets NZVC.
    //   p0 LOAD.L ptr -> T0 (pre-index)            (isFirst)
    //   p1 LOAD.host (T0+od (+post-idx)) -> T1
    //   p2 MOVE T1 -> miOther (the dst Dn) + NZVC                       (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                      // µPC16
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                     // µPC17
    // MOVE T1 -> Dn: the moved value rides srcB (the ALU MOVE result = src2); srcA = the
    // dst Dn = the .B/.W partial-merge (old-value) source. Sets NZVC (data-reg dst).
    Desc(UMiHostOp, srcA = SMiOther, srcB = ST1, dst = SMiOther, isLast = true),  // µPC18 (MOVE T1->Dn)

    // MI_MOVE_DST @19: MOVE reg -> dst-EA (store).
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                      // µPC19
    Desc(UMiHostMove, mem = MStore, srcA = ST0, srcB = SMiOther, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true, miMoveFlags = true, isLast = true),  // µPC20

    // MI_ALU_SRC @21: ALU op with the EA as SOURCE: op (T0+od), Dm -> Dm + flags.
    //   p0 LOAD.L ptr -> T0 ; p1 LOAD.host (T0+od) -> T1 ; p2 op T1,Dm -> Dm (+flags)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                      // µPC21
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                     // µPC22
    Desc(UMiHostOp, srcA = ST1, srcB = SMiOther, dst = SMiOther, isLast = true),  // µPC23

    // MI_RMW @24: dst-EA RMW (imm op ADDI/.../single-EA NEG/NOT/CLR): load, op, store.
    //   p0 LOAD.L ptr -> T0 ; p1 LOAD.host (T0+od) -> T1 ; p2 op (T1 [, other]) -> T1
    //   (+flags) ; p3 STORE.host T1 -> (T0+od)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                      // µPC24
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                     // µPC25
    Desc(UMiHostOp, srcA = ST1, srcB = SMiOther, dst = ST1),                   // µPC26
    Desc(UMiHostMove, mem = MStore, srcA = ST0, srcB = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true, isLast = true),                      // µPC27

    // MI_FLAGS @28: dst-EA flags-only (CMPI / TST): load, op (flags), no store.
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                      // µPC28
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                     // µPC29
    Desc(UMiHostOp, srcA = ST1, srcB = SMiOther, dst = SNone, isLast = true),   // µPC30

    // ════════════════════════════════════════════════════════════════════════
    // CAS .B/.W/.L (atomic compare-and-swap, single address) — 4 µops @ CAS_ENTRY=31.
    // The EA (memory-alterable control mode) rides the shared eaBase/eaDispLo/eaIndex
    // group (resolved by EaDecoder on op[5:0]). Dc=ext[2:0], Du=ext[8:6]. Atomicity is
    // free (in-order single-pipe LS + store queue — see spec §1); the store is ALWAYS
    // issued, writing the mux'd value (Du on match, the loaded value on mismatch — a
    // RAM no-op; spec §6). 2 temps (T0=loaded, T2=store-data); no archDepth bump.
    //   c0 LOAD.sz  (ea) -> T0                                             (isFirst)
    //   c1 CASS  storeData T2 = eq ? Du : T0 (eq = T0.sz==Dc.sz)           (no flags)
    //   c2 CASC  Dc := eq ? Dc : merge.sz(Dc,T0) ; NZVC = cmp(T0,Dc)       (KEPT commit)
    //   c3 STORE.sz T2 -> (ea)                                             (isLast; rmw-drop)
    // The LOAD + STORE both carry the CAS auto EA (eaAuto from ctx) so an (An)+/-(An) CAS
    // computes ONE effective address for both accesses; the An := An ± size write-back rides
    // the STORE (the side effect happens once, on BOTH match and mismatch — the always-store
    // mux keeps mem byte-identical). For the control modes casAutoMode=NONE -> inert eaAuto.
    Desc(UMove, mem = MLoad, auto = AEaCasLoad, srcA = SEaBase, dst = ST0, useImm = true,
         imm = SEaDispLo, indexFromEa = true, isFirst = true),                 // µPC31 (c0)
    Desc(UCasOp(CasForm.CASS), srcA = ST0, srcB = SCasDc, srcC = SCasDu, dst = ST2),  // µPC32 (c1)
    Desc(UCasOp(CasForm.CASC, writesNzvc = true), srcA = ST0, srcB = SCasDc, dst = SCasDc),  // µPC33 (c2)
    Desc(UMove, mem = MStore, auto = AEaCasStore, srcA = SEaBase, srcB = ST2, useImm = true,
         imm = SEaDispLo, indexFromEa = true, isLast = true),                  // µPC34 (c3)

    // ════════════════════════════════════════════════════════════════════════
    // CAS2 .W/.L (dual-address compare-and-swap) — 10 µops @ CAS2_ENTRY=35.
    // Rn1=ext1[15:12], Rn2=ext2[15:12] (REG_DA register-indirect: the FULL 32-bit reg is
    // the address). Both reads are UNCONDITIONAL (spec §2 fact 1). On ANY mismatch BOTH
    // Dc1/Dc2 are updated (fact 2); flags = res2 if dest1==Dc1 else res1 (fact 3); writes
    // only when BOTH match (fact 4) — modelled via the always-store mux. 3 temps T0/T1/T2.
    //   d0 LOAD.sz (Rn1) -> T0                                             (isFirst)
    //   d1 LOAD.sz (Rn2) -> T1
    //   d2 CAS2C1  T2 = {eq1}        ; NZVC = cmp(T0,Dc1) = res1           (dropped)
    //   d3 CAS2C2  T2 = {bothEq,eq2,eq1} (eq1<-T2) ; NZVC = eq1?res2:old   (KEPT commit)
    //   d4 CAS2DC1 Dc1 := bothEq?Dc1:casUpd.sz(Dc1,T0,da=ext1[15])        (dropped)
    //   d5 CAS2DC2 Dc2 := bothEq?Dc2:casUpd.sz(Dc2,T1,da=ext2[15])        (dropped)
    //   d6 CAS2SEL storeData1 = bothEq?Du1:T0 -> T0  (reuse T0; read AFTER d4)
    //   d7 CAS2SEL storeData2 = bothEq?Du2:T1 -> T1  (reuse T1; read AFTER d5)
    //   d8 STORE.sz T0 -> (Rn1)                                            (rmw-drop)
    //   d9 STORE.sz T1 -> (Rn2)                                            (isLast; rmw-drop)
    Desc(UMove, mem = MLoad, srcA = SCas2Rn1, dst = ST0, isFirst = true),      // µPC35 (d0)
    Desc(UMove, mem = MLoad, srcA = SCas2Rn2, dst = ST1),                      // µPC36 (d1)
    Desc(UCasOp(CasForm.CAS2C1, writesNzvc = true, dropCommit = true),
         srcA = ST0, srcB = SCas2Dc1, dst = ST2),                             // µPC37 (d2)
    Desc(UCasOp(CasForm.CAS2C2, writesNzvc = true, readsNzvc = true),
         srcA = ST1, srcB = SCas2Dc2, srcC = ST2, dst = ST2),                 // µPC38 (d3) KEPT
    Desc(UCasOp(CasForm.CAS2DC, dropCommit = true), srcA = ST0, srcB = SCas2Dc1,
         srcC = ST2, dst = SCas2Dc1, useImm = true, imm = SCas2Da1),          // µPC39 (d4)
    Desc(UCasOp(CasForm.CAS2DC, dropCommit = true), srcA = ST1, srcB = SCas2Dc2,
         srcC = ST2, dst = SCas2Dc2, useImm = true, imm = SCas2Da2),          // µPC40 (d5)
    Desc(UCasOp(CasForm.CAS2SEL), srcA = ST0, srcB = SCas2Du1, srcC = ST2, dst = ST0),  // µPC41 (d6)
    Desc(UCasOp(CasForm.CAS2SEL), srcA = ST1, srcB = SCas2Du2, srcC = ST2, dst = ST1),  // µPC42 (d7)
    Desc(UMove, mem = MStore, srcA = SCas2Rn1, srcB = ST0),                    // µPC43 (d8)
    Desc(UMove, mem = MStore, srcA = SCas2Rn2, srcB = ST1, isLast = true),     // µPC44 (d9)

    // ════════════════════════════════════════════════════════════════════════
    // MOVES .B/.W/.L (010+ PRIVILEGED move to/from alternate address space). The access
    // is FLAT (Musashi `(void)fc` — the FC is stored in SFC/DFC but never redirects address
    // space), so functionally it is a normal sized MOVE + the EA auto-inc/dec side effect +
    // a privilege trap. The EA (memory-alterable, incl (An)+/-(An)) rides the shared
    // eaBase/eaDispLo/eaIndex group; the CAS auto EA machinery (eaAuto from ctx) carries the
    // (An)+/-(An) single side effect. Privilege: the FIRST µop carries needsSupervisor (set
    // in resolve from ctx) -> the ROB delivers a vector-8 if committed S==0 (op does NOT
    // execute). NO CCR effect (the UMove / UMovesRead rows write no flags).
    //
    // WRITE form (dr=1) @ MOVES_WRITE_ENTRY=45 — 1 µop:
    //   w0 STORE.sz Rn -> (ea)   (eaAuto An write-back rides the store, like CAS store —
    //        AEaCasStore writes dst=eaBase An iff casAutoMode =/= NONE, single side effect)
    Desc(UMove, mem = MStore, auto = AEaCasStore, srcA = SEaBase, srcB = SMovesRn,
         useImm = true, imm = SEaDispLo, indexFromEa = true, keepCommit = true,
         isFirst = true, isLast = true),                                       // µPC45 (w0)

    // READ form (dr=0) @ MOVES_READ_ENTRY=46 — 2 or 3 µops:
    //   r0 LOAD.sz (ea) -> T0  (eaAuto from ctx for the address calc; the load does NOT
    //        write An — its dst is T0. For PREDEC addr=An-delta; POSTINC addr=An.)
    //   r1 MOVE T0 -> Rn (UMovesRead): An sign-ext.sz / Dn size-merge.sz; NO CCR.
    //   r2 ADD An + signedDelta -> An  (the (An)+/-(An) write-back; a DROPPED crack µop,
    //        like the BCD/MOVEM An update. signedDelta = +size (POSTINC) / -size (PREDEC) /
    //        0 (no auto -> an identity An:=An+0 NOP, harmless). isLast.) The An side effect
    //        thus happens EXACTLY ONCE (matching Musashi M68KMAKE_GET_EA_AY).
    Desc(UMove, mem = MLoad, auto = AEaCasLoad, srcA = SEaBase, dst = ST0, useImm = true,
         imm = SEaDispLo, indexFromEa = true, isFirst = true),                 // µPC46 (r0)
    Desc(UMovesRead, srcA = SMovesRn, srcB = ST0, dst = SMovesRn),             // µPC47 (r1)
    Desc(UAddDrop, srcA = SMovesAn, dst = SMovesAn, useImm = true, imm = SMovesDelta,
         isLast = true),                                                       // µPC48 (r2)

    // ════════════════════════════════════════════════════════════════════════
    // CMPM.B/.W/.L (Ay)+,(Ax)+ — 5 µops @ CMPM_ENTRY=49. Template: BCD_MEM_ENTRY's dual-
    // address-register chain, swapped from PREDECREMENT to POSTINCREMENT (postinc reads
    // the UNMODIFIED An as the access address; the +delta write-back rides a separate
    // dropped ADD, same shape as the predec chain) and with a flags-only CMP compute tail
    // (NO destination write, X UNTOUCHED — `nzvcOnly`) instead of a BCD op + store.
    // Musashi (m68k_in.c CMPM): src=read(Ay)+ FIRST; dst=read(Ax)+ SECOND; res=dst-src;
    // N/Z/V/C from res; X untouched; no register/memory write beyond the two postincs.
    //   e0 LOAD.sz (Ay) -> T0, auto=POSTINC(Ay)                              (isFirst)
    //   e1 ADD.L Ay + deltaAy -> Ay   (the Ay postinc write-back; dropped crack µop)
    //   e2 LOAD.sz (Ax) -> T1, auto=POSTINC(Ax)
    //   e3 ADD.L Ax + deltaAx -> Ax   (the Ax postinc write-back; dropped crack µop)
    //   e4 CMP.sz srcA=T1(Ax,dst) srcB=T0(Ay,src) -> flags only (NZVC; X untouched)
    Desc(UMove, mem = MLoad, auto = APostincAy, srcA = SAy, dst = ST0, isFirst = true),  // µPC49 (e0)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SDeltaAy),                // µPC50 (e1)
    Desc(UMove, mem = MLoad, auto = APostincAx, srcA = SAx, dst = ST1),                  // µPC51 (e2)
    Desc(UAddDrop, srcA = SAx, dst = SAx, useImm = true, imm = SDeltaAx),                // µPC52 (e3)
    Desc(UOpFromCtx, srcA = ST1, srcB = ST0, nzvcOnly = true, isLast = true)              // µPC53 (e4)
  )
  val BF_RMW_4B_ENTRY = 6
  val BF_RMW_5B_ENTRY = 9
  val MI_MOVE_SRC_ENTRY = 16   // rows 16,17,18 (ptr-load, host-load->T1, MOVE T1->Dn)
  val MI_MOVE_DST_ENTRY = 19   // rows 19,20    (ptr-load, host-store Dn->mem)
  val MI_ALU_SRC_ENTRY  = 21   // rows 21,22,23 (ptr-load, host-load->T1, op T1,Dm->Dm)
  val MI_RMW_ENTRY      = 24   // rows 24,25,26,27 (ptr-load, host-load->T1, op->T1, host-store)
  val MI_FLAGS_ENTRY    = 28   // rows 28,29,30 (ptr-load, host-load->T1, op flags-only)
  val CAS_ENTRY         = 31   // rows 31..34 (load, CASS, CASC, store)
  val CAS2_ENTRY        = 35   // rows 35..44 (load×2, CAS2C1/C2, DC1/DC2, SEL×2, store×2)
  val MOVES_WRITE_ENTRY = 45   // row 45      (store Rn -> (ea), eaAuto An write-back, keepCommit)
  val MOVES_READ_ENTRY  = 46   // rows 46..48 (load -> T0, MOVE T0->Rn sign-ext/merge, An update)
  val CMPM_ENTRY        = 49   // rows 49..53 (load Ay/postinc, load Ax/postinc, CMP flags-only)
  def romSize: Int = rom.size

  /** Latched-instruction CONTEXT the engine resolves selectors against. v1 fields
    * (opword..sizeBytesLog) are UNCHANGED so the BCD/ADDX/SUBX chain resolves identically;
    * the v2 group (EA + bit-field static params) is populated at ucBegin from the EaDecoder
    * on the EA-ext words + the bf-ext word, used ONLY by the bit-field RMW rows. The v2
    * fields default inert for a BCD entry (the BCD rows never reference them). */
  case class Ctx() extends Bundle {
    val opword       = Bits(16 bits)
    val pc           = UInt(32 bits)
    val nextPc       = UInt(32 bits)
    val op           = DecOp()           // the latched op (BCD/ADDX/SUBX) for UOpFromCtx
    val bcdSub       = Bool()
    val size         = Size()
    val sizeBytesLog = UInt(2 bits)      // 0=.B(1), 1=.W(2), 2=.L(4) — for the An delta
    // ── v2 bit-field RMW EA group (mirrors EaDecoder output; the 3a bfm load/store carry
    //    the same fields) ──────────────────────────────────────────────────────────────
    val eaBase       = UInt(5 bits)      // base An reg id (8..15); valid per eaBaseValid
    val eaBaseValid  = Bool()            // (An)/(d16,An)/(d8,An,Xn) -> True; (xxx).W/.L -> False
    val eaIndexReg   = UInt(5 bits)      // index reg (Dn/An) for m6-brief
    val eaIndexValid = Bool()
    val eaIndexLong  = Bool()
    val eaIndexScale = UInt(2 bits)
    val eaDispLo     = Bits(32 bits)     // resolved byteAddr disp = EA.disp + (offset>>3)
    val eaDispHi     = Bits(32 bits)     // = eaDispLo + 4 (byteAddr+4, the spill byte)
    // ── v2 bit-field static params ────────────────────────────────────────────────────
    val bfOp         = Bits(3 bits)      // op[10:8] = 2 BFCHG / 4 BFCLR / 6 BFSET / 7 BFINS
    val bfDn2        = UInt(5 bits)      // ext[14:12] (BFINS insert source register)
    val bfImm        = Bits(32 bits)     // the packed bfMem imm (identical layout to 3a bfmImm)
    val bfNeedHi     = Bool()            // (bitOff+width)>32 — picks the entry in DecodeStage
    // ── full-format MEMORY-INDIRECT host-op group (the §5 host-op-to-temp crack) ──────
    // The pointer-load address = eaBase + eaDispLo(=bd) + (pre: eaIndex). Post-index keeps
    // the index for the HOST access. After [LOAD.L ptr -> T0], the host op re-runs as a
    // (T0 + od (+post-index))-base access. These fields carry the host op identity.
    val miOd         = Bits(32 bits)     // outer displacement (added to the loaded pointer)
    val miPost       = Bool()            // True = post-index ([bd,An],Xn,od); index on the host access
    val miOp         = DecOp()           // the host op (MOVE/ADD/SUB/AND/OR/CMP/CLR/NEG/NOT/TST/...)
    val miHostSize   = Size()            // the host op access size (.B/.W/.L)
    val miIsDstEa    = Bool()            // the EA is the host op's DESTINATION (store/RMW) vs SOURCE (load)
    val miIsRmw      = Bool()            // dst-EA op that READS then WRITES the EA (imm op / CLR-family on mem)
    val miOther      = UInt(5 bits)      // the host op's OTHER operand register (Dn/Dm); valid per miOtherValid
    val miOtherValid = Bool()
    val miOtherIsImm = Bool()            // the other operand is an immediate (line-0 imm op)
    val miHostImm    = Bits(32 bits)     // the host op immediate (when miOtherIsImm)
    val miOtherIsDst = Bool()            // MOVE src-EA: the loaded value goes straight to miOther (Dn dst)
    val miWNzvc      = Bool(); val miWX = Bool()   // host op flag writes
    val miRNzvc      = Bool(); val miRX = Bool()   // host op flag reads (NEGX/etc — not in the §7 set)
    // ── CAS / CAS2 group (populated at ucBegin from the ext words) ──────────────────────
    // CAS: the EA address rides the shared eaBase/eaDispLo/eaIndex group above (resolved by
    // EaDecoder on op[5:0]); ctx.size is the access size. Dc/Du are Dn register ids.
    val casDc        = UInt(5 bits)      // CAS compare reg Dc = ext[2:0]
    val casDu        = UInt(5 bits)      // CAS update  reg Du = ext[8:6]
    // CAS auto-inc/dec EA side effect ((An)+ / -(An)): the LOAD + STORE carry this so they
    // compute the SAME address; the An write-back rides the STORE. NONE for the other modes.
    val casAutoMode  = EaAuto()
    val casAutoDelta = UInt(3 bits)
    // CAS2: two register-indirect addresses Rn1/Rn2 (REG_DA, Dn or An — the FULL 32-bit reg
    // is the address), and the two compare/update register pairs + the per-Rn D/A bit.
    val cas2Rn1      = UInt(5 bits); val cas2Rn2 = UInt(5 bits)
    val cas2Dc1      = UInt(5 bits); val cas2Du1 = UInt(5 bits)
    val cas2Dc2      = UInt(5 bits); val cas2Du2 = UInt(5 bits)
    val cas2Da1      = Bool();       val cas2Da2 = Bool()   // ext1[15] / ext2[15] (sign-ext rule)
    // ── MOVES group (populated at ucBegin from the ext word + EaDecoder) ────────────────
    // The EA rides the shared eaBase/eaDispLo/eaIndex + casAutoMode/casAutoDelta group
    // (reusing the CAS auto machinery for (An)+/-(An)). movesRn = REG_DA[ext15:12] (0..15,
    // the moved register); movesRnIsA = ext[15] (1=An -> sign-extend on read; 0=Dn -> merge).
    val movesRn      = UInt(5 bits)      // REG_DA[ext15:12] (0..15)
    val movesRnIsA   = Bool()            // ext[15] (1=An sign-ext, 0=Dn merge) on a READ
    val movesDelta   = Bits(32 bits)     // signed An write-back delta (+size POSTINC / -size PREDEC / 0)
    // needsSupervisor: set True for the MOVES first µop (the privilege trap; ROB vector-8 if
    // committed S==0). Default False (every other microcode customer is unprivileged).
    val needsSup     = Bool()
  }

  // ── selector → (regId, valid) ──────────────────────────────────────────────
  private def ayReg(ctx: Ctx): UInt = (U(8, 5 bits) + ctx.opword(2 downto 0).asUInt).resize(5)
  private def axReg(ctx: Ctx): UInt = (U(8, 5 bits) + ctx.opword(11 downto 9).asUInt).resize(5)

  private def selReg(sel: Sel, ctx: Ctx): (UInt, Bool) = sel match {
    case SAy     => (ayReg(ctx), True)
    case SAx     => (axReg(ctx), True)
    case ST0     => (U(T0, 5 bits), True)
    case ST1     => (U(T1, 5 bits), True)
    case ST2     => (U(T2, 5 bits), True)
    case SEaBase => (ctx.eaBase, ctx.eaBaseValid)   // abs modes -> baseValid False (disp-only)
    case SDn2    => (ctx.bfDn2, True)
    case SMiOther => (ctx.miOther, ctx.miOtherValid)
    case SCasDc   => (ctx.casDc, True)
    case SCasDu   => (ctx.casDu, True)
    case SCas2Rn1 => (ctx.cas2Rn1, True)
    case SCas2Rn2 => (ctx.cas2Rn2, True)
    case SCas2Dc1 => (ctx.cas2Dc1, True)
    case SCas2Du1 => (ctx.cas2Du1, True)
    case SCas2Dc2 => (ctx.cas2Dc2, True)
    case SCas2Du2 => (ctx.cas2Du2, True)
    case SMovesRn => (ctx.movesRn, True)
    // The (An)+/-(An) write-back target: the base An, valid ONLY when an auto mode is set
    // (a non-auto / abs EA -> the r2 ADD writes no reg, an inert NOP).
    case SMovesAn => (ctx.eaBase, ctx.casAutoMode =/= EaAuto.NONE)
    case _       => (U(0, 5 bits), False)
  }

  /** deltaAn (bytes) = (size==BYTE && An==A7) ? 2 : sizeBytes — the A7-byte even rule. */
  private def deltaBytesU(anReg: UInt, ctx: Ctx): UInt = {
    val sizeBytes = (U(1, 3 bits) |<< ctx.sizeBytesLog).resize(3)        // 1/2/4
    val isA7Byte  = (anReg === U(15, 5 bits)) && (ctx.size === Size.BYTE)
    Mux(isA7Byte, U(2, 3 bits), sizeBytes)
  }

  /** The predec write-back imm = -deltaAn (LONG). */
  private def negDelta(anReg: UInt, ctx: Ctx): Bits =
    (-(deltaBytesU(anReg, ctx).resize(32).asSInt)).asBits

  /** The postinc write-back imm = +deltaAn (LONG) — CMPM. */
  private def posDelta(anReg: UInt, ctx: Ctx): Bits =
    deltaBytesU(anReg, ctx).resize(32).asBits

  private def selImm(sel: Sel, ctx: Ctx): Bits = sel match {
    case SNegDeltaAy => negDelta(ayReg(ctx), ctx)
    case SNegDeltaAx => negDelta(axReg(ctx), ctx)
    case SDeltaAy    => posDelta(ayReg(ctx), ctx)
    case SDeltaAx    => posDelta(axReg(ctx), ctx)
    case SEaDispLo   => ctx.eaDispLo
    case SEaDispHi   => ctx.eaDispHi
    case SBfImm      => ctx.bfImm
    case SMiOd       => ctx.miOd
    case SMiImm      => ctx.miHostImm
    case SCas2Da1    => ctx.cas2Da1.asBits.resize(32)   // bit0 = ext1[15] (BIT_1F)
    case SCas2Da2    => ctx.cas2Da2.asBits.resize(32)   // bit0 = ext2[15] (BIT_F)
    case SMovesDelta => ctx.movesDelta                  // signed An write-back delta
    case _           => B(0, 32 bits)
  }

  /** Resolve a descriptor + context into a fully-driven DecodedUop. Every field is
    * assigned exactly once (mirrors MicroOpAssembler.movemMoveUop's fully-defaulted
    * shape). `valid` is driven by the caller. */
  def resolve(d: Desc, ctx: Ctx, valid: Bool): DecodedUop = {
    val u = DecodedUop()
    val (srcAReg, srcAV) = selReg(d.srcA, ctx)
    val (srcBReg, srcBV) = selReg(d.srcB, ctx)
    val (dstReg,  dstV)  = selReg(d.dst,  ctx)
    // srcC: an LS row whose `indexFromEa` is set carries the EA index reg; a UBfMem
    // compute row carries the BFINS insert source (Dn2) via d.srcC; otherwise inert.
    // Full-format mem-indirect: the PRE-index rides the pointer-load srcC (miPtrIndex &&
    // !miPost); the POST-index rides the host LS srcC (miHostIndex && miPost).
    val (srcCRegSel, srcCVSel) = selReg(d.srcC, ctx)
    val miPtrIdxUse  = Bool(d.miPtrIndex) && !ctx.miPost
    val miHostIdxUse = Bool(d.miHostIndex) && ctx.miPost
    val miIndexUse   = miPtrIdxUse || miHostIdxUse
    val srcCReg = if (d.indexFromEa) ctx.eaIndexReg
                  else if (d.miPtrIndex || d.miHostIndex) ctx.eaIndexReg
                  else srcCRegSel
    val srcCV   = if (d.indexFromEa) ctx.eaIndexValid
                  else if (d.miPtrIndex || d.miHostIndex) (ctx.eaIndexValid && miIndexUse)
                  else srcCVSel

    u.valid  := valid
    u.pc     := ctx.pc
    u.nextPc := ctx.nextPc
    d.uop match {
      case UMove       => u.op := DecOp.MOVE
      case UAddDrop    => u.op := DecOp.ADD
      case UOpFromCtx  => u.op := ctx.op
      case UBfMem      => u.op := DecOp.BITFIELD
      case UMiPtrLoad  => u.op := DecOp.MOVE
      case UMiHostMove => u.op := DecOp.MOVE
      case UMiHostOp   => u.op := ctx.miOp
      case UMovesRead  => u.op := DecOp.MOVE   // MOVE T0 -> Rn (sign-ext An / merge Dn in EU)
      case _: UCasOp   => u.op := DecOp.CASOP
    }
    u.cluster := (d.uop match {
      case UBfMem    => Cluster.INT          // the bit-field compute runs on the ALU/slow pipe
      // The §7 host ALU/unary ops (MOVE/ADD/SUB/AND/OR/EOR/CMP/CLR/NEG/NEGX/NOT/TST) all run
      // on the INT (ALU) pipe; CPLX/CHK/DIV are not full-format mem-indirect hosts in scope.
      case UMiHostOp => Cluster.INT
      case UMovesRead => Cluster.INT         // the MOVES read writeback runs on the ALU pipe
      case _: UCasOp => Cluster.INT          // CAS/CAS2 compute runs on the ALU pipe
      case _         => (d.mem match { case MNone => Cluster.INT; case _ => Cluster.LS })
    })
    // The An write-back ADD is LONG; the BCD chain uses ctx.size; the bit-field chain rows
    // carry an explicit size (SzLong for lo/compute, SzByte for the hi spill byte).
    d.uop match {
      case UAddDrop => u.size := Size.LONG
      case _        => d.sz match {
        case SzLong => u.size := Size.LONG
        case SzByte => u.size := Size.BYTE
        case SzCtx  => u.size := ctx.size
        case SzHost => u.size := ctx.miHostSize
      }
    }
    u.memOp := (d.mem match {
      case MNone  => MemOp.NONE
      case MLoad  => MemOp.LOAD
      case MStore => MemOp.STORE
    })
    u.srcAReg := srcAReg; u.srcAValid := srcAV
    u.srcBReg := srcBReg   // srcBValid assigned below (per-row, depends on the host-op kind)
    u.srcCReg := srcCReg; u.srcCValid := srcCV
    // The CAS auto STORE writes its int dst = the base An (the (An)+/-(An) side effect), gated
    // on a non-NONE ctx.casAutoMode; the LS-EU computes An := An ± size per eaAuto. All other
    // rows take the ROM-selected dst. (Only the STORE writes An — the load's dst is T0.)
    if (d.auto == AEaCasStore) {
      u.dstReg   := ctx.eaBase
      u.dstValid := ctx.casAutoMode =/= EaAuto.NONE
    } else {
      u.dstReg  := dstReg;  u.dstValid  := dstV
    }
    // ── useImm / imm / srcBValid / flags — assigned ONCE per row (Scala-if on d.uop) ──
    if (d.uop == UMiHostOp) {
      // The host ALU/MOVE/unary op. srcB is the ROM-selected register (ST1 for MOVE-src's
      // moved value; SMiOther for an ALU other-Dn) UNLESS the other operand is an immediate
      // (line-0 imm op -> useImm, srcB not read). A unary single-EA op (CLR/NEG/NOT/TST) has
      // no other operand (miOtherValid False -> the SMiOther srcB is not read). Flags from ctx.
      u.useImm     := ctx.miOtherIsImm
      u.imm        := ctx.miHostImm
      u.srcBValid  := Mux(ctx.miOtherIsImm, False, srcBV)
      u.readsNzvc  := ctx.miRNzvc
      u.readsX     := ctx.miRX
      u.writesNzvc := ctx.miWNzvc
      u.writesX    := ctx.miWX
    } else if (d.uop == UMiHostMove) {
      // The host MOVE (load-to-Dn / store-from-Dn) sets NZVC per ctx.miWNzvc (miMoveFlags
      // rows). An intermediate host-load to T1 (ALU-src/RMW) writes NO flags (the host op
      // µop owns them) -> miMoveFlags False on those rows.
      u.useImm     := Bool(d.useImm)
      u.imm        := (if (d.useImm) selImm(d.imm, ctx) else B(0, 32 bits))
      u.srcBValid  := srcBV
      u.readsNzvc  := False
      u.readsX     := False
      u.writesNzvc := Bool(d.miMoveFlags) && ctx.miWNzvc
      u.writesX    := False
    } else if (d.uop.isInstanceOf[UCasOp]) {
      // CAS/CAS2 compute. srcB (Dc / Du) is always read; srcC (Du / status temp) is set
      // by the ROM (handled above). The imm (the CAS2.W D/A bit) rides useImm. Flags per
      // the Desc-carried masks: CASC/CAS2C1/CAS2C2 write NZVC; CAS2C2 also READS NZVC
      // (to preserve res1 in the !eq1 case). CAS/CAS2 never touch X.
      val co = d.uop.asInstanceOf[UCasOp]
      u.useImm     := Bool(d.useImm)
      u.imm        := (if (d.useImm) selImm(d.imm, ctx) else B(0, 32 bits))
      u.srcBValid  := srcBV
      u.readsNzvc  := Bool(co.readsNzvc)
      u.readsX     := False
      u.writesNzvc := Bool(co.writesNzvc)
      u.writesX    := False
    } else {
      // Flags: the BCD/ADDX/SUBX op µop reads X + old-Z (clear-only Z) + writes NZVCX. The
      // bit-field RES/LO4 compute writes NZ only (V=C=0, X UNTOUCHED). CMPM's compute
      // (nzvcOnly) writes NZVC only (no NZVC/X read, X UNTOUCHED — like a register CMP).
      // Other rows: no flags.
      u.useImm     := Bool(d.useImm)
      u.imm        := (if (d.useImm) selImm(d.imm, ctx) else B(0, 32 bits))
      u.srcBValid  := srcBV
      u.readsNzvc  := Bool(d.writesFlags)
      u.readsX     := Bool(d.writesFlags)
      u.writesNzvc := Bool(d.writesFlags || d.bfWritesNz || d.nzvcOnly)
      u.writesX    := Bool(d.writesFlags)
    }
    u.isBranch := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
    u.cond := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.faultAddr := ctx.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
    u.divSigned := False; u.div64 := False
    // The two An write-back ADDs are DROPPED crack µops (divIsRem): the commit
    // observation is dropped, but the An write lands in the PRF + is verified by a later
    // reader (the program reads Ay/Ax into a Dn after the op). Mirrors anUpdUop / LINK.
    // CAS2's CAS2C1 (the res1 compare) + the two CAS2DC Dc-update µops are likewise DROPPED:
    // their NZVC/Dc1/Dc2 writes still land + fold (the lock-step reads Dc1/Dc2 back), but
    // CAS2 maps to exactly ONE oracle step (the kept CAS2C2, which carries the final NZVC).
    u.divIsRem := Bool(d.uop == UAddDrop || (d.uop.isInstanceOf[UCasOp] && d.uop.asInstanceOf[UCasOp].dropCommit))
    u.isChk2   := False
    // Auto-update: the LOAD carries PREDEC so the LS-EU computes addr = An - eaDelta (the
    // load does NOT write An — the LS-EU writes An only on an eaAuto STORE). The store
    // accesses the already-decremented Ax with NO auto. eaDelta is the byte count.
    d.auto match {
      case ANoAuto     => u.eaAuto := EaAuto.NONE;    u.eaDelta := U(0, 3 bits)
      case APredecAy   => u.eaAuto := EaAuto.PREDEC;  u.eaDelta := deltaBytesU(ayReg(ctx), ctx)
      case APredecAx   => u.eaAuto := EaAuto.PREDEC;  u.eaDelta := deltaBytesU(axReg(ctx), ctx)
      // CMPM (Ay)+,(Ax)+: the LOAD accesses the UNMODIFIED An (LsEuPlugin's POSTINC disp
      // branch forces disp=0); the An += delta write-back rides the separate dropped ADD
      // row (mirrors PREDEC's separate write-back row — the load itself writes T0/T1, not An).
      case APostincAy  => u.eaAuto := EaAuto.POSTINC; u.eaDelta := deltaBytesU(ayReg(ctx), ctx)
      case APostincAx  => u.eaAuto := EaAuto.POSTINC; u.eaDelta := deltaBytesU(axReg(ctx), ctx)
      // CAS (An)+/-(An): the LOAD + STORE carry the latched ctx auto so they compute the
      // SAME effective address (PREDEC: An-delta; POSTINC: An). For the control modes
      // casAutoMode=NONE -> inert eaAuto (addr = An + disp + index, the normal CAS EA).
      case AEaCasLoad  => u.eaAuto := ctx.casAutoMode; u.eaDelta := ctx.casAutoDelta
      case AEaCasStore => u.eaAuto := ctx.casAutoMode; u.eaDelta := ctx.casAutoDelta
    }
    u.ccrRestore := False; u.toCcr := False
    u.shiftOp := 0; u.shiftDir := False
    u.bcdSub := ctx.bcdSub
    // Bit-field RMW compute (UBfMem): op=BITFIELD + bfMem (so the ALU EU funnel datapath
    // runs) + bfOp (CHG/CLR/SET/INS) from the latched ctx.op + the store-form selector. The
    // funnel reads bitOff/needHi/width/origOff from the packed bfImm (= u.imm, set above).
    // isMovea: a MOVES READ writeback to an An (ctx.movesRnIsA) sign-extends the loaded
    // value to 32 (the moveaResult path, extended to .B for MOVES). A Dn read leaves it
    // False -> the generic size-merge preserves the upper bits. Default False for all others.
    u.bitOp := 0; u.bfDynamic := False; u.extByte := False
    u.isMovea := (if (d.uop == UMovesRead) ctx.movesRnIsA else False)
    d.uop match {
      case UBfMem =>
        u.bfMem       := True
        u.bfOp        := ctx.bfOp        // CHG=2/CLR=4/SET=6/INS=7 (latched at ucBegin)
        u.bfStoreForm := U(d.bfStoreForm, 2 bits)
      case _ =>
        u.bfMem       := False
        u.bfOp        := 0
        u.bfStoreForm := 0
    }
    u.isScc := False; u.isDbcc := False
    // Indexed-EA descriptor fields. The bit-field chain and the full-format mem-indirect
    // pointer/host LS rows carry the EA index (srcC) -> drive its size/scale from Ctx; all
    // other µcode µops use no index (default inert).
    if (d.indexFromEa || d.miPtrIndex || d.miHostIndex) {
      u.indexLong := ctx.eaIndexLong; u.indexScale := ctx.eaIndexScale
    } else {
      u.indexLong := False; u.indexScale := 0
    }
    // needsSupervisor: set on the FIRST µop of a MOVES (ctx.needsSup) — the ROB delivers a
    // vector-8 privilege violation if the head retires with committed S==0 (the op does NOT
    // execute). keepCommit: a ROM row may force-keep its commit (the MOVES write store).
    u.leaAddr := False; u.fromCcr := False; u.fromSr := False
    u.needsSupervisor := ctx.needsSup && Bool(d.isFirst)
    u.keepCommit := Bool(d.keepCommit)
    // µcode µops are never commit-time system ops (the system ops ride the fast
    // op-µop builder + the ROB serializing path, not the ROM). Default inert.
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits)
    // CAS/CAS2 compute sub-form (DecOp.CASOP); 0 for every other µop.
    u.casForm := (d.uop match {
      case co: UCasOp => B(co.form, 3 bits)
      case _          => B(0, 3 bits)
    })
    u.firstOfInstr := Bool(d.isFirst)
    u
  }
}
