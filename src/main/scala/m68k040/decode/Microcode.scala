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
  // distinct from the two loaded operands lo/hi). T3 (task #203) is the 4th, added so
  // the memory-INDIRECT dynamic-offset bit-field RMW/INS chains (MI_BF_RMW_DO1/
  // MI_BF_INS_DO1 below) can hold the resolved pointer/Tb LIVE across the whole funnel
  // compute instead of needing an EA-recompute (see those entries' header comment for
  // the full rationale). The DecodedUop reg-id space is 5 bits, so 19 fits the temp
  // pool. The int RAT/Freelist depth is now ARCH_INT_REGS = 20.
  val T0 = MicroOpAssembler.T0   // 16
  val T1 = MicroOpAssembler.T1   // 17
  val T2 = MicroOpAssembler.T2   // 18 — the bit-field 5-byte-chain `res` temp
  val T3 = MicroOpAssembler.T3   // 19 — the memind dynamic-offset bit-field chain's ptr/Tb temp

  /** Operand-selector vocabulary (the ROM's small mux set), resolved by the engine from
    * the latched opword fields + size. */
  sealed trait Sel
  case object SNone       extends Sel
  case object SAy         extends Sel   // 8 + op[2:0]   (source An / Ay)
  case object SAx         extends Sel   // 8 + op[11:9]  (dest   An / Ax)
  case object ST0         extends Sel
  case object ST1         extends Sel
  case object ST2         extends Sel   // the 3rd temp (bit-field RMW `res`)
  case object ST3         extends Sel   // the 4th temp (task #203 — memind DO1 ptr/Tb, held live)
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
  // ── mem-indirect EA<->EA selectors (task #119 follow-up) ────────────────────
  case object SMiOtherEaBase   extends Sel   // (miOtherEaBase, miOtherEaBaseValid) — the plain (non-MI) side's base
  case object SMiOtherEaDispLo extends Sel   // selImm: the plain (non-MI) side's disp
  // task #204 (both-sides-memory-indirect MOVE): when the "other" side is ITSELF a
  // full-format mem-indirect EA (not the plain-memory case SMiOtherEaBase/DispLo above
  // were built for), it needs its OWN outer displacement — mirrors SMiOd (ctx.miOd,
  // dedicated to the PRIMARY/src pointer) but for the SECOND (dst) pointer instead.
  case object SMiOtherOd       extends Sel   // selImm: the "other" (dst) pointer's own od
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
  // ── Bit-field DYNAMIC memory selectors (slice 3c) ─────────────────────────────
  case object SBfOffReg   extends Sel   // (bfOffDn, True)   — Dn[off] ALWAYS read (ASR/byteBase + LO5RAW/HI5RAW srcC + FFO add)
  case object SBfOffDyn   extends Sel   // (bfOffDn, bfDo)   — Dn[off] read iff Do (BFRESOLVE srcA)
  case object SBfWdDyn    extends Sel   // (bfWdDn,  bfDw)   — Dn[wd]  read iff Dw (BFRESOLVE srcB)
  case object SBfDn2      extends Sel   // (bfDn2,   True)   — BFINS insert source (register-form prefunnel consumer)
  case object SBfRdDst    extends Sel   // (bfDn2, bfOp in {1,3,5}) — read-only result reg (EXTU/EXTS/FFO write; TST none)
  case object SBfResImm   extends Sel   // selImm: the BFRESOLVE imm (Do/Dw/staticOff/staticWidth/memMode)
  case object SBfDeltaImm extends Sel   // selImm: byte-delta mode bit (imm[13]) — BFRESOLVE outputs offset>>>3
  case object SBfPcRelConst extends Sel // selImm: ctx.bfPcRelConst (pc+4) — task #199 PC-rel DO1 byteBase
  // ── Bit-field MEMORY-INDIRECT selectors (task #197) ────────────────────────────
  // The pointer's OWN address (An + bd, no byteOff — the byteOff/width fold happens AFTER
  // dereference) rides the EXISTING SEaBase/SEaDispLo pair (ctx.eaBase/eaDispLo are already
  // populated from the bit-field's own EaDecoder output for EVERY bit-field op, memind or
  // not — see DecodeStage's ucBf* population). Only the POST-dereference byte address needs
  // NEW selectors: SBfMiDispLo/Hi = ctx.miOd + byteOff (Do=0 static fold) or ctx.miOd + 0
  // (Do=1 — byteOff is forced 0 for the dynamic-offset case, matching ucBfByteOff's own
  // existing Mux; the runtime byteDelta is added to the POINTER temp instead, mirroring the
  // register-direct DO1 chain's Tb recompute).
  case object SBfMiDispLo extends Sel   // selImm: ctx.miOd + byteOff (the post-deref byteAddr disp)
  case object SBfMiDispHi extends Sel   // selImm: SBfMiDispLo + 4 (the spill byte)
  // ── PACK/UNPK MEMORY-form selectors (task #198) ─────────────────────────────
  case object SPackAdj extends Sel   // selImm: ctx.packAdj (the adj16 ext word, sign-extended)
  case object SShift8  extends Sel   // selImm: the constant 8 (UNPK's high-byte LSR count)
  // ── control-transfer / address-generate MEMORY-INDIRECT selectors (task #201) ────────
  case object SA7   extends Sel   // (15, True) — the constant A7 stack-pointer reg id
  case object SRetPc extends Sel  // selImm: ctx.nextPc (JSR-memind's pushed return PC)
  // ── MOVE16 selectors (task #207) ─────────────────────────────────────────────
  case object SMove16Ay extends Sel  // (ctx.move16Ay, True) — the dst An, ext word[14:12]
  case object SImm4  extends Sel     // selImm: constant 4  (MOVE16 transfer offset)
  case object SImm8  extends Sel     // selImm: constant 8  (MOVE16 transfer offset)
  case object SImm12 extends Sel     // selImm: constant 12 (MOVE16 transfer offset)
  case object SImm16 extends Sel     // selImm: constant 16 (MOVE16 Ax/Ay += 16 write-back)

  /** The op kind of a descriptor's template. */
  sealed trait UOp
  case object UMove      extends UOp    // plain move / load / store data move (DecOp.MOVE)
  case object UAddDrop   extends UOp    // ADD.L dst:=srcA+imm, divIsRem (dropped An write-back)
  case object UOpFromCtx extends UOp    // the latched op (BCD/ADDX/SUBX/PACK/UNPK), flags from ctx
  case object UBfMem     extends UOp    // BITFIELD bfMem compute (RES/LO/HI funnel form); op=BITFIELD
  // ── Bit-field DYNAMIC memory kinds (slice 3c) ───────────────────────────────
  case object UBfResolve extends UOp    // BFRESOLVE (mem layout): packed {origOff/needHi/bitOff/rawWidth} -> temp
  case object UBfShiftOff extends UOp   // ASR.L Dn[off],#3 -> temp (byteDelta = offset >>>signed 3); NO flags
  case object UBfAdd     extends UOp    // ADD.L srcA + srcB -> temp; NO flags (byteBase = eaBase + byteDelta / FFO offset add)
  case object UBfReg     extends UOp    // BITFIELD register-form dynamic (bfMem=False, bfDynamic): BFINS over the prefunnelled field32
  // ── full-format MEMORY-INDIRECT host-op kinds ──────────────────────────────
  case object UMiPtrLoad extends UOp    // LOAD.L pointer (eaBase + eaDispLo (+ pre-index)) -> T0
  case object UMiHostMove extends UOp   // host MOVE load/store at (T0 + od (+post-index)); op=MOVE
  case object UMiHostOp  extends UOp     // host ALU/unary compute (ctx.miOp): srcA,srcB -> dst + flags
  // ── control-transfer / address-generate MEMORY-INDIRECT kinds (task #201) ──────────
  // LEA/PEA/JMP/JSR resolve their EA directly (no loaded VALUE, unlike the §7 host-op
  // family above) — the pointer load (UMiPtrLoad, reused unchanged) yields T0, then ONE
  // of these folds T0 + od (+ post-index) into the FINAL address, routed straight to its
  // real consumer (An / a push / the branch target) instead of a generic temp+host-op.
  case object UMiLeaFinal    extends UOp  // ADDR-GENERATE (leaAddr, LS-EU AGU, no mem access):
                                           // T0 + od (+ post-idx) -> dst (LEA's An / PEA's temp)
  case object UMiPushFinal   extends UOp  // stack push STORE: -(A7) := srcB (data), dst=A7 (PEA's
                                           // computed addr / JSR's retPC via imm when srcB invalid)
  case object UMiBranchFinal extends UOp  // INDIRECT BRANCH: target = T0 + od (+ post-idx)
                                           // (JMP/JSR-memind's redirect, mirrors ibrUop)
  // ── PACK/UNPK MEMORY-form kind (task #198) ──────────────────────────────────
  case object UShiftR8   extends UOp    // SHIFT.L T,#8 (LSR, right, real barrel shifter) -> dst; NO
                                         // flags — UNPK's high-result-byte extraction (moves the
                                         // unpacked value's [15:8] down into the new dst's [7:0] so
                                         // a plain BYTE store picks it up, mirroring the LOW result
                                         // byte's plain truncating BYTE store of the un-shifted value).
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
  // Mem-indirect EA<->EA "other" (plain) side auto-inc/dec (task #154): mirrors
  // AEaCasLoad/Store but keyed to ctx.miOtherEaAutoMode/Delta + ctx.miOtherEaBase (the
  // "other" side's OWN base register, distinct from ctx.eaBase which is reserved for the
  // mem-indirect pointer). MI_MOVE_EAEA's STORE (other=dst, e.g. MOVE ([...]),-(A7)) uses
  // AEaMiOtherStore; MI_MOVE_EAEA_REV's LOAD (other=src, e.g. MOVE -(A7),([...])) uses
  // AEaMiOtherLoad. NONE for every other mem-indirect customer (a harmless inert eaAuto).
  case object AEaMiOtherLoad  extends Auto   // load: eaAuto from ctx, NO An write (dst = T1)
  case object AEaMiOtherStore extends Auto   // store: eaAuto from ctx + An write-back (dst = miOtherEaBase)

  /** Explicit µop size for a row (overrides the ctx-size default). The bit-field chain
    * rows are LONG (lo load/store, the compute) or BYTE (the hi spill-byte load/store);
    * the BCD chain rows use the ctx-size default (SzCtx). */
  sealed trait Sz
  case object SzCtx  extends Sz   // = ctx.size (BCD chain)
  case object SzLong extends Sz
  case object SzWord extends Sz   // UNPK's compute row (result occupies the low 16 bits)
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
      indexFromMiOtherEa: Boolean = false,  // LS row: srcC + indexLong/indexScale come from
                                             // Ctx.miOtherEa* (task #119 EA<->EA plain side)
      bfStoreForm: Int     = 0,      // UBfMem: 0=RES,1=LO4,2=LO5,3=HI5,4=LO5RAW,5=HI5RAW (funnel form)
      bfWritesNz:  Boolean = false,  // UBfMem/UBfReg: this compute writes the NZ flags (RES / LO4 / BFINS)
      bfDyn:       Boolean = false,  // UBfMem/UBfReg: set bfDynamic (read packed {bitOff/width} via srcC, slice 3c)
      bfTstForm:   Boolean = false,  // UBfMem: force bfOp=0 (BFTST) — the prefunnel emits field32 as its result
      bfDrop:      Boolean = false,  // divIsRem: DROP this µop's oracle-step observation (its reg/NZVC writes still
                                     // land+fold) — the BFFFO Do=1 funnel writes the index+flags to a temp; the
                                     // trailing ADD is the single committed step carrying Dn2 + the funnel's flags
      bfIllegal:   Boolean = false,  // deliver an ILLEGAL (vector-4) µop — BFINS mem-dynamic is DEFERRED (gated)
      miPtrIndex:  Boolean = false,  // UMiPtrLoad: add the PRE-index (eaIndex) to the pointer addr
      miHostIndex: Boolean = false,  // UMiHostMove LS row: add the POST-index (eaIndex) to (T0+od)
      miMoveFlags: Boolean = false,  // UMiHostMove: this IS the host MOVE (sets NZVC per ctx.miWNzvc)
      keepCommit:  Boolean = false,  // force-keep the macro commit (a no-flags store that IS the
                                     // single oracle step, e.g. the MOVES write store) — overrides
                                     // the EaAutoDrop / RMW-store DROP in the whitebox
      isFirst: Boolean = false,   // firstOfInstr (the macro boundary)
      isLast:  Boolean = false    // releases `fed`
  )

  // ═══════════════════════════════════════════════════════════════════════════════
  // LUT-reduction Task A1: `DescBits` (hardware mirror of `Desc`) + `descToBits`
  // (compile-time Desc -> hardware-literal DescBits). Nothing below consumes these
  // yet — Task A2 builds `Mem(DescBits(), romSize)` from `descToBits`; Task A3
  // rewrites `resolve()` into a runtime-hardware `resolveFromBits(d: DescBits, ...)`
  // gated by Task A4's per-row equivalence test before Task A5 cuts the live decode
  // path over. See docs/superpowers/specs/2026-08-06-lut-reduction-microcode-rob-
  // bram-design.md, Feature A.
  //
  // `Sel`/`UOp`/`Mem`/`Auto`/`Sz` above are plain Scala `sealed trait`/`case object`
  // hierarchies (confirmed by direct read — no pre-existing SpinalEnum anywhere in
  // this codebase for any of them, unlike `DecOp`/`SysKind` in DecodedUop.scala,
  // which already are SpinalEnums). Each gets a same-named-elements SpinalEnum below,
  // suffixed `Hw` to keep every hardware enum's element namespace (e.g. `SelHw.SNone`)
  // distinct from the identically-named compile-time `case object`s (e.g. bare
  // `SNone`) already in scope in this `object Microcode` — Scala only needs the OUTER
  // object name to disambiguate a qualified member access, so this is a naming
  // convention for clarity, not a correctness requirement.
  // ═══════════════════════════════════════════════════════════════════════════════

  /** Hardware encoding of `Sel` (the operand-selector vocabulary) — one element per
    * `Sel` case object (54), same names. */
  object SelHw extends SpinalEnum {
    val
        SA7, SAx, SAy, SBfDeltaImm, SBfDn2, SBfImm,
        SBfMiDispHi, SBfMiDispLo, SBfOffDyn, SBfOffReg, SBfPcRelConst, SBfRdDst,
        SBfResImm, SBfWdDyn, SCas2Da1, SCas2Da2, SCas2Dc1, SCas2Dc2,
        SCas2Du1, SCas2Du2, SCas2Rn1, SCas2Rn2, SCasDc, SCasDu,
        SDeltaAx, SDeltaAy, SDn2, SEaBase, SEaDispHi, SEaDispLo,
        SImm12, SImm16, SImm4, SImm8, SMiImm, SMiOd,
        SMiOther, SMiOtherEaBase, SMiOtherEaDispLo, SMiOtherOd, SMove16Ay, SMovesAn,
        SMovesDelta, SMovesRn, SNegDeltaAx, SNegDeltaAy, SNone, SPackAdj,
        SRetPc, SShift8, ST0, ST1, ST2, ST3 = newElement()
  }

  /** Hardware encoding of `UOp` (the µop-template vocabulary) — one element per
    * `UOp` case object (16), plus `UCasOp` as a single TAG element: unlike every
    * other `UOp` case, the real `UCasOp` is a Scala CASE CLASS carrying its own
    * sub-payload (`form: Int, writesNzvc/readsNzvc/dropCommit: Boolean`) — a
    * SpinalEnum element cannot itself carry a payload, so `DescBits` below adds 4
    * sibling fields (`casForm`/`casWritesNzvc`/`casReadsNzvc`/`casDropCommit`),
    * valid only when `uop === UOpHw.UCasOp`. This mirrors the existing `Desc` idiom
    * of siblings-to-`uop` payload fields for other per-kind extra data (e.g.
    * `bfStoreForm` for `UBfMem`, `miPtrIndex` for `UMiPtrLoad`) — UCasOp's payload
    * just happens to already be bundled into the Scala case class instead of being
    * separate `Desc` fields. */
  object UOpHw extends SpinalEnum {
    val
        UMove, UAddDrop, UOpFromCtx, UBfMem, UBfResolve, UBfShiftOff,
        UBfAdd, UBfReg, UMiPtrLoad, UMiHostMove, UMiHostOp, UMiLeaFinal,
        UMiPushFinal, UMiBranchFinal, UShiftR8, UMovesRead, UCasOp = newElement()
  }

  /** Hardware encoding of `Mem` (memory role) — one element per case object (3). */
  object MemHw extends SpinalEnum {
    val MNone, MLoad, MStore = newElement()
  }

  /** Hardware encoding of `Auto` (PREDEC/POSTINC auto-update kind) — one element
    * per case object (9). */
  object AutoHw extends SpinalEnum {
    val
        ANoAuto, APredecAy, APredecAx, APostincAy, APostincAx, AEaCasLoad,
        AEaCasStore, AEaMiOtherLoad, AEaMiOtherStore = newElement()
  }

  /** Hardware encoding of `Sz` (explicit µop size override) — one element per case
    * object (5). */
  object SzHw extends SpinalEnum {
    val SzCtx, SzLong, SzWord, SzByte, SzHost = newElement()
  }

  /** Hardware mirror of `Desc` (one ROM row). Every `Desc` field appears here,
    * same name, hardware-typed: `SpinalEnum`s above for `UOp`/`Mem`/`Auto`/`Sel`/
    * `Sz`, `Bool()` for each `Boolean` field, `UInt` sized to the field's actual
    * used range for the one `Int` field (`bfStoreForm`). Plus the 4 `UCasOp`
    * payload fields described on `UOpHw` above (not a `Desc` field itself — `Desc`
    * only has `uop: UOp`, and `UCasOp`'s payload rides inside that one Scala value).
    *
    * Width note (`bfStoreForm`, `casForm`): both sized `UInt(3 bits)`, verified
    * against real call-site usage, not guessed:
    *   - `bfStoreForm`: `Microcode.rom` rows use values 0-6 (0=RES,1=LO4,2=LO5,
    *     3=HI5,4=LO5RAW,5=HI5RAW,6=FFOFULL — the FFOFULL row comment only lists
    *     0-5 but two live rows, µPC65/µPC190, pass 6). The EXISTING hardware sink
    *     (`DecodedUop.bfStoreForm`, `DecodedUop.scala:344`, and `resolve()`'s own
    *     `u.bfStoreForm := U(d.bfStoreForm, 3 bits)`, `Microcode.scala:1863`)
    *     already commits to 3 bits (covers 0-7) — matched here, not re-derived.
    *   - `casForm`: `CasForm` (`DecodedUop.scala:471`) defines CASS=0..CAS2SEL=5;
    *     the existing sink `DecodedUop.casForm` (`DecodedUop.scala:467`) and
    *     `resolve()`'s `B(co.form, 3 bits)` (`Microcode.scala:1899`) already commit
    *     to 3 bits — matched here.
    */
  case class DescBits() extends Bundle {
    val uop  = UOpHw()
    val mem  = MemHw()
    val auto = AutoHw()
    val srcA = SelHw()
    val srcB = SelHw()
    val srcC = SelHw()    // 3rd operand: BFINS insert source (Dn2) for the RES/LO4 compute
    val dst  = SelHw()
    val useImm = Bool()
    val imm    = SelHw()  // when useImm, the imm comes from this selector
    val sz     = SzHw()   // explicit µop size (SzCtx = ctx.size, the BCD default)
    val writesFlags = Bool()  // reads X+old-Z, writes NZVCX (the op µop)
    val nzvcOnly    = Bool()  // writes NZVC only (X UNTOUCHED, no NZVC/X read) — CMPM
    val indexFromEa = Bool()  // LS row: srcC + indexLong/indexScale come from Ctx EA
    val indexFromMiOtherEa = Bool()  // LS row: srcC + indexLong/indexScale come from
                                      // Ctx.miOtherEa* (task #119 EA<->EA plain side)
    val bfStoreForm = UInt(3 bits)  // UBfMem: 0=RES,1=LO4,2=LO5,3=HI5,4=LO5RAW,5=HI5RAW,6=FFOFULL
    val bfWritesNz  = Bool()  // UBfMem/UBfReg: this compute writes the NZ flags (RES / LO4 / BFINS)
    val bfDyn       = Bool()  // UBfMem/UBfReg: set bfDynamic (read packed {bitOff/width} via srcC, slice 3c)
    val bfTstForm   = Bool()  // UBfMem: force bfOp=0 (BFTST) — the prefunnel emits field32 as its result
    val bfDrop      = Bool()  // divIsRem: DROP this µop's oracle-step observation (its reg/NZVC writes
                               // still land+fold)
    val bfIllegal   = Bool()  // deliver an ILLEGAL (vector-4) µop — BFINS mem-dynamic is DEFERRED (gated)
    val miPtrIndex  = Bool()  // UMiPtrLoad: add the PRE-index (eaIndex) to the pointer addr
    val miHostIndex = Bool()  // UMiHostMove LS row: add the POST-index (eaIndex) to (T0+od)
    val miMoveFlags = Bool()  // UMiHostMove: this IS the host MOVE (sets NZVC per ctx.miWNzvc)
    val keepCommit  = Bool()  // force-keep the macro commit (a no-flags store that IS the single
                               // oracle step, e.g. the MOVES write store) — overrides the EaAutoDrop /
                               // RMW-store DROP in the whitebox
    val isFirst = Bool()  // firstOfInstr (the macro boundary)
    val isLast  = Bool()  // releases `fed`
    // `UCasOp`'s payload (see `UOpHw` doc above) — valid only when uop === UOpHw.UCasOp.
    val casForm       = UInt(3 bits)  // CasForm.{CASS..CAS2SEL} = 0..5
    val casWritesNzvc = Bool()
    val casReadsNzvc  = Bool()
    val casDropCommit = Bool()
  }

  /** Compile-time `Desc` -> hardware-literal `DescBits`, a direct 1:1 field mapping.
    * Runs at Scala elaboration time (called once per ROM row when building the
    * `Mem`'s initial content in Task A2) — every field is assigned a literal
    * (constant) hardware value, so the result is safe to use as `Mem` initial
    * content (no dependency on any runtime/live signal). */
  def descToBits(d: Desc): DescBits = {
    val b = DescBits()

    val (uopHw, casF, casWn, casRn, casDc): (SpinalEnumElement[UOpHw.type], Int, Boolean, Boolean, Boolean) =
      d.uop match {
        case UMove          => (UOpHw.UMove, 0, false, false, false)
        case UAddDrop       => (UOpHw.UAddDrop, 0, false, false, false)
        case UOpFromCtx     => (UOpHw.UOpFromCtx, 0, false, false, false)
        case UBfMem         => (UOpHw.UBfMem, 0, false, false, false)
        case UBfResolve     => (UOpHw.UBfResolve, 0, false, false, false)
        case UBfShiftOff    => (UOpHw.UBfShiftOff, 0, false, false, false)
        case UBfAdd         => (UOpHw.UBfAdd, 0, false, false, false)
        case UBfReg         => (UOpHw.UBfReg, 0, false, false, false)
        case UMiPtrLoad     => (UOpHw.UMiPtrLoad, 0, false, false, false)
        case UMiHostMove    => (UOpHw.UMiHostMove, 0, false, false, false)
        case UMiHostOp      => (UOpHw.UMiHostOp, 0, false, false, false)
        case UMiLeaFinal    => (UOpHw.UMiLeaFinal, 0, false, false, false)
        case UMiPushFinal   => (UOpHw.UMiPushFinal, 0, false, false, false)
        case UMiBranchFinal => (UOpHw.UMiBranchFinal, 0, false, false, false)
        case UShiftR8       => (UOpHw.UShiftR8, 0, false, false, false)
        case UMovesRead     => (UOpHw.UMovesRead, 0, false, false, false)
        case co: UCasOp     => (UOpHw.UCasOp, co.form, co.writesNzvc, co.readsNzvc, co.dropCommit)
      }
    b.uop           := uopHw
    b.casForm       := U(casF, 3 bits)
    b.casWritesNzvc := Bool(casWn)
    b.casReadsNzvc  := Bool(casRn)
    b.casDropCommit := Bool(casDc)

    b.mem := (d.mem match {
      case MNone  => MemHw.MNone
      case MLoad  => MemHw.MLoad
      case MStore => MemHw.MStore
    })

    b.auto := (d.auto match {
      case ANoAuto         => AutoHw.ANoAuto
      case APredecAy       => AutoHw.APredecAy
      case APredecAx       => AutoHw.APredecAx
      case APostincAy      => AutoHw.APostincAy
      case APostincAx      => AutoHw.APostincAx
      case AEaCasLoad       => AutoHw.AEaCasLoad
      case AEaCasStore      => AutoHw.AEaCasStore
      case AEaMiOtherLoad   => AutoHw.AEaMiOtherLoad
      case AEaMiOtherStore  => AutoHw.AEaMiOtherStore
    })

    def sel(s: Sel): SpinalEnumElement[SelHw.type] = s match {
      case SA7 => SelHw.SA7
      case SAx => SelHw.SAx
      case SAy => SelHw.SAy
      case SBfDeltaImm => SelHw.SBfDeltaImm
      case SBfDn2 => SelHw.SBfDn2
      case SBfImm => SelHw.SBfImm
      case SBfMiDispHi => SelHw.SBfMiDispHi
      case SBfMiDispLo => SelHw.SBfMiDispLo
      case SBfOffDyn => SelHw.SBfOffDyn
      case SBfOffReg => SelHw.SBfOffReg
      case SBfPcRelConst => SelHw.SBfPcRelConst
      case SBfRdDst => SelHw.SBfRdDst
      case SBfResImm => SelHw.SBfResImm
      case SBfWdDyn => SelHw.SBfWdDyn
      case SCas2Da1 => SelHw.SCas2Da1
      case SCas2Da2 => SelHw.SCas2Da2
      case SCas2Dc1 => SelHw.SCas2Dc1
      case SCas2Dc2 => SelHw.SCas2Dc2
      case SCas2Du1 => SelHw.SCas2Du1
      case SCas2Du2 => SelHw.SCas2Du2
      case SCas2Rn1 => SelHw.SCas2Rn1
      case SCas2Rn2 => SelHw.SCas2Rn2
      case SCasDc => SelHw.SCasDc
      case SCasDu => SelHw.SCasDu
      case SDeltaAx => SelHw.SDeltaAx
      case SDeltaAy => SelHw.SDeltaAy
      case SDn2 => SelHw.SDn2
      case SEaBase => SelHw.SEaBase
      case SEaDispHi => SelHw.SEaDispHi
      case SEaDispLo => SelHw.SEaDispLo
      case SImm12 => SelHw.SImm12
      case SImm16 => SelHw.SImm16
      case SImm4 => SelHw.SImm4
      case SImm8 => SelHw.SImm8
      case SMiImm => SelHw.SMiImm
      case SMiOd => SelHw.SMiOd
      case SMiOther => SelHw.SMiOther
      case SMiOtherEaBase => SelHw.SMiOtherEaBase
      case SMiOtherEaDispLo => SelHw.SMiOtherEaDispLo
      case SMiOtherOd => SelHw.SMiOtherOd
      case SMove16Ay => SelHw.SMove16Ay
      case SMovesAn => SelHw.SMovesAn
      case SMovesDelta => SelHw.SMovesDelta
      case SMovesRn => SelHw.SMovesRn
      case SNegDeltaAx => SelHw.SNegDeltaAx
      case SNegDeltaAy => SelHw.SNegDeltaAy
      case SNone => SelHw.SNone
      case SPackAdj => SelHw.SPackAdj
      case SRetPc => SelHw.SRetPc
      case SShift8 => SelHw.SShift8
      case ST0 => SelHw.ST0
      case ST1 => SelHw.ST1
      case ST2 => SelHw.ST2
      case ST3 => SelHw.ST3
    }
    b.srcA := sel(d.srcA)
    b.srcB := sel(d.srcB)
    b.srcC := sel(d.srcC)
    b.dst  := sel(d.dst)
    b.imm  := sel(d.imm)

    b.sz := (d.sz match {
      case SzCtx  => SzHw.SzCtx
      case SzLong => SzHw.SzLong
      case SzWord => SzHw.SzWord
      case SzByte => SzHw.SzByte
      case SzHost => SzHw.SzHost
    })

    b.useImm             := Bool(d.useImm)
    b.writesFlags         := Bool(d.writesFlags)
    b.nzvcOnly            := Bool(d.nzvcOnly)
    b.indexFromEa         := Bool(d.indexFromEa)
    b.indexFromMiOtherEa  := Bool(d.indexFromMiOtherEa)
    b.bfStoreForm         := U(d.bfStoreForm, 3 bits)
    b.bfWritesNz          := Bool(d.bfWritesNz)
    b.bfDyn               := Bool(d.bfDyn)
    b.bfTstForm           := Bool(d.bfTstForm)
    b.bfDrop              := Bool(d.bfDrop)
    b.bfIllegal           := Bool(d.bfIllegal)
    b.miPtrIndex          := Bool(d.miPtrIndex)
    b.miHostIndex         := Bool(d.miHostIndex)
    b.miMoveFlags         := Bool(d.miMoveFlags)
    b.keepCommit          := Bool(d.keepCommit)
    b.isFirst             := Bool(d.isFirst)
    b.isLast              := Bool(d.isLast)

    b
  }

  /** The ROM (index = µPC). v1 has one customer family (BCD/ADDX/SUBX mem). The op kind
    * (BCD vs ADDX vs SUBX), size, and bcdSub come from the CONTEXT, so ONE 6-row sequence
    * serves all four mem forms (they differ only in ctx fields). */
  val BCD_MEM_ENTRY = 0
  private def romP1(): Vector[Desc] = Vector(
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
    //   p0 LOAD.L ptr -> T0 ; p1 LOAD.host (T0+od) -> T1 ; p2 op Dm,T1 -> Dm (+flags)
    // Operand order (FUZZER-CAUGHT): the ALU computes a-b with srcA as BOTH the
    // destination operand and the .B/.W partial-merge (old-value) source, so the
    // host op must read srcA = Dm (the dest Dn) and srcB = T1 (the loaded EA value)
    // — `cmp/sub <ea>,Dm` is Dm - mem. (The pre-fix ST1/SMiOther order produced
    // reversed CMP flags, a mem-minus-Dm SUB, and a T1-upper .B/.W merge.)
    // CMP additionally writes NO register — resolve() forces dstValid off for a
    // CMP host op (the ROM dst slot is only meaningful for the writing ops).
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                      // µPC21
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                     // µPC22
    Desc(UMiHostOp, srcA = SMiOther, srcB = ST1, dst = SMiOther, isLast = true),  // µPC23

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

  )
  private def romP2(): Vector[Desc] = Vector(
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
    //   r1 ADD An + signedDelta -> An  (the (An)+/-(An) write-back; a DROPPED crack µop,
    //        like the BCD/MOVEM An update. signedDelta = +size (POSTINC) / -size (PREDEC) /
    //        0 (no auto -> an identity An:=An+0 NOP, harmless).) Placed BEFORE the Rn
    //        write (A2 fix): Musashi's GET_EA_AY macro mutates An as part of the EA calc,
    //        THEN `REG_DA[Rn] = read(...)` OVERWRITES whatever Rn currently holds — so
    //        when Rn IS the EA's An, the auto-update is moot/overwritten by the load. Our
    //        µop order must match: the write that can alias (Rn) must land LAST so it
    //        wins, exactly like MOVEM's `movemLoadDst` already orders AnUpdate before the
    //        kept write.
    //   r2 MOVE T0 -> Rn (UMovesRead): An sign-ext.sz / Dn size-merge.sz; NO CCR. isLast
    //        (the macro's kept commit). moveaResult (the isMovea/An-dest path) reads ONLY
    //        srcB=T0 — srcA=SMovesRn (used for the Dn size-merge's "old value") is a
    //        DIFFERENT physical register in the Dn case (never aliases An), so the reorder
    //        is safe both ways.
    Desc(UMove, mem = MLoad, auto = AEaCasLoad, srcA = SEaBase, dst = ST0, useImm = true,
         imm = SEaDispLo, indexFromEa = true, isFirst = true),                 // µPC46 (r0)
    Desc(UAddDrop, srcA = SMovesAn, dst = SMovesAn, useImm = true, imm = SMovesDelta),  // µPC47 (r1)
    Desc(UMovesRead, srcA = SMovesRn, srcB = ST0, dst = SMovesRn, isLast = true),        // µPC48 (r2)

    // ════════════════════════════════════════════════════════════════════════
    // Bit-field DYNAMIC offset/width MEMORY forms (slice 3c). The dynamic offset/width
    // come from Dn[off]=ext[8:6] (iff Do=ext[11]) / Dn[wd]=ext[2:0] (iff Dw=ext[5]). A
    // BFRESOLVE-mem µop packs {origOff[18:14], needHi=1[13], bitOff[12:10], rawWidth[9:5]}
    // into a temp (T2) the funnel reads via srcC (bfDyn). ALWAYS-5-byte (needHi forced True;
    // the funnel leaves the spill byte unchanged when the field doesn't reach it). Signed
    // byteBase: for Do=0 the static offset>>3 folds into eaDispLo (like 3a); for Do=1 the
    // runtime byteDelta = Dn[off] >>>signed 3 (ASR.L #3) + eaBase -> Tb is recomputed per
    // access (kept within T0/T1/T2 — NO archDepth bump). All three live temps {lo,hi,packed}
    // are read by the funnel; the funnel result goes to Dn2 (EXTU/EXTS/FFO) / none (TST).

    // BF_DYN_RD_DO0 @49 — read-only, STATIC offset (Do=0, Dw=1). byteBase folded.
    //   resolve -> T2 ; load.L lo -> T0 ; load.B hi -> T1 ; funnel -> Dn2/none (+NZ)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong, isFirst = true),                        // µPC49
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC50
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC51
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, isLast = true),                      // µPC52

    // BF_DYN_RD_DO1 @53 — read-only (NOT FFO), DYNAMIC offset (Do=1). byteBase recomputed.
    //   ASR Dn[off],#3 -> T0 ; ADD eaBase+T0 -> Tb(T2) ; load.L lo -> T0 ; load.B hi -> T1
    //   ; resolve -> T2 ; funnel -> Dn2/none (+NZ)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong, isFirst = true),                                       // µPC53
    Desc(UBfAdd, srcA = SEaBase, srcB = ST0, dst = ST2, sz = SzLong),          // µPC54 (Tb)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC55
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC56
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                        // µPC57
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, isLast = true),                      // µPC58

    // BF_DYN_FFO_DO1 @59 — BFFFO, DYNAMIC offset (Do=1). One KEPT funnel writes Dn2 + flags:
    // a prefunnel collapses lo/hi -> field32 (T0), then a register-form FFO over field32 sets
    // ffoBase = the FULL signed Dn[off] (srcB) so the result = Dn[off] + first-set-index in ONE
    // committed step (Musashi: result = original_offset + index; N/Z from the field).
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong, isFirst = true),                                       // µPC59 (byteDelta -> T0)
    Desc(UBfAdd, srcA = SEaBase, srcB = ST0, dst = ST2, sz = SzLong),          // µPC60 (Tb = eaBase + byteDelta)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC61 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC62 (hi -> T1)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                        // µPC63 (packed -> T2)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                      // µPC64 (prefunnel: field32 -> T0)
    Desc(UBfMem, srcA = ST0, srcB = SBfOffReg, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, bfStoreForm = 6, isLast = true),     // µPC65 (FFOFULL: Dn2 = Dn[off] + index, +NZ)

    // ════════════════════════════════════════════════════════════════════════
    // Bit-field DYNAMIC offset/width MEMORY RMW (BFCHG/BFCLR/BFSET) — slice 3c. All-bfMem
    // funnels (RES -> LO5/HI5 -> store), mirroring the 3b 5-byte chain but with the packed
    // {bitOff/needHi/origOff/rawWidth} from the BFRESOLVE temp (srcC) instead of the static
    // imm. ALWAYS-5-byte. NZ flags from the ORIGINAL field (the RES compute). BFINS (Dn2
    // insert) has its own INS_DO0/INS_DO1 entries below (prefunnel + register-form insert).

    // BF_DYN_RMW_DO0 @66 — STATIC offset (Do=0, Dw=1). byteBase folds; LO5/HI5 use imm bitOff.
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong, isFirst = true),                        // µPC66 (packed -> T2)
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC67 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC68 (hi -> T1)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                     // µPC69 (RES -> T2; +NZ from orig field)
    Desc(UBfMem, srcA = ST0, srcB = ST2, dst = ST0, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 2),                                    // µPC70 (LO5: lo' -> T0, imm bitOff)
    Desc(UBfMem, srcA = ST1, srcB = ST2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 3),                                    // µPC71 (HI5: hi' -> T1, imm bitOff)
    Desc(UMove, mem = MStore, srcA = SEaBase, srcB = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC72 (store lo')
    Desc(UMove, mem = MStore, srcA = SEaBase, srcB = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true, isLast = true),                      // µPC73 (store hi')

    // BF_DYN_RMW_DO1 @74 — DYNAMIC offset (Do=1). byteBase = eaBase + (Dn[off]>>>3) recomputed
    // for the load + store phases; LO5RAW/HI5RAW read bitOff from Dn[off] (srcC) so `packed`
    // need not be held (3 temps).
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong, isFirst = true),                                         // µPC74 (byteDelta -> T0)
    Desc(UBfAdd, srcA = SEaBase, srcB = ST0, dst = ST2, sz = SzLong),          // µPC75 (Tb)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC76 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC77 (hi -> T1)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                        // µPC78 (packed -> T2)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                     // µPC79 (RES -> T2; +NZ)
    Desc(UBfMem, srcA = ST0, srcB = ST2, srcC = SBfOffReg, dst = ST0, sz = SzLong,
         bfStoreForm = 4),                                                     // µPC80 (LO5RAW: lo' -> T0, bitOff=Dn[off]&7)
    Desc(UBfMem, srcA = ST1, srcB = ST2, srcC = SBfOffReg, dst = ST1, sz = SzLong,
         bfStoreForm = 5),                                                     // µPC81 (HI5RAW: hi' -> T1)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST2, useImm = true, imm = SBfDeltaImm,
         sz = SzLong),                                                         // µPC82 (byteDelta -> T2)
    Desc(UBfAdd, srcA = SEaBase, srcB = ST2, dst = ST2, sz = SzLong),          // µPC83 (Tb recompute)
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC84 (store lo')
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true, isLast = true),                      // µPC85 (store hi')

    // BF_DYN_ILLEGAL @86 — a single vector-4 ILLEGAL µop. Routed to by the OUT-OF-SCOPE
    // Do=1-at-abs-EA dynamic RMW/INS forms (the DO1 chains need a base REGISTER for the
    // byteBase add; an abs EA has none) — traps, NOT silent-wrong. Also keeps the entry
    // numbering above undisturbed.
    Desc(UMove, bfIllegal = true, isFirst = true, isLast = true),              // µPC86

  )
  private def romP3(): Vector[Desc] = Vector(
    // ════════════════════════════════════════════════════════════════════════
    // Bit-field DYNAMIC offset/width MEMORY BFINS (slice 3c). The RES compute for CHG/CLR/SET
    // reads {lo, hi, packed} on srcA/B/C; BFINS additionally needs the Dn2 INSERT source — a
    // 4th register source with nowhere to ride. Resolve it with the register-form insert:
    //   (1) a PREFUNNEL (bfMem + bfTstForm) collapses lo/hi + bitOff -> the left-justified
    //       field32 (a temp) — freeing the lo/hi source slots;
    //   (2) a register-form BFINS (UBfReg, bfMem=False, offset=0 from packed[4:0]) inserts Dn2
    //       into field32 -> newField32 (+ N/Z from the INSERTED value, Musashi verbatim:
    //       insert_base = Dn2<<(32-width); FLAG_N=bit31, FLAG_Z=(insert_base==0), V=C=0, X UNTOUCHED);
    //   (3) re-LOAD lo/hi and run the LO5/HI5 inverse funnel (which needs ONLY bitOff, not width)
    //       -> lo'/hi' -> STORE. ALWAYS-5-byte. The reload keeps the live temp set at 3 (T0/T1/T2)
    //       — NO archDepth bump (the field32/newField32 reuse the lo/hi/packed slots after they die).

    // ALL loads precede ALL stores in each chain — a store followed by a younger load whose
    // byte range shares the store's 4-byte block (unaligned/negative byteBase) deadlocks the LS
    // forwarding, so we reconstruct BOTH lo'/hi' first, then store both (Tb recomputed to fit 3 temps).

    // BF_DYN_INS_DO0 @87 — STATIC offset (Do=0; width may be dynamic). byteBase folds into the
    // disp; the inverse funnel takes the STATIC bitOff from SBfImm (bfDyn=False on LO5/HI5).
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong, isFirst = true),                        // µPC87 (packed -> T2)
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC88 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC89 (hi -> T1)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                      // µPC90 (prefunnel: field32 -> T0)
    Desc(UBfReg, srcA = ST0, srcB = SBfDn2, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                     // µPC91 (register-BFINS: newField32 -> T2; +N/Z)
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC92 (lo reload -> T0)
    Desc(UBfMem, srcA = ST0, srcB = ST2, dst = ST0, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 2),                                        // µPC93 (LO5: lo' -> T0, static bitOff)
    Desc(UMove, mem = MLoad, srcA = SEaBase, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC94 (hi reload -> T1)
    Desc(UBfMem, srcA = ST1, srcB = ST2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 3),                                        // µPC95 (HI5: hi' -> T1, static bitOff)
    Desc(UMove, mem = MStore, srcA = SEaBase, srcB = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC96 (store lo')
    Desc(UMove, mem = MStore, srcA = SEaBase, srcB = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true, isLast = true),                      // µPC97 (store hi')

    // BF_DYN_INS_DO1 @98 — DYNAMIC offset (Do=1). byteBase = eaBase + (Dn[off]>>>3) recomputed per
    // phase (never held across the funnel); the inverse funnel takes bitOff = Dn[off]&7 from srcC
    // (LO5RAW/HI5RAW). All 4 loads precede both stores. 3 temps: newField32 rides T2 through phase B.
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong, isFirst = true),                                         // µPC98 (byteDelta -> T0)
    Desc(UBfAdd, srcA = SEaBase, srcB = ST0, dst = ST2, sz = SzLong),          // µPC99 (Tb -> T2)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC100 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC101 (hi -> T1)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                        // µPC102 (packed -> T2; Tb dead)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                      // µPC103 (prefunnel: field32 -> T0)
    Desc(UBfReg, srcA = ST0, srcB = SBfDn2, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                     // µPC104 (register-BFINS: newField32 -> T2; +N/Z)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong),                                                         // µPC105 (byteDelta -> T0)
    Desc(UBfAdd, srcA = SEaBase, srcB = ST0, dst = ST0, sz = SzLong),          // µPC106 (Tb -> T0)
    Desc(UMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC107 (lo reload -> T1)
    Desc(UBfMem, srcA = ST1, srcB = ST2, srcC = SBfOffReg, dst = ST1, sz = SzLong,
         bfStoreForm = 4),                                                     // µPC108 (LO5RAW: lo' -> T1, bitOff=Dn[off]&7)
    Desc(UMove, mem = MLoad, srcA = ST0, dst = ST0, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                     // µPC109 (hi reload -> T0; Tb consumed)
    Desc(UBfMem, srcA = ST0, srcB = ST2, srcC = SBfOffReg, dst = ST0, sz = SzLong,
         bfStoreForm = 5),                                                     // µPC110 (HI5RAW: hi' -> T0)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST2, useImm = true, imm = SBfDeltaImm,
         sz = SzLong),                                                         // µPC111 (byteDelta -> T2; newField32 dead)
    Desc(UBfAdd, srcA = SEaBase, srcB = ST2, dst = ST2, sz = SzLong),          // µPC112 (Tb recompute -> T2)
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST1, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                     // µPC113 (store lo')
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST0, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true, isLast = true),                      // µPC114 (store hi')

    // ════════════════════════════════════════════════════════════════════════
    // CMPM.B/.W/.L (Ay)+,(Ax)+ — 5 µops @ CMPM_ENTRY=115 (placed after the full bit-field
    // 3c dynamic range 49-114 to avoid an index collision). Template: BCD_MEM_ENTRY's dual-
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
    Desc(UMove, mem = MLoad, auto = APostincAy, srcA = SAy, dst = ST0, isFirst = true),  // µPC115 (e0)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SDeltaAy),                // µPC116 (e1)
    Desc(UMove, mem = MLoad, auto = APostincAx, srcA = SAx, dst = ST1),                  // µPC117 (e2)
    Desc(UAddDrop, srcA = SAx, dst = SAx, useImm = true, imm = SDeltaAx),                // µPC118 (e3)
    Desc(UOpFromCtx, srcA = ST1, srcB = ST0, nzvcOnly = true, isLast = true),             // µPC119 (e4)

    // ════════════════════════════════════════════════════════════════════════
    // MI_MOVE_EAEA @120 (task #119, deep-audit follow-up): MOVE mem-indirect src-EA ->
    // a PLAIN (non-register) memory dst-EA. Neither operand is a register, so the value
    // moves straight from the loaded temp to the store — no register write, no dst-side
    // pointer load (the plain side's address rides its OWN independent base/disp/index
    // group, ctx.miOtherEa*, decoded separately from the mem-indirect side's eaBase/
    // eaDispLo/eaIndex, which stays dedicated to the pointer-load).
    //   f0 LOAD.L ptr -> T0 (pre-index)                                        (isFirst)
    //   f1 LOAD.host (T0+od (+post-idx)) -> T1
    //   f2 STORE.host T1 -> (miOtherEaBase+miOtherEaDispLo(+miOtherIndex))     (isLast)
    // task #154 (ported-tests memind cluster): f2 carries `auto = AEaMiOtherStore` so a
    // (Am)+/-(Am) "other" dst (e.g. MOVE ([...]),-(A7) / MOVE ([...]),(A1)+) gets its
    // auto-inc/dec address adjustment + An write-back, exactly like the ordinary MOVE
    // crack's crackStore already does for a non-mem-indirect dst.
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC120 (f0)
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                                // µPC121 (f1)
    Desc(UMiHostMove, mem = MStore, auto = AEaMiOtherStore, srcA = SMiOtherEaBase, srcB = ST1,
         useImm = true, imm = SMiOtherEaDispLo, sz = SzHost, indexFromMiOtherEa = true,
         miMoveFlags = true, isLast = true),                                              // µPC122 (f2)

    // MI_MOVE_EAEA_REV @123 (task #147 wired this entry live): the MIRROR direction —
    // MOVE a PLAIN (non-register) memory src-EA -> a mem-indirect dst-EA. Same "no
    // register write" shape as MI_MOVE_EAEA, reversed: load straight from the plain side
    // (no pointer needed for it), then store through the mem-indirect pointer.
    //   g0 LOAD.L ptr -> T0 (pre-index)                                        (isFirst)
    //   g1 LOAD.host (miOtherEaBase+miOtherEaDispLo(+miOtherIndex)) -> T1
    //   g2 STORE.host T1 -> (T0+od (+post-idx))                                (isLast)
    // KNOWN RESIDUAL GAP (task #154, deliberately NOT fixed here, mirrors the DIVREM
    // gap documented in DivEuPlugin.scala/task #149): unlike MI_MOVE_EAEA's STORE (the
    // ONLY µop touching the "other" side, so folding its auto-update + An write-back into
    // ONE µop via AEaMiOtherStore's dst-override trick — mirroring AEaCasStore — is
    // correct and complete), this LOAD is likewise the ONLY µop touching the "other"
    // side, but the established convention for a load-side auto (APredecAy/APredecAx,
    // see their own comment above) is "eaAuto adjusts the ACCESS address but never
    // writes An — a SEPARATE dropped UAddDrop µop does that" (a 2-µop idiom, e.g. rows
    // 190-193). Naively reusing AEaMiOtherStore's single-µop trick here would compute
    // the CORRECT loaded value but silently leave An un-updated for a (Am)+/-(Am) "other"
    // SOURCE (e.g. `MOVE -(A7),([...])`) — a real but narrower gap (data correct, An
    // stale) than a full miscompute, and NOT currently exercised by any known ported
    // test (every failing MOVE-family test needing this fix is the FORWARD direction,
    // src=mem-indirect / dst=plain (An)+/-(An) — MI_MOVE_EAEA's g2, not this entry's
    // g1). Proper fix: insert a dropped `UAddDrop` row after g1 (mirrors rows 190-193),
    // bumping MI_MOVE_BOTH_MI_ILLEGAL_ENTRY's row constant by 1 — deferred as a
    // follow-up given no known repro exercises it yet.
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC123 (g0)
    Desc(UMiHostMove, mem = MLoad, srcA = SMiOtherEaBase, dst = ST1, useImm = true,
         imm = SMiOtherEaDispLo, sz = SzHost, indexFromMiOtherEa = true),                  // µPC124 (g1)
    Desc(UMiHostMove, mem = MStore, srcA = ST0, srcB = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true, miMoveFlags = true, isLast = true),              // µPC125 (g2)

    // MI_MOVE_BOTH_MI_ILLEGAL @126 (task #119 scope limit): a MOVE with BOTH src AND dst
    // full-format memory-indirect simultaneously needed a 4-µop/2-pointer/3-temp chain
    // (deliberately out of scope for that slice — rare in practice). Trap vector-4 rather
    // than silently mis-executing (the F2-class failure mode this whole task exists to
    // avoid); a real completeness gap, but safe.
    // task #204: the non-post-indexed-dst subset of this shape is now HANDLED (see
    // MI_MOVE_BOTH_MI_ENTRY @238, routed via DecodeStage's `ucMoveBothMiOk`). This entry
    // remains live ONLY for a post-indexed dst (`([bd,An],Xn,od)` — Xn would need to apply
    // at the FINAL store, which @238's single second-pointer-load doesn't thread through) —
    // still a real, narrower, deliberately-scoped-out completeness gap, but safe (traps
    // rather than mis-executing).
    Desc(UMove, bfIllegal = true, isFirst = true, isLast = true),                         // µPC126

    // MI_MOVE_DST_IMM @127 (task #178, ported-tests cluster 11: move_bwl_imm_src_memind_dst):
    // MOVE #imm,<mem-indirect-dst> — the immediate SOURCE variant of MI_MOVE_DST_ENTRY (@19,
    // which only ever worked for a REGISTER source). Appended as a NEW entry (not merged into
    // @19/@20) rather than renumbering every entry after it — pure addition, zero blast radius
    // on the many already-passing entries/tests keyed to the existing row numbers.
    //
    // ROOT CAUSE this entry fixes: `UMiHostMove` (the µop kind MI_MOVE_DST_ENTRY's store row
    // @20 uses) NEVER reads `ctx.miOtherIsImm`/`ctx.miHostImm` — only `UMiHostOp` (the ALU-op
    // host kind used by MI_RMW_ENTRY/MI_FLAGS_ENTRY/etc, task #156's "already built for the
    // line-0-imm/ADDQ families") does. A plain MOVE's store row is a `UMiHostMove` (its own
    // `useImm`/`imm` slot is ALREADY spoken for by the STORE ADDRESS's od displacement,
    // `SMiOd` — one row cannot carry both an address immediate AND a data immediate at once,
    // exactly the constraint MicroOpAssembler.scala's `immToMemCase` comment documents for the
    // ORDINARY (non-mem-indirect) `MOVE #imm,<mem>` crack). Reusing @19/@20 unmodified for an
    // immediate source therefore silently read `ctx.miOther`/`ctx.miOtherValid` as if they were
    // a REAL register — but the src EA's #imm encoding's `reg` bits (mode7/reg4) are NOT a
    // register id, so this read garbage (observed: D4's stale/reset value, 0, silently stored
    // instead of the real immediate — a genuine data-corruption bug, not a hang).
    //
    // FIX (mirrors MicroOpAssembler.scala's established "materialize immediate into a scratch
    // temp, then store the temp" 2-µop idiom for the identical non-mem-indirect problem):
    //   h0 LOAD.L ptr -> T0 (pre-index)                                        (isFirst)
    //   h1 MOVE ctx.miHostImm -> T1  (plain `UMove`, not `UMiHostOp` -- deliberately does NOT
    //      write flags: `writesFlags`/`nzvcOnly` both default False on the generic `UMove`
    //      resolve() path, unlike `UMiHostOp` which unconditionally ties `writesNzvc` to
    //      `ctx.miWNzvc` regardless of isLast -- using `UMiHostOp` here would have made this
    //      intermediate materialize row ALSO commit flags, alongside the real store row below)
    //   h2 STORE.host T1 -> (T0+od (+post-idx)), sets NZVC per ctx.miWNzvc              (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC127 (h0)
    Desc(UMove, useImm = true, imm = SMiImm, dst = ST1),                                  // µPC128 (h1)
    Desc(UMiHostMove, mem = MStore, srcA = ST0, srcB = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true, miMoveFlags = true, isLast = true),             // µPC129 (h2)

  )
  private def romP4(): Vector[Desc] = Vector(
    // ════════════════════════════════════════════════════════════════════════
    // Bit-field MEMORY-INDIRECT (68020+ full-format [bd,An],od / [bd,An,od]) — task #197.
    // Reuses the EXISTING generic MI pointer-resolve pattern (UMiPtrLoad, identical to the
    // §5 host-op crack above) to materialize the pointer into a temp, THEN chains into the
    // SAME bit-field funnel/RES/LO5/HI5 compute already used by the register-direct BF_DYN_*
    // family (slice 3c) — no parallel ROM family built from scratch. Every row anchors its
    // load/store on the TEMP holding the resolved pointer (srcA=STn) instead of SEaBase (an
    // architectural An), using the NEW SBfMiDispLo/Hi selectors (= ctx.miOd + byteOff, the
    // POST-dereference byte address) instead of SEaDispLo/Hi (which stay reserved for the
    // POINTER's own bd, used only by the UMiPtrLoad rows). Static-offset (Do=0) entries
    // below are NO-INDEX only (od present, Xn absent — [bd,An],od with I/IS=100);
    // pre/post-indexed STATIC-offset memind bit-fields remain a separate, NOT-YET-
    // IMPLEMENTED gap. The DYNAMIC-offset (Do=1) entries (MI_BF_RD_DO1/RMW_DO1/INS_DO1,
    // task #203, see romP7 below) DO support pre/post-indexing.
    //
    // TEMP BUDGET (why static-offset RMW/INS below uses an EA-recompute): the DO0 entries
    // below use the ORIGINAL 3-temp (T0/T1/T2) engine. A no-index memind RMW/INS needs the
    // pointer ALIVE at both the initial loads AND (after the RES/splice compute consumes
    // lo/hi/packed, all 3 temps) AGAIN for the final store(s) — since dereferencing needs
    // its own LOAD, MI_BF_RMW_DO0/INS_DO0 use "EA-recompute" (re-run the SAME 1-µop pointer
    // load a second time) rather than holding it live throughout. That recompute needs only
    // 1 temp for a STATIC offset (Do=0 — byteOff folds into the disp at decode time,
    // cost-free), which is why it was scoped DO0-only.
    //
    // task #203 UPDATE: a genuine 4th temp (T3, see the engine-temps header comment above)
    // turned out to be a small, mechanical, low-risk addition (ARCH_INT_REGS 19->20,
    // log2Up stays 5 — mirrors the T2-adding precedent, commit 8a1cbd3) rather than "a
    // genuinely new mechanism". With T3 available, the DYNAMIC-offset (Do=1) RMW/INS
    // entries (MI_BF_RMW_DO1/MI_BF_INS_DO1, romP7 below) hold the resolved pointer/Tb
    // PERSISTENTLY in T3 across the ENTIRE funnel compute — no EA-recompute needed at all
    // (simpler than the DO0 entries, not just "possible"). Previously routed to the
    // BF_DYN_ILLEGAL_ENTRY vector-4 trap; now real entries. See romP7's header comment for
    // the exact row-by-row temp-liveness accounting.

    // MI_BF_RD_DO0 @130 — read-only (BFTST/BFEXTU/BFEXTS/BFFFO), STATIC offset (Do=0; width
    // may be dynamic). byteOff folds into ctx.bfMiDispLo/Hi (computed at ucBegin exactly
    // like the register-direct ucBfDispLo/Hi fold, just based on ctx.miOd instead of the
    // EA's own disp). Mirrors BF_DYN_RD_DO0's shape with a 1-µop pointer-resolve prefix.
    //   i0 LOAD.L ptr = (An+bd) -> T2                                          (isFirst)
    //   i1 LOAD.L  [ptr+byteAddr]   -> T0 (lo)
    //   i2 LOAD.B  [ptr+byteAddr+4] -> T1 (hi)
    //   i3 BFRESOLVE {off,wid} -> T2 (packed; ptr dead, reused)
    //   i4 BITFIELD bfMem funnel -> Dn2/none (+NZ)                             (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST2, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC130 (i0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC131 (i1)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte),                                                                    // µPC132 (i2)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC133 (i3)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, isLast = true),                                 // µPC134 (i4)

    // MI_BF_RD_DO1 @135 — read-only, DYNAMIC offset (Do=1; covers BFTST/BFEXTU/BFEXTS —
    // BFFFO's dynamic-offset memind form is a narrower NOT-YET-IMPLEMENTED sub-gap, routed
    // to BF_DYN_ILLEGAL_ENTRY by DecodeStage, mirroring the register-direct FFO_DO1 split).
    // No store phase -> no EA-recompute needed (see the family header comment above) — the
    // pointer temp (T2) stays live as "Tb" (ptr + runtime byteDelta) straight through both
    // loads, exactly like the register-direct DO1 chain's Tb.
    //
    // task #203: j3/j4 carry `miHostIndex = true` (the pointer load j0 already carried
    // `miPtrIndex = true` since task #197 — unused until now since the front-end gate
    // excluded indexed EAs). `ctx.miPost` (task #203: now correctly sourced from the bit-
    // field's OWN EA decode for a bit-field-memind op, see DecodeStage's `ucEntryCtx.miPost`
    // assignment) gates which one actually fires: PRE-indexed ([bd,An,Xn],od) adds Xn*scale
    // at j0 (the dereference), POST-indexed ([bd,An],Xn*scale,od) adds it here at j3/j4 (the
    // post-deref access) — the exact same generic mechanism the §5 host-op family already
    // uses, no new hardware. A no-index EA leaves `ctx.eaIndexValid`/`ctx.miPost` both False,
    // so this is a complete no-op for every pre-existing no-index caller (zero regression
    // risk — verified via the mandatory lock-step + full-corpus resweep).
    //   j0 LOAD.L ptr = (An+bd(+pre-idx)) -> T2                                (isFirst)
    //   j1 BFRESOLVE byteDelta = Dn[off]>>>3 -> T0
    //   j2 ADD Tb = ptr + byteDelta -> T2
    //   j3 LOAD.L  [Tb+od(+post-idx)]   -> T0 (lo)
    //   j4 LOAD.B  [Tb+od(+post-idx)+4] -> T1 (hi; Tb dead after this, no store phase)
    //   j5 BFRESOLVE {off,wid} -> T2 (packed)
    //   j6 BITFIELD bfMem funnel -> Dn2/none (+NZ)                             (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST2, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC135 (j0)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong),                                                                    // µPC136 (j1)
    Desc(UBfAdd, srcA = ST2, srcB = ST0, dst = ST2, sz = SzLong),                          // µPC137 (j2, Tb)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong, miHostIndex = true),                                                // µPC138 (j3)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, miHostIndex = true),                                                // µPC139 (j4)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC140 (j5)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, isLast = true),                                 // µPC141 (j6)

    // MI_BF_RMW_DO0 @142 — BFCHG/BFCLR/BFSET, STATIC offset (Do=0; width may be dynamic).
    // Mirrors BF_DYN_RMW_DO0's RES/LO5/HI5 shape, EA-recomputed (a 2nd 1-µop pointer LOAD)
    // before the store pair (see the family header comment for why).
    //   k0 LOAD.L ptr = (An+bd) -> T2                                          (isFirst)
    //   k1 LOAD.L  [ptr+byteAddr]   -> T0 (lo)
    //   k2 LOAD.B  [ptr+byteAddr+4] -> T1 (hi; ptr dead)
    //   k3 BFRESOLVE {off,wid} -> T2 (packed)
    //   k4 BITFIELD bfMem RES  srcA=T0,srcB=T1,srcC=T2 -> T2 (res; +NZ from orig field)
    //   k5 BITFIELD bfMem LO5  srcA=T0,srcB=T2,imm=static bitOff -> T0 (lo')
    //   k6 BITFIELD bfMem HI5  srcA=T1,srcB=T2,imm=static bitOff -> T1 (hi'; res dead)
    //   k7 LOAD.L ptr (RECOMPUTE) = (An+bd) -> T2
    //   k8 STORE.L T0(lo') -> [ptr+byteAddr]
    //   k9 STORE.B T1(hi') -> [ptr+byteAddr+4]                                 (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST2, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC142 (k0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC143 (k1)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte),                                                                    // µPC144 (k2)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC145 (k3)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                                // µPC146 (k4)
    Desc(UBfMem, srcA = ST0, srcB = ST2, dst = ST0, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 2),                                                   // µPC147 (k5)
    Desc(UBfMem, srcA = ST1, srcB = ST2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 3),                                                   // µPC148 (k6)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST2, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true),                                                 // µPC149 (k7, recompute)
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC150 (k8)
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, isLast = true),                                                     // µPC151 (k9)

    // MI_BF_INS_DO0 @152 — BFINS, STATIC offset (Do=0; width may be dynamic). Mirrors
    // BF_DYN_INS_DO0's prefunnel/register-BFINS/reload+inverse-funnel shape, EA-recomputed
    // TWICE (once to re-derive the load address for the lo/hi RELOAD, once more for the
    // final store pair) — each recompute is a single 1-µop pointer LOAD, always ≤3 temps
    // concurrently live (verified per-row in the family header comment's budget analysis).
    //   l0 LOAD.L ptr = (An+bd) -> T2                                          (isFirst)
    //   l1 LOAD.L  [ptr+byteAddr]   -> T0 (lo)
    //   l2 LOAD.B  [ptr+byteAddr+4] -> T1 (hi)
    //   l3 BFRESOLVE {off,wid} -> T2 (packed)
    //   l4 BITFIELD bfMem prefunnel (bfTstForm) srcA=T0,srcB=T1,srcC=T2 -> T0 (field32)
    //   l5 BITFIELD bfReg register-BFINS srcA=T0,srcB=Dn2,srcC=T2 -> T2 (newField32; +N/Z)
    //   l6 LOAD.L ptr (RECOMPUTE) = (An+bd) -> T0
    //   l7 LOAD.L  [ptr+byteAddr]   -> T1 (lo reload; ptr(T0) still needed)
    //   l8 BITFIELD bfMem LO5 srcA=T1,srcB=T2,imm=static bitOff -> T1 (lo')
    //   l9 LOAD.B  [ptr+byteAddr+4] -> T0 (hi reload; overwrites dead ptr; T1=lo' preserved)
    //   l10 BITFIELD bfMem HI5 srcA=T0,srcB=T2,imm=static bitOff -> T0 (hi'; newField32 dead)
    //   l11 LOAD.L ptr (RECOMPUTE #2) = (An+bd) -> T2
    //   l12 STORE.L T1(lo') -> [ptr+byteAddr]
    //   l13 STORE.B T0(hi') -> [ptr+byteAddr+4]                                (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST2, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC152 (l0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC153 (l1)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte),                                                                    // µPC154 (l2)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC155 (l3)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                                 // µPC156 (l4)
    Desc(UBfReg, srcA = ST0, srcB = SBfDn2, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                                // µPC157 (l5)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true),                                                 // µPC158 (l6, recompute)
    Desc(UMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC159 (l7)
    Desc(UBfMem, srcA = ST1, srcB = ST2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 2),                                                   // µPC160 (l8)
    Desc(UMove, mem = MLoad, srcA = ST0, dst = ST0, useImm = true, imm = SBfMiDispHi,
         sz = SzByte),                                                                    // µPC161 (l9)
    Desc(UBfMem, srcA = ST0, srcB = ST2, dst = ST0, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 3),                                                   // µPC162 (l10)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST2, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true),                                                 // µPC163 (l11, recompute #2)
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST1, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC164 (l12)
    Desc(UMove, mem = MStore, srcA = ST2, srcB = ST0, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, isLast = true),                                                     // µPC165 (l13)

  )
  private def romP5(): Vector[Desc] = Vector(
    // ════════════════════════════════════════════════════════════════════════
    // PACK -(Ay),-(Ax),#adj @166 (task #198). Musashi (m68k_in.c m68k_op_pack_16_mm):
    //   REG_A[srcreg]--; src  = read8(Ay)          (FIRST read -> LOW byte of src)
    //   REG_A[srcreg]--; src |= read8(Ay)<<8        (SECOND read -> HIGH byte of src)
    //   src += adj (16-bit, wraps mod 0x10000)
    //   REG_A[dstreg]--; write8(Ax, ((src>>4)&0xF0)|(src&0x0F))
    // Same dual-predec-LOAD-from-Ay shape as BCD_MEM_ENTRY's Ay/Ax pair, but BOTH loads
    // come from the SAME register (Ay), and the compute REUSES the existing register-
    // form PACK datapath (AluEuPlugin's isPack cone) via UOpFromCtx (ctx.op=PACK) — the
    // ALU is told this is the MEMORY form (combine two separate byte temps instead of
    // reading a single 16-bit Dy) via `u.extByte`, set True by resolve() for exactly
    // this row (see resolve()'s op-select block; register-form PACK never reaches this
    // ROM at all — it's a single-µop fast crack in MicroOpAssembler — so ctx.op===PACK
    // can ONLY mean "this UOpFromCtx row", no collision risk).
    //   m0 LOAD.B (Ay) -> T0, auto=PREDEC(Ay)                                  (isFirst)
    //   m1 ADD.L Ay - deltaAy -> Ay   (1st Ay predec write-back; dropped)
    //   m2 LOAD.B (Ay) -> T1, auto=PREDEC(Ay)
    //   m3 ADD.L Ay - deltaAy -> Ay   (2nd Ay predec write-back; dropped)
    //   m4 PACK  srcA=T0(lo), srcB=T1(hi), imm=adj16 -> T2 (packed byte, low 8 bits)
    //   m5 STORE.B T2 -> (Ax), auto=PREDEC(Ax), dst=Ax (self-write-back)      (isLast)
    Desc(UMove, mem = MLoad, auto = APredecAy, srcA = SAy, dst = ST0, sz = SzByte,
         isFirst = true),                                                                 // µPC166 (m0)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SNegDeltaAy),               // µPC167 (m1)
    Desc(UMove, mem = MLoad, auto = APredecAy, srcA = SAy, dst = ST1, sz = SzByte),        // µPC168 (m2)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SNegDeltaAy),               // µPC169 (m3)
    Desc(UOpFromCtx, srcA = ST0, srcB = ST1, dst = ST2, useImm = true, imm = SPackAdj,
         sz = SzByte),                                                                    // µPC170 (m4)
    Desc(UMove, mem = MStore, auto = APredecAx, srcA = SAx, srcB = ST2, dst = SAx,
         sz = SzByte, isLast = true),                                                     // µPC171 (m5)

    // ════════════════════════════════════════════════════════════════════════
    // UNPK -(Ay),-(Ax),#adj @172 (task #198). Musashi (m68k_in.c m68k_op_unpk_16_mm):
    //   REG_A[srcreg]--; src = read8(Ay)
    //   src = ((src<<4)&0x0F00) | (src&0x000F)       (expand BCD nibbles into a byte pair)
    //   src += adj (16-bit, wraps mod 0x10000)
    //   REG_A[dstreg]--; write8(Ax, src)              (LOW  byte -> (Ax-1), FIRST write)
    //   REG_A[dstreg]--; write8(Ax, src>>8)            (HIGH byte -> (Ax-2), SECOND write)
    // The compute REUSES the existing register-form UNPK datapath unchanged (AluEuPlugin's
    // isUnpk cone already reads srcB via the raw rdB port as "Dy&0xffff" — memory-form's
    // single loaded byte T0 (0..255) is already a valid subset of that same 16-bit domain,
    // so NO mode flag / ALU change is needed for UNPK, unlike PACK). The result's HIGH byte
    // (bits 15:8) needs a real LSR-by-8 (UShiftR8, the barrel shifter) before its own BYTE
    // store, since a BYTE store always truncates to a register's LOW 8 bits.
    //   n0 LOAD.B (Ay) -> T0, auto=PREDEC(Ay)                                  (isFirst)
    //   n1 ADD.L Ay - deltaAy -> Ay   (Ay predec write-back; dropped)
    //   n2 UNPK  srcA=T0(dummy merge src, unused), srcB=T0, imm=adj16 -> T1 (unpacked
    //      16-bit result; T1[7:0]=LOW result byte, T1[15:8]=HIGH result byte)
    //   n3 STORE.B T1 -> (Ax), auto=PREDEC(Ax), dst=Ax   (LOW byte, written FIRST)
    //   n4 SHIFT.L T1 LSR #8 -> T2 (moves T1[15:8] down into T2[7:0])
    //   n5 STORE.B T2 -> (Ax), auto=PREDEC(Ax), dst=Ax   (HIGH byte, written SECOND) (isLast)
    Desc(UMove, mem = MLoad, auto = APredecAy, srcA = SAy, dst = ST0, sz = SzByte,
         isFirst = true),                                                                 // µPC172 (n0)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SNegDeltaAy),               // µPC173 (n1)
    Desc(UOpFromCtx, srcA = ST0, srcB = ST0, dst = ST1, useImm = true, imm = SPackAdj,
         sz = SzWord),                                                                    // µPC174 (n2)
    Desc(UMove, mem = MStore, auto = APredecAx, srcA = SAx, srcB = ST1, dst = SAx,
         sz = SzByte),                                                                    // µPC175 (n3)
    Desc(UShiftR8, srcA = ST1, dst = ST2, useImm = true, imm = SShift8, sz = SzLong),      // µPC176 (n4)
    Desc(UMove, mem = MStore, auto = APredecAx, srcA = SAx, srcB = ST2, dst = SAx,
         sz = SzByte, isLast = true),                                                     // µPC177 (n5)

    // ════════════════════════════════════════════════════════════════════════
    // Bit-field DYNAMIC-OFFSET (Do=1) PC-RELATIVE read-only forms @178 (task #199,
    // bf_pcrel_read.s cases 6-10). Byte-identical to BF_DYN_RD_DO1/BF_DYN_FFO_DO1 above
    // (see that family's header comment for the full read-only-dynamic shape) EXCEPT the
    // byteBase-recompute row: a (d16,PC) EA has baseValid=False (no An register), so
    // `UBfAdd srcA=SEaBase` (a REGISTER read) would read garbage instead of the implicit
    // PC base — substitute `srcA=T0(byteDelta), useImm=true, imm=SBfPcRelConst(=pc+4)`.
    // `eaDispLo` (still `ucBfEaDec.disp`, the raw EA disp — unaffected by this substitution)
    // applies on top at the load rows exactly like the register-base case, so the final
    // byte address is `pc+4 + disp + byteDelta`, matching the OLD 3a static crack's
    // `bfmPcRelAddr` reference point. Do=0 (dynamic-WIDTH-only, static offset) PC-rel stays
    // unimplemented (falls through to the pre-existing fail-safe illegal trap) — see
    // DecodeStage.scala's `s0bfDo`/`ucBfDynRdEntry` comments.

    // BF_DYN_RD_PCREL_DO1 @178 — read-only (NOT FFO), DYNAMIC offset, PC-relative EA.
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong, isFirst = true),                                       // µPC178 (byteDelta -> T0)
    Desc(UBfAdd, srcA = ST0, dst = ST2, useImm = true, imm = SBfPcRelConst,
         sz = SzLong),                                                       // µPC179 (Tb = byteDelta + pc+4)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                   // µPC180 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                   // µPC181 (hi -> T1)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                      // µPC182 (packed -> T2)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, isLast = true),                    // µPC183 (funnel)

    // BF_DYN_FFO_PCREL_DO1 @184 — BFFFO, DYNAMIC offset, PC-relative EA.
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong, isFirst = true),                                       // µPC184 (byteDelta -> T0)
    Desc(UBfAdd, srcA = ST0, dst = ST2, useImm = true, imm = SBfPcRelConst,
         sz = SzLong),                                                       // µPC185 (Tb = byteDelta + pc+4)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, indexFromEa = true),                                   // µPC186 (lo -> T0)
    Desc(UMove, mem = MLoad, srcA = ST2, dst = ST1, useImm = true, imm = SEaDispHi,
         sz = SzByte, indexFromEa = true),                                   // µPC187 (hi -> T1)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                      // µPC188 (packed -> T2)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                    // µPC189 (prefunnel: field32 -> T0)
    Desc(UBfMem, srcA = ST0, srcB = SBfOffReg, srcC = ST2, dst = SBfRdDst, sz = SzLong,
         bfDyn = true, bfWritesNz = true, bfStoreForm = 6, isLast = true),   // µPC190 (FFOFULL)

  )
  private def romP6(): Vector[Desc] = Vector(
    // ════════════════════════════════════════════════════════════════════════
    // Control-transfer / address-generate full-format MEMORY-INDIRECT (task #201):
    // LEA/PEA/JMP/JSR with a mode-6 / mode-7-reg-3 full-format EA. Unlike the §7 host-op
    // family above (which loads a VALUE through the resolved pointer), these need only the
    // RESOLVED ADDRESS itself: pointer = mem.L[base+bd(+pre-idx)] (the SAME UMiPtrLoad row
    // every other MI entry already uses), final = pointer + od (+ post-idx). LEA/PEA route
    // that final address straight to their real destination (An / a push) via the LS-EU's
    // existing `leaAddr` short-circuit (address-generate only, NO translate/NO cache access
    // — see LsEuPlugin's `when(u1.leaAddr)`, already used by the fast-path LEA/PEA crack);
    // JMP/JSR route it to the branch-EU's target adder (mirrors `ibrUop`/RTS's T0-based
    // ibranch — BranchEuPlugin already reads ANY int physical register as psrcA, not just
    // an architectural An, per RTS/RTR's existing T0-target precedent).

    // MI_LEA_ENTRY @191 (rows 191,192): ptr-load -> T0, then T0+od(+post-idx) -> An.
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                       // µPC191
    Desc(UMiLeaFinal, srcA = ST0, dst = SMiOther, useImm = true, imm = SMiOd,
         sz = SzLong, miHostIndex = true, isLast = true),                       // µPC192

    // MI_PEA_ENTRY @193 (rows 193,194,195): ptr-load -> T0, T0+od(+post-idx) -> T1,
    // push T1 -> -(A7) (the sole kept commit — mirrors the fast-path `peaPush.keepCommit`).
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                       // µPC193
    Desc(UMiLeaFinal, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzLong, miHostIndex = true),                                      // µPC194
    Desc(UMiPushFinal, mem = MStore, srcA = SA7, srcB = ST1, dst = SA7,
         sz = SzLong, keepCommit = true, isLast = true),                        // µPC195

    // MI_JMP_ENTRY @196 (rows 196,197): ptr-load -> T0, ibranch to T0+od(+post-idx).
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                       // µPC196
    Desc(UMiBranchFinal, srcA = ST0, useImm = true, imm = SMiOd,
         sz = SzLong, miHostIndex = true, isLast = true),                       // µPC197

    // MI_JSR_ENTRY @198 (rows 198,199,200): push retPC -> -(A7) (mirrors the fast-path
    // `jsrPush`, NOT keepCommit — the trailing ibranch is the macro's real oracle step,
    // exactly like the non-memind JSR crack), ptr-load -> T0, ibranch to T0+od(+post-idx).
    Desc(UMiPushFinal, mem = MStore, srcA = SA7, useImm = true, imm = SRetPc, dst = SA7,
         sz = SzLong, isFirst = true),                                          // µPC198
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true),                                       // µPC199
    Desc(UMiBranchFinal, srcA = ST0, useImm = true, imm = SMiOd,
         sz = SzLong, miHostIndex = true, isLast = true)                        // µPC200
  )
  private def romP7(): Vector[Desc] = Vector(
    // ════════════════════════════════════════════════════════════════════════
    // Bit-field MEMORY-INDIRECT, DYNAMIC offset (Do=1) RMW/INS + the BFINS static-offset
    // (Do=0) fix — task #203. Appended as NEW entries (not merged into the existing
    // MI_BF_RMW_DO0/INS_DO0 @142/152, per the established "pure addition, zero blast
    // radius" convention — task #178's MI_MOVE_DST_IMM_ENTRY, task #197's own MI_BF_*
    // family) rather than renumbering every entry after them.
    //
    // MI_BF_RMW_DO1 / MI_BF_INS_DO1: task #197 characterized DYNAMIC-offset memind RMW/
    // INS as needing "a 4th concurrently-live temp" the 3-temp (T0/T1/T2) engine didn't
    // have, and left it routed to BF_DYN_ILLEGAL_ENTRY. Re-investigated (task #203): the
    // 4th temp (T3, see the engine-temps header comment) turned out to be a small,
    // mechanical, low-risk addition (mirrors the T2-adding precedent) — and WITH T3
    // available, these entries don't even need the DO0 entries' "EA-recompute" trick at
    // all: T3 holds the resolved pointer/Tb (= ptr + runtime byteDelta) LIVE across the
    // ENTIRE funnel compute, exactly like the register-direct BF_DYN_RMW_DO1/INS_DO1
    // chains hold their Tb in T2 — the ONLY difference is that a memind "pointer" is a
    // real LOAD result (needs a temp), not a free architectural-register re-read like
    // `SEaBase`. Also index-capable (task #203): the initial UMiPtrLoad carries
    // `miPtrIndex = true` (PRE-index, mirrors every other MI_* pointer load) and the
    // post-deref load/store rows carry `miHostIndex = true` (POST-index) — the same
    // generic ctx.miPost-gated mechanism as MI_BF_RD_DO1 above (see that entry's updated
    // header comment for the no-index-is-a-no-op regression argument, which applies
    // identically here).
    //
    // MI_BF_RMW_DO1 temp-liveness accounting (T3 = Tb throughout ALL 11 rows; T0/T1/T2
    // handle everything else, max 3 concurrently -> 4 temps total, never more):
    //   q0  LOAD.L ptr = (An+bd(+pre-idx)) -> T3                               (isFirst)
    //   q1  BFRESOLVE byteDelta = Dn[off]>>>3 -> T0
    //   q2  ADD Tb = ptr + byteDelta -> T3                                     (T0 dead)
    //   q3  LOAD.L  [Tb+od(+post-idx)]   -> T0 (lo)
    //   q4  LOAD.B  [Tb+od(+post-idx)+4] -> T1 (hi)
    //   q5  BFRESOLVE {off,wid} -> T2 (packed)
    //   q6  BITFIELD bfMem RES srcA=T0,srcB=T1,srcC=T2 -> T2 (res; +NZ)
    //   q7  BITFIELD bfMem LO5RAW srcA=T0,srcB=T2,srcC=Dn[off] -> T0 (lo')
    //   q8  BITFIELD bfMem HI5RAW srcA=T1,srcB=T2,srcC=Dn[off] -> T1 (hi'; res dead)
    //   q9  STORE.L T0(lo') -> [Tb+od(+post-idx)]
    //   q10 STORE.B T1(hi') -> [Tb+od(+post-idx)+4]                            (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST3, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC201 (q0)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong),                                                                    // µPC202 (q1)
    Desc(UBfAdd, srcA = ST3, srcB = ST0, dst = ST3, sz = SzLong),                          // µPC203 (q2, Tb)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong, miHostIndex = true),                                                // µPC204 (q3)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, miHostIndex = true),                                                // µPC205 (q4)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC206 (q5)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                                // µPC207 (q6)
    Desc(UBfMem, srcA = ST0, srcB = ST2, srcC = SBfOffReg, dst = ST0, sz = SzLong,
         bfStoreForm = 4),                                                                // µPC208 (q7)
    Desc(UBfMem, srcA = ST1, srcB = ST2, srcC = SBfOffReg, dst = ST1, sz = SzLong,
         bfStoreForm = 5),                                                                // µPC209 (q8)
    Desc(UMove, mem = MStore, srcA = ST3, srcB = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong, miHostIndex = true),                                                // µPC210 (q9)
    Desc(UMove, mem = MStore, srcA = ST3, srcB = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, miHostIndex = true, isLast = true),                                 // µPC211 (q10)

    // MI_BF_INS_DO1 — BFINS, DYNAMIC offset (Do=1; width may also be dynamic). Same T3-
    // holds-Tb-throughout shape as MI_BF_RMW_DO1 above, with the prefunnel/register-BFINS/
    // reload+inverse-funnel BFINS shape (mirrors BF_DYN_INS_DO1's register-direct chain,
    // and MI_BF_INS_DO0's own prefunnel/insert stage, unchanged). "ALL loads precede ALL
    // stores" preserved (task #197's BF_DYN_INS family header comment's LS-forwarding
    // deadlock warning) — r3/r4 (initial) and r8/r10 (reload) all precede r12/r13 (store).
    //   r0  LOAD.L ptr = (An+bd(+pre-idx)) -> T3                               (isFirst)
    //   r1  BFRESOLVE byteDelta = Dn[off]>>>3 -> T0
    //   r2  ADD Tb = ptr + byteDelta -> T3                                     (T0 dead)
    //   r3  LOAD.L  [Tb+od(+post-idx)]   -> T0 (lo)
    //   r4  LOAD.B  [Tb+od(+post-idx)+4] -> T1 (hi)
    //   r5  BFRESOLVE {off,wid} -> T2 (packed)
    //   r6  BITFIELD bfMem prefunnel (bfTstForm) srcA=T0,srcB=T1,srcC=T2 -> T0 (field32)
    //   r7  BITFIELD bfReg register-BFINS srcA=T0,srcB=Dn2,srcC=T2 -> T2 (newField32; +N/Z)
    //   r8  LOAD.L  [Tb+od(+post-idx)]   -> T0 (lo RELOAD; Tb(T3) still live, no recompute)
    //   r9  BITFIELD bfMem LO5RAW srcA=T0,srcB=T2,srcC=Dn[off] -> T0 (lo')
    //   r10 LOAD.B  [Tb+od(+post-idx)+4] -> T1 (hi RELOAD)
    //   r11 BITFIELD bfMem HI5RAW srcA=T1,srcB=T2,srcC=Dn[off] -> T1 (hi'; newField32 dead)
    //   r12 STORE.L T0(lo') -> [Tb+od(+post-idx)]
    //   r13 STORE.B T1(hi') -> [Tb+od(+post-idx)+4]                            (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST3, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC212 (r0)
    Desc(UBfResolve, srcA = SBfOffReg, dst = ST0, useImm = true, imm = SBfDeltaImm,
         sz = SzLong),                                                                    // µPC213 (r1)
    Desc(UBfAdd, srcA = ST3, srcB = ST0, dst = ST3, sz = SzLong),                          // µPC214 (r2, Tb)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong, miHostIndex = true),                                                // µPC215 (r3)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, miHostIndex = true),                                                // µPC216 (r4)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC217 (r5)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                                 // µPC218 (r6)
    Desc(UBfReg, srcA = ST0, srcB = SBfDn2, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                                // µPC219 (r7)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong, miHostIndex = true),                                                // µPC220 (r8)
    Desc(UBfMem, srcA = ST0, srcB = ST2, srcC = SBfOffReg, dst = ST0, sz = SzLong,
         bfStoreForm = 4),                                                                // µPC221 (r9)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, miHostIndex = true),                                                // µPC222 (r10)
    Desc(UBfMem, srcA = ST1, srcB = ST2, srcC = SBfOffReg, dst = ST1, sz = SzLong,
         bfStoreForm = 5),                                                                // µPC223 (r11)
    Desc(UMove, mem = MStore, srcA = ST3, srcB = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong, miHostIndex = true),                                                // µPC224 (r12)
    Desc(UMove, mem = MStore, srcA = ST3, srcB = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, miHostIndex = true, isLast = true),                                 // µPC225 (r13)

    // MI_BF_INS_DO0_V2 — BFINS, STATIC offset (Do=0; width may be dynamic), NO-INDEX only
    // (mirrors the original MI_BF_INS_DO0 @152's own scope — this is a same-scope
    // REPLACEMENT, not a feature extension). task #197 left the original MI_BF_INS_DO0
    // wired but non-functional (a genuine hang, root cause not found — leading unconfirmed
    // hypothesis was "the chain re-resolves the pointer 3 times [...] something about the
    // 3rd occurrence may be the break point"). Re-investigated (task #203): rather than
    // continue chasing that hypothesis, this entry sidesteps it entirely by using the SAME
    // T3-holds-ptr-throughout shape as MI_BF_INS_DO1 above (now affordable with T3
    // available) — ONE pointer LOAD total instead of three (the original's l0/l6/l11), so
    // whatever the original's 2nd/3rd-recompute bug actually was, it structurally cannot
    // recur here. (The original MI_BF_INS_DO0 @152 is left in place, unreferenced by any
    // routing after this task — pure addition, zero blast radius, mirrors every other
    // "leave the old entry as dead ROM space" precedent in this file.)
    //   s0 LOAD.L ptr = (An+bd) -> T3                                          (isFirst)
    //   s1 LOAD.L  [ptr+byteAddr]   -> T0 (lo)
    //   s2 LOAD.B  [ptr+byteAddr+4] -> T1 (hi)
    //   s3 BFRESOLVE {off,wid} -> T2 (packed; T3=ptr untouched, unlike the original's l3
    //      which clobbered its OWN ptr temp T2 — unrelated to the hang hypothesis above,
    //      just a structural side-effect of the T0/T1/T2-only budget this replaces)
    //   s4 BITFIELD bfMem prefunnel (bfTstForm) srcA=T0,srcB=T1,srcC=T2 -> T0 (field32)
    //   s5 BITFIELD bfReg register-BFINS srcA=T0,srcB=Dn2,srcC=T2 -> T2 (newField32; +N/Z)
    //   s6 LOAD.L  [ptr+byteAddr]   -> T0 (lo RELOAD; ptr(T3) still live, no recompute)
    //   s7 BITFIELD bfMem LO5 srcA=T0,srcB=T2,imm=static bitOff -> T0 (lo')
    //   s8 LOAD.B  [ptr+byteAddr+4] -> T1 (hi RELOAD)
    //   s9 BITFIELD bfMem HI5 srcA=T1,srcB=T2,imm=static bitOff -> T1 (hi'; newField32 dead)
    //   s10 STORE.L T0(lo') -> [ptr+byteAddr]
    //   s11 STORE.B T1(hi') -> [ptr+byteAddr+4]                                (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST3, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC226 (s0)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC227 (s1)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte),                                                                    // µPC228 (s2)
    Desc(UBfResolve, srcA = SBfOffDyn, srcB = SBfWdDyn, dst = ST2, useImm = true,
         imm = SBfResImm, sz = SzLong),                                                   // µPC229 (s3)
    Desc(UBfMem, srcA = ST0, srcB = ST1, srcC = ST2, dst = ST0, sz = SzLong,
         bfDyn = true, bfTstForm = true),                                                 // µPC230 (s4)
    Desc(UBfReg, srcA = ST0, srcB = SBfDn2, srcC = ST2, dst = ST2, sz = SzLong,
         bfDyn = true, bfWritesNz = true),                                                // µPC231 (s5)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC232 (s6)
    Desc(UBfMem, srcA = ST0, srcB = ST2, dst = ST0, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 2),                                                   // µPC233 (s7)
    Desc(UMove, mem = MLoad, srcA = ST3, dst = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte),                                                                    // µPC234 (s8)
    Desc(UBfMem, srcA = ST1, srcB = ST2, dst = ST1, useImm = true, imm = SBfImm,
         sz = SzLong, bfStoreForm = 3),                                                   // µPC235 (s9)
    Desc(UMove, mem = MStore, srcA = ST3, srcB = ST0, useImm = true, imm = SBfMiDispLo,
         sz = SzLong),                                                                    // µPC236 (s10)
    Desc(UMove, mem = MStore, srcA = ST3, srcB = ST1, useImm = true, imm = SBfMiDispHi,
         sz = SzByte, isLast = true),                                                     // µPC237 (s11)
  )
  private def romP8(): Vector[Desc] = Vector(
    // ════════════════════════════════════════════════════════════════════════
    // MI_MOVE_BOTH_MI @238 (task #204): MOVE mem-indirect src-EA -> mem-indirect dst-EA,
    // BOTH sides full-format. Resumes task #198's abandoned design sketch (reverted
    // incomplete, no ROM rows ever existed for it) — re-derived from scratch against the
    // infrastructure this session has since built out (tasks #144-203). Scoped to a
    // NON-post-indexed dst (see `ucMoveBothMiOk` in DecodeStage.scala): a post-indexed
    // dst's Xn would need to apply at the FINAL store, which this narrow first entry
    // doesn't thread through — that harder case still falls to the pre-existing
    // MI_MOVE_BOTH_MI_ILLEGAL_ENTRY (task #119's original scope limit, unchanged, still
    // live for that shape). The src side is UNRESTRICTED (pre- OR post-indexed, any od) —
    // it reuses the EXISTING primary eaBase/eaDispLo/eaIndex/miOd/miPost machinery
    // completely unchanged (t0/t1 below are byte-for-byte the same shape as MI_MOVE_SRC's
    // p0/p1 @16/17).
    //
    // KEY SIMPLIFICATION (found investigating whether this needed a whole new dual-
    // pointer ctx group, mirroring task #201's LEA/PEA/JMP/JSR finding that a resolved
    // ADDRESS needs LESS infrastructure than a loaded VALUE): the "other" (dst) side's
    // pointer load needs EXACTLY the ctx.miOtherEa{Base,DispLo,Index*} group MI_MOVE_EAEA
    // (task #119) already built for its plain-memory "other" side — that group already
    // carries a full EaDecoder output (base/disp/index), and `indexFromMiOtherEa` already
    // applies that index UNCONDITIONALLY (no ctx.miPost gating), which is EXACTLY the
    // semantics a non-post-indexed dst needs (index-before-load, same as a pre-indexed
    // primary pointer). The only genuinely NEW piece is the dst pointer's own OUTER
    // displacement (od) for the FINAL store — `SMiOtherOd`/`ctx.miOtherOd`, mirroring the
    // existing `SMiOd`/`ctx.miOd` pair but for the second pointer. Everything else (the
    // second UMiPtrLoad, the final UMiHostMove store) is a straight reuse of existing Desc
    // shapes with different selectors. 3 temps (T0=src ptr, T1=loaded value, T2=dst ptr) —
    // already an established budget (the bit-field MI family uses T0-T3; no archDepth bump).
    //   t0 LOAD.L srcPtr = (srcEaBase+bd (+pre-idx))       -> T0        (isFirst)
    //   t1 LOAD.host (T0+srcOd (+src post-idx))             -> T1
    //   t2 LOAD.L dstPtr = (dstEaBase+bd (+idx, uncond.))   -> T2
    //   t3 STORE.host T1 -> (T2+dstOd), sets NZVC per ctx.miWNzvc               (isLast)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SEaBase, dst = ST0, useImm = true, imm = SEaDispLo,
         sz = SzLong, miPtrIndex = true, isFirst = true),                                 // µPC238 (t0)
    Desc(UMiHostMove, mem = MLoad, srcA = ST0, dst = ST1, useImm = true, imm = SMiOd,
         sz = SzHost, miHostIndex = true),                                                // µPC239 (t1)
    Desc(UMiPtrLoad, mem = MLoad, srcA = SMiOtherEaBase, dst = ST2, useImm = true,
         imm = SMiOtherEaDispLo, sz = SzLong, indexFromMiOtherEa = true),                 // µPC240 (t2)
    Desc(UMiHostMove, mem = MStore, srcA = ST2, srcB = ST1, useImm = true, imm = SMiOtherOd,
         sz = SzHost, miMoveFlags = true, isLast = true),                                 // µPC241 (t3)

    // ════════════════════════════════════════════════════════════════════════
    // MOVE16 (Ax)+,(Ay)+ @242 (task #207, ported-tests move16_basic): 68040 INTEGER
    // cache-line-move — ONLY the (Ax)+,(Ay)+ post-increment form (opword 0xF620|Ax, ext
    // word bits[14:12]=Ay; the 3 absolute forms stay on the F-line illegal default).
    // Semantics locked to Musashi (this repo's golden oracle), NOT real 68040 silicon:
    // four PLAIN LONG transfers (Ax)+0/4/8/12 -> (Ay)+0/4/8/12 using the register values
    // as they stood BEFORE either updates (NO 16-byte alignment masking — Musashi doesn't
    // mask), then Ax += 16 and Ay += 16 UNCONDITIONALLY (two independent write-backs, NOT
    // a single "if different registers" branch). No CCR effect at all.
    //
    // Address computation deliberately does NOT use the eaAuto/predec-postinc machinery
    // (APostincAy/APostincAx, the CMPM precedent) — those compute addr=An THEN bump An by
    // a size-dependent delta (with the A7-byte-odd quirk), neither of which MOVE16 wants
    // (its 4 transfer addresses are FIXED +0/+4/+8/+12 offsets off the ORIGINAL An, and its
    // final delta is a flat +16 regardless of size or A7). So the 4 transfers instead reuse
    // the full-format mem-indirect host-op idiom (a plain register base + a useImm/imm
    // literal displacement, e.g. MI_MOVE_EAEA's shape) with the offsets as new SImm4/8/12
    // constant selectors, and the two write-backs are plain UAddDrop rows (mirrors CMPM's
    // e1/e3 dropped-crack ADD) with a NEW flat SImm16 constant (not SDeltaAx/Ay's ctx-size-
    // dependent delta). Ax (source, opword[2:0]) reuses the EXISTING generic SAy selector
    // (which is really just "the register named by opword[2:0]", despite its name); Ay
    // (dest, ext word[14:12]) needs a NEW SMove16Ay selector + ctx field (populated at
    // ucBegin, mirrors ctx.movesRn's ext-word extraction).
    //
    // ORDERING IS LOAD-BEARING for the Ax==Ay case: all 8 loads/stores (using the
    // UNCHANGED architectural Ax/Ay) come FIRST, and the two write-back ADDs come LAST,
    // Ax THEN Ay. If Ax==Ay, the renamer resolves the second ADD's SMove16Ay read against
    // the FIRST ADD's just-written result (ordinary in-order-decode RAW dependency, no
    // special-casing needed) -> the register nets Ax_orig+16+16 = +32, exactly matching
    // the ported test's stage-3 assertion (Musashi: `[Ax] += 16; [Ay] += 16;` sequentially
    // in program order, same net effect when Ax==Ay).
    //   m0 LOAD.L  (Ax+0)  -> T0                                              (isFirst)
    //   m1 LOAD.L  (Ax+4)  -> T1
    //   m2 STORE.L T0 -> (Ay+0)
    //   m3 STORE.L T1 -> (Ay+4)
    //   m4 LOAD.L  (Ax+8)  -> T0
    //   m5 LOAD.L  (Ax+12) -> T1
    //   m6 STORE.L T0 -> (Ay+8)
    //   m7 STORE.L T1 -> (Ay+12)
    //   m8 ADD.L Ax + 16 -> Ax   (dropped crack µop)
    //   m9 ADD.L Ay + 16 -> Ay   (dropped crack µop)                          (isLast)
    Desc(UMove, mem = MLoad, srcA = SAy, dst = ST0, sz = SzLong, isFirst = true),          // µPC242 (m0)
    Desc(UMove, mem = MLoad, srcA = SAy, dst = ST1, useImm = true, imm = SImm4,
         sz = SzLong),                                                                    // µPC243 (m1)
    Desc(UMove, mem = MStore, srcA = SMove16Ay, srcB = ST0, sz = SzLong),                 // µPC244 (m2)
    Desc(UMove, mem = MStore, srcA = SMove16Ay, srcB = ST1, useImm = true, imm = SImm4,
         sz = SzLong),                                                                    // µPC245 (m3)
    Desc(UMove, mem = MLoad, srcA = SAy, dst = ST0, useImm = true, imm = SImm8,
         sz = SzLong),                                                                    // µPC246 (m4)
    Desc(UMove, mem = MLoad, srcA = SAy, dst = ST1, useImm = true, imm = SImm12,
         sz = SzLong),                                                                    // µPC247 (m5)
    Desc(UMove, mem = MStore, srcA = SMove16Ay, srcB = ST0, useImm = true, imm = SImm8,
         sz = SzLong),                                                                    // µPC248 (m6)
    Desc(UMove, mem = MStore, srcA = SMove16Ay, srcB = ST1, useImm = true, imm = SImm12,
         sz = SzLong),                                                                    // µPC249 (m7)
    Desc(UAddDrop, srcA = SAy, dst = SAy, useImm = true, imm = SImm16),                   // µPC250 (m8)
    Desc(UAddDrop, srcA = SMove16Ay, dst = SMove16Ay, useImm = true, imm = SImm16,
         isLast = true),                                                                  // µPC251 (m9)
  )
  val rom: Vector[Desc] = romP1() ++ romP2() ++ romP3() ++ romP4() ++ romP5() ++ romP6() ++ romP7() ++ romP8()
  val BF_DYN_RD_PCREL_DO1_ENTRY  = 178   // rows 178..183
  val BF_DYN_FFO_PCREL_DO1_ENTRY = 184   // rows 184..190
  val MI_BF_RD_DO0_ENTRY  = 130
  val MI_BF_RD_DO1_ENTRY  = 135
  val MI_BF_RMW_DO0_ENTRY = 142
  val MI_BF_INS_DO0_ENTRY = 152   // task #197 original — non-functional (hang), left as dead
                                   // ROM space; superseded for routing by MI_BF_INS_DO0_V2_ENTRY
  val MI_BF_RMW_DO1_ENTRY    = 201  // rows 201..211 (task #203)
  val MI_BF_INS_DO1_ENTRY    = 212  // rows 212..225 (task #203)
  val MI_BF_INS_DO0_V2_ENTRY = 226  // rows 226..237 (task #203)
  val BF_DYN_RD_DO0_ENTRY  = 49
  val BF_DYN_RD_DO1_ENTRY  = 53
  val BF_DYN_FFO_DO1_ENTRY = 59
  val BF_DYN_RMW_DO0_ENTRY = 66
  val BF_DYN_RMW_DO1_ENTRY = 74
  val BF_DYN_ILLEGAL_ENTRY = 86
  val BF_DYN_INS_DO0_ENTRY = 87
  val BF_DYN_INS_DO1_ENTRY = 98
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
  val MOVES_READ_ENTRY  = 46   // rows 46..48 (load -> T0, An update, MOVE T0->Rn sign-ext/merge — A2: Rn write LAST so an aliased Rn==An wins)
  val CMPM_ENTRY        = 115  // rows 115..119 (load Ay/postinc, load Ax/postinc, CMP flags-only)
  val MI_MOVE_EAEA_ENTRY = 120 // rows 120..122 (ptr-load, host-load->T1, host-store T1->plain EA)
  val MI_MOVE_EAEA_REV_ENTRY = 123 // rows 123..125 (ptr-load, host-load plain EA->T1, host-store T1->(ptr+od))
  val MI_MOVE_BOTH_MI_ILLEGAL_ENTRY = 126 // row 126 (both src+dst mem-indirect, post-indexed dst — scoped out, traps illegal)
  val MI_MOVE_BOTH_MI_ENTRY = 238 // rows 238..241 (task #204: both src+dst mem-indirect, dst NOT post-indexed)
  val MOVE16_ENTRY = 242 // rows 242..251 (task #207: MOVE16 (Ax)+,(Ay)+, 4 LONG transfers + Ax/Ay += 16)
  val MI_MOVE_DST_IMM_ENTRY = 127 // rows 127..129 (ptr-load, materialize #imm->T1, host-store T1->mem)
  val PACK_MEM_ENTRY = 166 // rows 166..171 (load Ay/predec x2, PACK compute, store Ax/predec)
  val UNPK_MEM_ENTRY = 172 // rows 172..177 (load Ay/predec, UNPK compute, store lo, shift, store hi)
  val MI_LEA_ENTRY = 191 // rows 191,192 (ptr-load, T0+od(+post-idx) -> An)
  val MI_PEA_ENTRY = 193 // rows 193..195 (ptr-load, T0+od(+post-idx) -> T1, push T1 -> -(A7))
  val MI_JMP_ENTRY = 196 // rows 196,197 (ptr-load, ibranch T0+od(+post-idx))
  val MI_JSR_ENTRY = 198 // rows 198..200 (push retPC -> -(A7), ptr-load, ibranch T0+od(+post-idx))
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
    // Task #199 (bf_pcrel_read): the PC-relative reference point for a DYNAMIC-OFFSET
    // (Do=1) bit-field read = pc+4 (the address of the EA's own extension word — 2
    // leading words: opword + bf-ext word). Used ONLY by the BF_DYN_RD_PCREL_DO1/
    // BF_DYN_FFO_PCREL_DO1 entries' byteBase-recompute row (in place of reading SEaBase
    // as a register, which a PC-rel EA has none of); `eaDispLo` still supplies the raw
    // EA displacement on top, exactly like the register-base DO1 chain. Harmless
    // (unread) for every other microcode customer.
    val bfPcRelConst = Bits(32 bits)
    // ── v2 bit-field static params ────────────────────────────────────────────────────
    val bfOp         = Bits(3 bits)      // op[10:8] = 2 BFCHG / 4 BFCLR / 6 BFSET / 7 BFINS
    val bfDn2        = UInt(5 bits)      // ext[14:12] (BFINS insert source register)
    val bfImm        = Bits(32 bits)     // the packed bfMem imm (identical layout to 3a bfmImm)
    val bfNeedHi     = Bool()            // (bitOff+width)>32 — picks the entry in DecodeStage
    // ── v2 bit-field DYNAMIC params (slice 3c) ──────────────────────────────────────────
    val bfOffDn      = UInt(5 bits)      // Dn[off] = ext[8:6]  (offset register, 0..7)
    val bfWdDn       = UInt(5 bits)      // Dn[wd]  = ext[2:0]  (width  register, 0..7)
    val bfDo         = Bool()            // ext[11] (offset is dynamic)
    val bfDw         = Bool()            // ext[5]  (width  is dynamic)
    val bfResImm     = Bits(32 bits)     // BFRESOLVE imm: [4:0]=staticOff,[9:5]=staticWidth,[10]=Do,[11]=Dw,[12]=memMode(1)
    // ── Bit-field MEMORY-INDIRECT post-dereference byte address (task #197) ─────────────
    // = ctx.miOd + byteOff (0 for Do=1). Only meaningful for the MI_BF_* entries; harmless
    // (unread) for every other bit-field/microcode customer.
    val bfMiDispLo   = Bits(32 bits)
    val bfMiDispHi   = Bits(32 bits)
    // ── full-format MEMORY-INDIRECT host-op group (the §5 host-op-to-temp crack) ──────
    // The pointer-load address = eaBase + eaDispLo(=bd) + (pre: eaIndex). Post-index keeps
    // the index for the HOST access. After [LOAD.L ptr -> T0], the host op re-runs as a
    // (T0 + od (+post-index))-base access. These fields carry the host op identity.
    val miOd         = Bits(32 bits)     // outer displacement (added to the loaded pointer)
    val miPost       = Bool()            // True = post-index ([bd,An],Xn,od); index on the host access
    val miOp         = DecOp()           // the host op (MOVE/ADD/SUB/AND/OR/CMP/CLR/NEG/NOT/TST/...)
    val miBitOp      = Bits(2 bits)      // BITOP sub-kind (op[7:6]): 00 BTST/01 BCHG/10 BCLR/11 BSET
                                         // (only meaningful when miOp===DecOp.BITOP; task #152)
    val miHostSize   = Size()            // the host op access size (.B/.W/.L)
    val miIsDstEa    = Bool()            // the EA is the host op's DESTINATION (store/RMW) vs SOURCE (load)
    val miIsRmw      = Bool()            // dst-EA op that READS then WRITES the EA (imm op / CLR-family on mem)
    val miOther      = UInt(5 bits)      // the host op's OTHER operand register (Dn/Dm); valid per miOtherValid
    val miOtherValid = Bool()
    val miOtherIsImm = Bool()            // the other operand is an immediate (line-0 imm op)
    val miHostImm    = Bits(32 bits)     // the host op immediate (when miOtherIsImm)
    val miOtherIsDst = Bool()            // MOVE src-EA: the loaded value goes straight to miOther (Dn dst)
    val miMovea      = Bool()            // MOVE src-EA whose dst is an ADDRESS reg (MOVEA): the host
                                         // MOVE µop gets isMovea (full-32 An write, .W sign-extend) + NO CCR
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
    // ── mem-indirect EA<->EA group (task #119 follow-up) ────────────────────────────────
    // When the mem-indirect side is combined with a PLAIN (non-register) memory EA on the
    // OTHER side, that other side needs its OWN independent base/disp/index — the shared
    // eaBase/eaDispLo/eaIndex* group above is already spoken for by the pointer-load.
    // Populated only for the MI_MOVE_EAEA entry; DecodeStage decodes the non-selected side
    // (the one that is NOT MEMINDIRECT) with EaDecoder and threads it here.
    val miOtherEaBase       = UInt(5 bits)
    val miOtherEaBaseValid  = Bool()
    val miOtherEaIndexReg   = UInt(5 bits)
    val miOtherEaIndexValid = Bool()
    val miOtherEaIndexLong  = Bool()
    val miOtherEaIndexScale = UInt(2 bits)
    val miOtherEaDispLo     = Bits(32 bits)
    // task #204 (both-sides-memory-indirect MOVE): populated ONLY when the "other" side is
    // ITSELF a full-format mem-indirect EA (ucMoveBothMiOk) — its own outer displacement,
    // added to the SECOND pointer (loaded via miOtherEaBase/DispLo/Index above) by the
    // MI_MOVE_BOTH_MI_ENTRY final store row. Harmless (unread) for every other customer.
    val miOtherOd           = Bits(32 bits)
    // (An)+/-(An) auto-update on the "other" (plain) side of an EA<->EA mem-indirect MOVE
    // (task #154, ported-tests memind cluster): MI_MOVE_EAEA's store (other=dst) / _REV's
    // load (other=src) need the SAME auto-postinc/predec + An write-back the ordinary
    // MOVE crack already gets via crackLoad/crackStore -- this was entirely missing (the
    // "other" side's EaDecoder output already computes autoMode/autoDelta correctly; it
    // was just never threaded into Ctx). NONE for every other mem-indirect customer.
    val miOtherEaAutoMode  = EaAuto()
    val miOtherEaAutoDelta = UInt(3 bits)
    // ── PACK/UNPK MEMORY-form group (task #198) ─────────────────────────────────────────
    // adj16 (the ext word right after the opword), sign-extended — mirrors the register
    // form's own imm routing (MicroOpAssembler's packUnpkReg block) exactly. Ay/Ax reuse
    // the shared SAy/SAx selectors (ctx.opword bits 2:0 / 11:9), same as the BCD chain.
    val packAdj = Bits(32 bits)
    // ── MOVE16 group (task #207) ────────────────────────────────────────────────────────
    // The dst An = ext word[14:12] (8 + that field, matching the SAy/SAx An-numbering
    // convention). Populated at ucBegin from ucEntryPkt.words(1), mirrors ctx.movesRn's
    // own ext-word extraction. Harmless (unread) for every other microcode customer.
    val move16Ay = UInt(5 bits)
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
    case ST3     => (U(T3, 5 bits), True)
    case SEaBase => (ctx.eaBase, ctx.eaBaseValid)   // abs modes -> baseValid False (disp-only)
    case SMiOtherEaBase => (ctx.miOtherEaBase, ctx.miOtherEaBaseValid)  // task #119 EA<->EA plain side
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
    case SBfOffReg => (ctx.bfOffDn, True)
    case SBfOffDyn => (ctx.bfOffDn, ctx.bfDo)
    case SBfWdDyn  => (ctx.bfWdDn,  ctx.bfDw)
    case SBfDn2    => (ctx.bfDn2,   True)
    // read-only result reg = Dn2 (ext[14:12]); written by BFEXTU(1)/BFEXTS(3)/BFFFO(5), NOT BFTST(0).
    case SBfRdDst  => (ctx.bfDn2, (ctx.bfOp === 1) || (ctx.bfOp === 3) || (ctx.bfOp === 5))
    // The (An)+/-(An) write-back target: the base An, valid ONLY when an auto mode is set
    // (a non-auto / abs EA -> the r2 ADD writes no reg, an inert NOP).
    case SMovesAn => (ctx.eaBase, ctx.casAutoMode =/= EaAuto.NONE)
    case SA7      => (U(15, 5 bits), True)   // task #201: the constant A7 stack pointer
    case SMove16Ay => (ctx.move16Ay, True)   // task #207: MOVE16 dst An (ext word[14:12])
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
    case SMiOtherEaDispLo => ctx.miOtherEaDispLo   // task #119 EA<->EA plain side
    case SMiOtherOd  => ctx.miOtherOd              // task #204 both-MI: the dst pointer's own od
    case SBfImm      => ctx.bfImm
    case SMiOd       => ctx.miOd
    case SMiImm      => ctx.miHostImm
    case SCas2Da1    => ctx.cas2Da1.asBits.resize(32)   // bit0 = ext1[15] (BIT_1F)
    case SCas2Da2    => ctx.cas2Da2.asBits.resize(32)   // bit0 = ext2[15] (BIT_F)
    case SMovesDelta => ctx.movesDelta                  // signed An write-back delta
    case SBfResImm   => ctx.bfResImm                    // BFRESOLVE imm (slice 3c)
    case SBfDeltaImm => B(1, 32 bits) |<< 13            // imm[13]=1 -> BFRESOLVE byte-delta mode
    case SBfPcRelConst => ctx.bfPcRelConst              // task #199: pc+4 (PC-rel DO1 byteBase)
    case SBfMiDispLo => ctx.bfMiDispLo                  // task #197: mem-indirect post-deref byteAddr disp
    case SBfMiDispHi => ctx.bfMiDispHi
    case SPackAdj    => ctx.packAdj                     // task #198: PACK/UNPK mem-form adj16
    case SShift8     => U(8, 32 bits).asBits             // task #198: UNPK's high-byte LSR count
    case SRetPc      => ctx.nextPc.asBits                // task #201: JSR-memind's pushed return PC
    case SImm4       => U(4, 32 bits).asBits              // task #207: MOVE16 transfer offset
    case SImm8       => U(8, 32 bits).asBits              // task #207: MOVE16 transfer offset
    case SImm12      => U(12, 32 bits).asBits             // task #207: MOVE16 transfer offset
    case SImm16      => U(16, 32 bits).asBits             // task #207: MOVE16 An += 16 write-back
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
                  else if (d.indexFromMiOtherEa) ctx.miOtherEaIndexReg
                  else if (d.miPtrIndex || d.miHostIndex) ctx.eaIndexReg
                  else srcCRegSel
    val srcCV   = if (d.indexFromEa) ctx.eaIndexValid
                  else if (d.indexFromMiOtherEa) ctx.miOtherEaIndexValid
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
      case UBfReg      => u.op := DecOp.BITFIELD
      case UBfResolve  => u.op := DecOp.BFRESOLVE
      case UBfShiftOff => u.op := DecOp.SHIFT
      case UShiftR8    => u.op := DecOp.SHIFT   // task #198: UNPK's high-byte LSR #8
      case UBfAdd      => u.op := DecOp.ADD
      case UMiPtrLoad  => u.op := DecOp.MOVE
      case UMiHostMove => u.op := DecOp.MOVE
      case UMiHostOp   => u.op := ctx.miOp
      case UMiLeaFinal    => u.op := DecOp.MOVE     // address-generate (leaAddr short-circuits memOp)
      case UMiPushFinal   => u.op := DecOp.MOVE     // stack-push store (mirrors pushUop/peaPush)
      case UMiBranchFinal => u.op := DecOp.BRANCH   // indirect branch (mirrors ibrUop)
      case UMovesRead  => u.op := DecOp.MOVE   // MOVE T0 -> Rn (sign-ext An / merge Dn in EU)
      case _: UCasOp   => u.op := DecOp.CASOP
    }
    u.cluster := (d.uop match {
      case UBfMem    => Cluster.INT          // the bit-field compute runs on the ALU/slow pipe
      case UBfReg    => Cluster.INT          // register-form bit-field (BFINS prefunnel consumer)
      case UBfResolve => Cluster.INT
      case UBfShiftOff => Cluster.INT
      case UBfAdd    => Cluster.INT
      // The §7 host ALU/unary ops (MOVE/ADD/SUB/AND/OR/EOR/CMP/CLR/NEG/NEGX/NOT/TST) all run
      // on the INT (ALU) pipe; CPLX/CHK/DIV are not full-format mem-indirect hosts in scope.
      case UMiHostOp => Cluster.INT
      // UMiLeaFinal needs the LS-EU's AGU (leaAddr short-circuit, base+imm+scaled-index) —
      // an explicit override since d.mem=MNone would otherwise default to Cluster.INT
      // (mirrors the fast-path leaGenUop's `u.cluster := Cluster.LS`). UMiPushFinal
      // (d.mem=MStore) and UMiBranchFinal (d.mem=MNone -> Cluster.INT, mirrors ibrUop's
      // own `Cluster.INT`) both already resolve correctly via the `_` default below.
      case UMiLeaFinal => Cluster.LS
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
        case SzWord => u.size := Size.WORD
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
    } else if (d.auto == AEaMiOtherStore) {
      // Mirrors AEaCasStore, keyed to the "other" (plain) side's OWN base register
      // (task #154) instead of the mem-indirect pointer's ctx.eaBase.
      u.dstReg   := ctx.miOtherEaBase
      u.dstValid := ctx.miOtherEaAutoMode =/= EaAuto.NONE
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
      // CMP writes NO result register (flags only) — the MI_ALU_SRC row's dst slot
      // (SMiOther, shared with the writing ALU ops) must not land for a CMP host
      // (FUZZER-CAUGHT: `cmp.<sz> ([...]),Dn` clobbered Dn with the compare result).
      when(ctx.miOp === DecOp.CMP) { u.dstValid := False }
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
    // task #201: UMiBranchFinal is an indirect branch (mirrors ibrUop); UMiPushFinal is a
    // stack-push store (mirrors pushUop/peaPush). Every other µcode customer is neither.
    u.isBranch := Bool(d.uop == UMiBranchFinal); u.ibranch := Bool(d.uop == UMiBranchFinal)
    u.stkPush  := Bool(d.uop == UMiPushFinal);   u.anInc   := 0
    u.cond := 0; u.branchDisp := 0
    // bfIllegal: deliver a vector-4 ILLEGAL (the out-of-scope Do=1-at-abs-EA dynamic
    // RMW/INS forms route here — trap, NOT silent-wrong).
    u.unimplemented := Bool(d.bfIllegal)
    u.faulted := Bool(d.bfIllegal); u.faultVector := (if (d.bfIllegal) U(4, 8 bits) else U(0, 8 bits)); u.faultUsesNextPc := False
    u.faultAddr := ctx.pc; u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned := False; u.div64 := False
    // The two An write-back ADDs are DROPPED crack µops (divIsRem): the commit
    // observation is dropped, but the An write lands in the PRF + is verified by a later
    // reader (the program reads Ay/Ax into a Dn after the op). Mirrors anUpdUop / LINK.
    // CAS2's CAS2C1 (the res1 compare) + the two CAS2DC Dc-update µops are likewise DROPPED:
    // their NZVC/Dc1/Dc2 writes still land + fold (the lock-step reads Dc1/Dc2 back), but
    // CAS2 maps to exactly ONE oracle step (the kept CAS2C2, which carries the final NZVC).
    u.divIsRem := Bool(d.uop == UAddDrop || d.bfDrop || (d.uop.isInstanceOf[UCasOp] && d.uop.asInstanceOf[UCasOp].dropCommit))
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
      // Mem-indirect EA<->EA "other" side (task #154): same LOAD/STORE split as CAS above,
      // keyed to ctx.miOtherEaAutoMode/Delta instead of ctx.casAutoMode/Delta.
      case AEaMiOtherLoad  => u.eaAuto := ctx.miOtherEaAutoMode; u.eaDelta := ctx.miOtherEaAutoDelta
      case AEaMiOtherStore => u.eaAuto := ctx.miOtherEaAutoMode; u.eaDelta := ctx.miOtherEaAutoDelta
    }
    u.ccrRestore := False; u.toCcr := False
    // shiftOp: 0 for every row EXCEPT UShiftR8 (task #198, UNPK's high-byte LSR #8),
    // which needs the real barrel shifter's LSL/LSR family (tt=01) selected — see
    // AluEuPlugin's shiftCmd wiring (u1.shiftOp/u1.shiftDir), the SAME mechanism the
    // standalone movepShiftUop helper already uses for an analogous byte-extract shift.
    u.shiftOp := (if (d.uop == UShiftR8) B"01" else B"00"); u.shiftDir := False
    u.bcdSub := ctx.bcdSub
    // Bit-field RMW compute (UBfMem): op=BITFIELD + bfMem (so the ALU EU funnel datapath
    // runs) + bfOp (CHG/CLR/SET/INS) from the latched ctx.op + the store-form selector. The
    // funnel reads bitOff/needHi/width/origOff from the packed bfImm (= u.imm, set above).
    // isMovea: a MOVES READ writeback to an An (ctx.movesRnIsA) sign-extends the loaded
    // value to 32 (the moveaResult path, extended to .B for MOVES). A Dn read leaves it
    // False -> the generic size-merge preserves the upper bits. A mem-indirect MOVEA
    // host op (ctx.miMovea, MI_MOVE_SRC with an An dst) likewise takes the moveaResult
    // path (.W sign-extend, full-32 An write). Default False for all others.
    // BITOP sub-kind: forced 0 (BTST, no-op result) for every OTHER microcode customer
    // (bitOp is otherwise don't-care for them), but a real mem-indirect BCHG/BCLR/BSET
    // MUST carry its actual tt through, else AluDatapath's bitRes mux always takes the
    // BTST arm ("no change") regardless of the real op -- silently turning every
    // mem-indirect BCHG/BCLR/BSET into a no-op store (task #152).
    u.bitOp := Mux(ctx.miOp === DecOp.BITOP, ctx.miBitOp, B(0, 2 bits))
    u.bfDynamic := Bool(d.bfDyn)
    // extByte is repurposed here (task #198) as the PACK MEMORY-FORM marker: True only for
    // this ROM's UOpFromCtx/PACK compute row, telling AluEuPlugin's isPack cone to combine
    // srcA/srcB (the two predec-loaded bytes) instead of reading a single 16-bit Dy via the
    // raw rdB port. Safe reuse: the register-form PACK (a single-µop MicroOpAssembler fast
    // crack, NEVER routed through this ROM) is the field's only other consumer-context, and
    // it always leaves extByte at its ordinary False default. Every other UOpFromCtx customer
    // (BCD/ADDX/SUBX/CMPM's CMP/UNPK) ignores extByte entirely (EXT/EXTB's own real meaning
    // for `extByte` is likewise a different op entirely, never reached via this ROM).
    u.extByte := Bool(d.uop == UOpFromCtx) && (ctx.op === DecOp.PACK)
    u.isMovea := (d.uop match {
      case UMovesRead => ctx.movesRnIsA
      case UMiHostOp  => ctx.miMovea
      case _          => False
    })
    // A2 fix: the MOVES write µop (the sole SMovesRn-as-srcB MStore row, µPC45) reads
    // Rn AND folds the (An)+/-(An) auto write-back into the same atomic store. Compare
    // the two STATIC (decode-time, opcode-field-derived) register numbers — movesRn
    // (REG_DA 0..15) vs ctx.eaBase (REG_DA 0..15, An = 8+reg) — both already resolved
    // by the time resolve() runs; no runtime register VALUE is involved. See DecodedUop
    // for the full rationale.
    u.movesAliasStore := Bool(d.srcB == SMovesRn && d.mem == MStore) &&
      ctx.movesRnIsA && (ctx.movesRn === ctx.eaBase) && (ctx.casAutoMode =/= EaAuto.NONE)
    d.uop match {
      case UBfMem =>
        u.bfMem       := True
        // bfOp = ctx.bfOp (CHG=2/CLR=4/SET=6/INS=7); a prefunnel row forces bfOp=0 (BFTST)
        // whose bfMem result IS field32 = (lo<<bitOff)|(needHi?hi>>(8-bitOff):0).
        u.bfOp        := (if (d.bfTstForm) B(0, 3 bits) else ctx.bfOp)
        u.bfStoreForm := U(d.bfStoreForm, 3 bits)
      case UBfReg =>
        u.bfMem       := False           // register form: dy = srcA = field32; offset = packed[4:0] = 0
        u.bfOp        := ctx.bfOp        // BFINS = 7 (the only UBfReg customer, slice 3c)
        u.bfStoreForm := 0
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
    } else if (d.indexFromMiOtherEa) {
      u.indexLong := ctx.miOtherEaIndexLong; u.indexScale := ctx.miOtherEaIndexScale
    } else {
      u.indexLong := False; u.indexScale := 0
    }
    // needsSupervisor: set on the FIRST µop of a MOVES (ctx.needsSup) — the ROB delivers a
    // vector-8 privilege violation if the head retires with committed S==0 (the op does NOT
    // execute). keepCommit: a ROM row may force-keep its commit (the MOVES write store).
    // task #201: UMiLeaFinal is an address-generate (leaAddr short-circuit on the LS EU —
    // mirrors the fast-path leaGenUop). Every other µcode customer is not.
    u.leaAddr := Bool(d.uop == UMiLeaFinal); u.fromCcr := False; u.fromSr := False
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
