package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** LUT-reduction Task A4 — the MANDATORY equivalence gate protecting Task A5's cutover.
  *
  * Slice A replaces the ~250-way combinationally-resolved microcode structure with a real
  * synchronous-read BRAM: instead of calling the COMPILE-TIME `Microcode.resolve(Desc, ctx,
  * valid)` once per ROM row (each call dead-code-eliminated down to that one row's
  * specialization, then all 250 results muxed), Task A5 will read ONE `DescBits` row out of
  * `DecodeStage.logic.ucRomMem` and hand it to the RUNTIME-hardware `Microcode
  * .resolveFromBits(DescBits, ctx, valid)` (Task A3).
  *
  * `resolveFromBits` is a pure type-level transcription of `resolve` (Scala `match` ->
  * `switch`, `if/else` -> `when/elsewhen`, value-`if` -> `Mux`, case-object `==` -> enum
  * `===`). Nothing enforces that transcription's fidelity except this test, so this test IS
  * the gate: for EVERY row of `Microcode.rom`, both functions are elaborated against a SHARED
  * `Ctx` input port and their `DecodedUop.asBits` compared, over a directed + randomized
  * sweep of `Ctx`.
  *
  * Ctx fields `resolve()` actually branches on (all covered below, directed where the field
  * is small/enumerable and randomized otherwise):
  *   - `op`      (DecOp)  : UOpFromCtx's `u.op`, and `extByte := (op === PACK)`
  *   - `miOp`    (DecOp)  : UMiHostOp's `u.op`, `dstValid := False` when `=== CMP`,
  *                          `bitOp := Mux(miOp === BITOP, miBitOp, 0)`
  *   - `miPost`  (Bool)   : selects PRE- vs POST-index for the mem-indirect srcC/valid
  *   - `bfOp`    (3 bits) : SBfRdDst's validity (`=== 1/3/5`) and `u.bfOp`
  *   - `bfDo`/`bfDw`      : SBfOffDyn / SBfWdDyn validity
  *   - `casAutoMode`      (EaAuto): AEaCas{Load,Store} `u.eaAuto`, SMovesAn / AEaCasStore
  *                          `dstValid`, and one of `movesAliasStore`'s four conjuncts
  *   - `miOtherEaAutoMode`(EaAuto): AEaMiOther{Load,Store} `u.eaAuto` + `dstValid`
  *   - `size` (Size) + `sizeBytesLog` + `opword` : the A7-byte delta rule
  *                          (`deltaBytesU`: `(An === A7 && size === BYTE) ? 2 : 1<<log`),
  *                          `opword` also feeds ayReg/axReg (SAy/SAx and the delta imms)
  *   - `miHostSize` (Size): SzHost
  *   - `movesRnIsA` + `movesRn` vs `eaBase` + `casAutoMode` : `movesAliasStore`, plus
  *                          `isMovea` for UMovesRead
  *   - `miMovea`          : `isMovea` for UMiHostOp
  *   - `miOtherIsImm`     : UMiHostOp's `useImm` / `srcBValid`
  *   - `miRNzvc`/`miRX`/`miWNzvc`/`miWX` : UMiHostOp + UMiHostMove flag writes
  *   - `needsSup`         : `needsSupervisor := needsSup && isFirst`
  *   - `eaBaseValid`/`eaIndexValid`/`eaIndexLong`/`eaIndexScale`/`eaIndexReg`/`eaBase`,
  *     `miOtherEa*`, `bfDn2`/`bfOffDn`/`bfWdDn`, `casDc`/`casDu`/`cas2*`, `movesRn`,
  *     `move16Ay`, `miOther`/`miOtherValid` : selector register ids + valids
  *   - every imm source (`eaDispLo`/`eaDispHi`/`bfImm`/`bfResImm`/`bfPcRelConst`/
  *     `bfMiDisp{Lo,Hi}`/`miOd`/`miHostImm`/`miOtherEaDispLo`/`miOtherOd`/`movesDelta`/
  *     `packAdj`/`cas2Da1`/`cas2Da2`/`nextPc`), plus `pc` and `bcdSub`
  * The randomized sweep drives EVERY `Ctx` field (via a full-bundle `randomize()`), so any
  * field added to `Ctx` later is automatically covered for breadth even if the directed list
  * above is not extended.
  *
  * NOTE (expected, NOT a discrepancy): `resolveFromBits` reads a slightly wider UNION of
  * `Ctx` fields than any single row's `resolve()` specialization does — previously-dead
  * fanout becomes live once the row is a runtime value. That changes no OUTPUT, which is
  * exactly what this test pins down.
  */
class MicrocodeResolveEquivalenceSpec extends AnyFunSuite {

  /** Both resolvers, all `romSize` rows, one shared `Ctx` input port.
    *   mismatch(i) = (resolve(rom(i), ctx) =/= resolveFromBits(descToBits(rom(i)), ctx))
    * `rowSel`/`dbgA`/`dbgB` expose one row's two encodings for failure diagnosis. */
  class Dut extends Component {
    val ctx      = in(Microcode.Ctx())
    val rowSel   = in UInt (log2Up(Microcode.romSize) bits)
    val mismatch = out Bits (Microcode.romSize bits)

    val refUop  = DecodedUop()
    val uopBits = refUop.getBitsWidth
    val dbgA = out Bits (uopBits bits)
    val dbgB = out Bits (uopBits bits)

    /** (fieldName, lsb, width) for `DecodedUop.asBits` — `MultiData.asBits` folds
      * `ret = e.asBits ## ret` over `elements` in declaration order, so the FIRST
      * flattened element occupies the LOW bits. Used only to name mismatching bits. */
    val fieldMap: Seq[(String, Int, Int)] = {
      var lsb = 0
      refUop.flatten.map { e =>
        val w = e.getBitsWidth
        val r = (e.getName().stripPrefix("refUop_"), lsb, w)
        lsb += w
        r
      }.toSeq
    }
    refUop.assignDontCare()   // reference instance only; never drives anything

    val aBits = Vec((0 until Microcode.romSize).map(i =>
      Microcode.resolve(Microcode.rom(i), ctx, True).asBits))
    val bBits = Vec((0 until Microcode.romSize).map(i =>
      Microcode.resolveFromBits(Microcode.descToBits(Microcode.rom(i)), ctx, True).asBits))

    for (i <- 0 until Microcode.romSize) mismatch(i) := aBits(i) =/= bBits(i)
    dbgA := aBits(rowSel)
    dbgB := bBits(rowSel)
  }

  test("resolve() and resolveFromBits() agree on every ROM row over a directed+random Ctx sweep",
       VerilatorTest) {
    val romSize     = Microcode.romSize
    var comparisons = 0L
    var ctxVectors  = 0
    var badVectors  = 0
    val failures    = ArrayBuffer[String]()

    SimConfig.withVerilator.compile(new Dut).doSim(seed = 0x4a4a4a) { dut =>
      val ctx = dut.ctx
      val rng = new Random(0xa4)

      // ── generic Ctx helpers ────────────────────────────────────────────────────
      def pokeEnumRandom[T <: SpinalEnum](e: SpinalEnumCraft[T]): Unit = {
        val els = e.spinalEnum.elements
        e #= els(rng.nextInt(els.length)).asInstanceOf[SpinalEnumElement[T]]
      }
      /** Randomize EVERY Ctx field (breadth); directed pokes then override a subset. */
      def randCtx(): Unit = ctx.flatten.foreach {
        case b: Bool               => b #= rng.nextBoolean()
        case e: SpinalEnumCraft[_] => pokeEnumRandom(e.asInstanceOf[SpinalEnumCraft[SpinalEnum]])
        case bv: BitVector         => bv #= BigInt(bv.getWidth, rng)
        case other                 => fail(s"unhandled Ctx element type: $other")
      }
      /** Drive every Ctx field to its extreme (all-zeros / all-ones + first/last enum). */
      def constCtx(hi: Boolean): Unit = ctx.flatten.foreach {
        case b: Bool => b #= hi
        case e: SpinalEnumCraft[_] =>
          val ec  = e.asInstanceOf[SpinalEnumCraft[SpinalEnum]]
          val els = ec.spinalEnum.elements
          ec #= (if (hi) els.last else els.head).asInstanceOf[SpinalEnumElement[SpinalEnum]]
        case bv: BitVector => bv #= (if (hi) (BigInt(1) << bv.getWidth) - 1 else BigInt(0))
        case other         => fail(s"unhandled Ctx element type: $other")
      }
      def dumpCtx(): String = ctx.flatten.map {
        case b: Bool               => s"${b.getName()}=${b.toBoolean}"
        case e: SpinalEnumCraft[_] => s"${e.getName()}=${e.asInstanceOf[SpinalEnumCraft[SpinalEnum]].toEnum}"
        case bv: BitVector         => s"${bv.getName()}=0x${bv.toBigInt.toString(16)}"
        case other                 => other.toString
      }.mkString(", ")

      /** Evaluate the shared comb cone and record every mismatching row. */
      def check(label: String): Unit = {
        sleep(1)
        ctxVectors  += 1
        comparisons += romSize
        val mm = dut.mismatch.toBigInt
        if (mm != 0) badVectors += 1
        if (mm != 0 && failures.length < 8) {
          val ctxStr = dumpCtx()
          for (i <- 0 until romSize if mm.testBit(i) && failures.length < 8) {
            dut.rowSel #= i
            sleep(1)
            val a = dut.dbgA.toBigInt
            val b = dut.dbgB.toBigInt
            val x = a ^ b
            val bad = dut.fieldMap.filter { case (_, lsb, w) =>
              ((x >> lsb) & ((BigInt(1) << w) - 1)) != 0
            }.map { case (n, lsb, w) =>
              val m = (BigInt(1) << w) - 1
              s"$n(resolve=0x${((a >> lsb) & m).toString(16)} " +
                s"resolveFromBits=0x${((b >> lsb) & m).toString(16)})"
            }
            failures += s"[$label] ROM row $i: ${Microcode.rom(i)}\n" +
              s"    differing DecodedUop fields: ${bad.mkString(", ")}\n" +
              s"    ctx: $ctxStr"
            dut.rowSel #= 0
          }
        }
      }

      dut.rowSel #= 0

      // ── 1. directed: every DecOp on ctx.op (UOpFromCtx / PACK extByte) ─────────
      for (e <- DecOp.elements; rep <- 0 until 3) {
        randCtx(); ctx.op #= e; check(s"op=$e#$rep")
      }
      // ── 2. directed: every DecOp on ctx.miOp (host op / CMP dstValid / BITOP) ──
      for (e <- DecOp.elements; rep <- 0 until 3) {
        randCtx(); ctx.miOp #= e; check(s"miOp=$e#$rep")
      }
      // ── 3. directed: the two CMP/BITOP corners crossed with their companions ───
      for (imm <- Seq(false, true); ov <- Seq(false, true); rep <- 0 until 2) {
        randCtx(); ctx.miOp #= DecOp.CMP; ctx.miOtherIsImm #= imm; ctx.miOtherValid #= ov
        check(s"miOp=CMP imm=$imm otherValid=$ov#$rep")
      }
      for (tt <- 0 until 4; rep <- 0 until 2) {
        randCtx(); ctx.miOp #= DecOp.BITOP; ctx.miBitOp #= tt
        check(s"miOp=BITOP tt=$tt#$rep")
      }
      // ── 4. directed: every EaAuto on both auto-mode sources ────────────────────
      for (e <- EaAuto.elements; d <- 0 until 8) {
        randCtx(); ctx.casAutoMode #= e; ctx.casAutoDelta #= d % 8
        check(s"casAutoMode=$e delta=$d")
      }
      for (e <- EaAuto.elements; d <- 0 until 8) {
        randCtx(); ctx.miOtherEaAutoMode #= e; ctx.miOtherEaAutoDelta #= d % 8
        check(s"miOtherEaAutoMode=$e delta=$d")
      }
      // ── 5. directed: every bfOp (0..7) x the dynamic offset/width valid bits ───
      for (b <- 0 until 8; dyn <- 0 until 4) {
        randCtx(); ctx.bfOp #= b; ctx.bfDo #= (dyn & 1) != 0; ctx.bfDw #= (dyn & 2) != 0
        check(s"bfOp=$b do=${(dyn & 1) != 0} dw=${(dyn & 2) != 0}")
      }
      // ── 6. directed: both miPost values (PRE- vs POST-index srcC selection) ────
      for (p <- Seq(false, true); pi <- Seq(false, true); rep <- 0 until 2) {
        randCtx(); ctx.miPost #= p; ctx.eaIndexValid #= pi
        check(s"miPost=$p eaIndexValid=$pi#$rep")
      }
      // ── 7. directed: size x sizeBytesLog x miHostSize (SzCtx/SzHost + delta) ───
      for (s <- Size.elements; l <- 0 until 4; hs <- Size.elements) {
        randCtx(); ctx.size #= s; ctx.sizeBytesLog #= l; ctx.miHostSize #= hs
        check(s"size=$s log=$l hostSize=$hs")
      }
      // ── 8. directed: the A7-byte delta rule — sweep ay/ax over 0..7 at .B/.W/.L ─
      for (ay <- 0 until 8; ax <- 0 until 8; s <- Size.elements) {
        randCtx()
        val ow = (rng.nextInt(1 << 16) & ~0x0e07) | (ax << 9) | ay
        ctx.opword #= ow; ctx.size #= s; ctx.sizeBytesLog #= rng.nextInt(4)
        check(s"ay=$ay ax=$ax size=$s")
      }
      // ── 9. directed: movesAliasStore's four conjuncts (the alias corner case) ──
      //     movesAliasStore = (row is SMovesRn/MStore) && movesRnIsA && (movesRn === eaBase)
      //                       && (casAutoMode =/= NONE)
      for (isA <- Seq(false, true); alias <- Seq(false, true); auto <- EaAuto.elements;
           rep <- 0 until 2) {
        randCtx()
        val base = rng.nextInt(32)
        ctx.eaBase #= base
        ctx.movesRn #= (if (alias) base else (base + 1 + rng.nextInt(31)) % 32)
        ctx.movesRnIsA #= isA
        ctx.casAutoMode #= auto
        check(s"movesAlias isA=$isA alias=$alias auto=$auto#$rep")
      }
      // ── 10. directed: index descriptors + base/index validity ──────────────────
      for (bv <- Seq(false, true); iv <- Seq(false, true); il <- Seq(false, true);
           sc <- 0 until 4) {
        randCtx()
        ctx.eaBaseValid #= bv; ctx.eaIndexValid #= iv; ctx.eaIndexLong #= il
        ctx.eaIndexScale #= sc
        ctx.miOtherEaBaseValid #= !bv; ctx.miOtherEaIndexValid #= !iv
        ctx.miOtherEaIndexLong #= !il; ctx.miOtherEaIndexScale #= (3 - sc)
        check(s"idx bv=$bv iv=$iv il=$il sc=$sc")
      }
      // ── 11. directed: the flag-source + supervisor + movea booleans ────────────
      for (bits <- 0 until 64) {
        randCtx()
        ctx.miRNzvc #= (bits & 1) != 0
        ctx.miRX    #= (bits & 2) != 0
        ctx.miWNzvc #= (bits & 4) != 0
        ctx.miWX    #= (bits & 8) != 0
        ctx.needsSup #= (bits & 16) != 0
        ctx.miMovea  #= (bits & 32) != 0
        check(s"flags=$bits")
      }
      // ── 12. edge vectors: all-zeros / all-ones Ctx ─────────────────────────────
      constCtx(hi = false); check("ctx=all-zero")
      constCtx(hi = true);  check("ctx=all-one")

      // ── 13. randomized breadth ─────────────────────────────────────────────────
      for (i <- 0 until 250) { randCtx(); check(s"random#$i") }
    }

    info(f"microcode resolve-equivalence: $ctxVectors%d Ctx vectors x $romSize%d ROM rows " +
         f"= $comparisons%,d DecodedUop comparisons, $badVectors%d mismatching vectors")
    if (failures.nonEmpty)
      fail(s"resolve()/resolveFromBits() MISMATCH on $badVectors Ctx vector(s) out of " +
           s"$ctxVectors ($comparisons total row comparisons); first ${failures.length} " +
           s"(row, ctx) case(s):\n" + failures.mkString("\n"))
    assert(comparisons > 100000, s"sweep too small: only $comparisons comparisons")
  }
}
