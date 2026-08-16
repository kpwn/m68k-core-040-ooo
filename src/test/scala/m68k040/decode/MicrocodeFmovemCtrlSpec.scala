package m68k040.decode

import org.scalatest.funsuite.AnyFunSuite
import m68k040.decode.Microcode._

/** Task 9b — FMOVEM control-register LIST form (`FMOVEM.L <ea>,{FPCR/FPSR/FPIAR}` and
  * the reverse). Pure Scala unit tests over the generated ROM rows: no hardware, no
  * simulation. They pin the two things the row generator can get silently wrong —
  * the D9 register/address ordering, and the "exactly one sysOp, and it is last"
  * invariant the whole design rests on.
  *
  * Design: docs/superpowers/specs/2026-08-16-fp-control-multiword-transfer-design.md
  */
class MicrocodeFmovemCtrlSpec extends AnyFunSuite {

  /** The rows of one generated program, sliced out of the real ROM at the real entry
    * point the hardware dispatch mux uses — so a generator/offset mismatch fails here. */
  private def program(load: Boolean, bucket: Int, popcount: Int): Vector[Desc] = {
    val start = fpCtrlEntry(load, bucket, popcount)
    val rows  = rom.drop(start).takeWhile(!_.isLast)
    rows :+ rom(start + rows.size)
  }

  private val buckets = Seq(0 -> "non-auto", 1 -> "(An)+", 2 -> "-(An)")

  // ── Structural invariants over the WHOLE ROM ─────────────────────────────────────

  test("the terminal apply row is the ROM's only sysOp, and every one of them is isLast") {
    val applyRows = rom.zipWithIndex.filter(_._1.fpCtrlApply)
    assert(applyRows.nonEmpty, "no terminal apply row was generated at all")
    // 9 load-direction programs (3 buckets x 3 popcounts), one apply row each.
    assert(applyRows.size == 9, s"expected 9 apply rows, got ${applyRows.size}")
    applyRows.foreach { case (d, i) =>
      assert(d.isLast, s"ROM row $i sets fpCtrlApply but not isLast — a sysOp that is not " +
                       "a program's last µop is silently discarded (excSquash + S_REDIR)")
      assert(!d.fpCtrlCap, s"ROM row $i is both an apply and a capture row")
      assert(d.imm == SFpCtrlRc, s"ROM row $i's apply must carry the mask via SFpCtrlRc")
      assert(d.useImm, s"ROM row $i's apply must set useImm (sysRc comes from imm[11:0])")
      assert(d.mem == MNone, s"ROM row $i's apply must not be a memory µop")
    }
  }

  test("every capture row is an ordinary MLoad targeting T0/T1/T2 and is never a sysOp") {
    val caps = rom.zipWithIndex.filter(_._1.fpCtrlCap)
    assert(caps.size == 18, s"expected 18 capture rows (3 buckets x (1+2+3) loads), got ${caps.size}")
    caps.foreach { case (d, i) =>
      assert(d.mem == MLoad, s"ROM row $i is a capture but not a load")
      assert(!d.fpCtrlApply, s"ROM row $i is both a capture and an apply")
      assert(Seq(ST0, ST1, ST2).contains(d.dst),
             s"ROM row $i's capture slot is derived from its dst temp; got ${d.dst}")
      assert(d.sz == SzLong, s"ROM row $i: control-register transfers are always LONG")
    }
  }

  test("every generated program has exactly one isFirst and one isLast row") {
    for (load <- Seq(true, false); (b, name) <- buckets; n <- 1 to 3) {
      val p = program(load, b, n)
      assert(p.count(_.isFirst) == 1, s"load=$load $name pop=$n: isFirst count ${p.count(_.isFirst)}")
      assert(p.count(_.isLast) == 1,  s"load=$load $name pop=$n: isLast count ${p.count(_.isLast)}")
      assert(p.head.isFirst, s"load=$load $name pop=$n: isFirst is not the first row")
      assert(p.last.isLast,  s"load=$load $name pop=$n: isLast is not the last row")
    }
  }

  test("generated programs do not overlap and stay inside the ROM") {
    val spans = for (load <- Seq(true, false); (b, _) <- buckets; n <- 1 to 3)
                yield { val s = fpCtrlEntry(load, b, n); (s, s + program(load, b, n).size) }
    spans.foreach { case (s, e) => assert(e <= romSize, s"program [$s,$e) runs past romSize $romSize") }
    spans.sortBy(_._1).sliding(2).foreach {
      case Seq((_, e1), (s2, _)) => assert(e1 == s2, s"gap/overlap between programs at $e1 vs $s2")
      case _ =>
    }
  }

  // ── LOAD direction ───────────────────────────────────────────────────────────────

  test("load direction: row counts and shape per bucket") {
    for (n <- 1 to 3) {
      // non-auto: n loads + apply
      val base = program(load = true, bucket = 0, popcount = n)
      assert(base.size == n + 1, s"non-auto pop=$n: ${base.size} rows")
      assert(base.take(n).forall(d => d.fpCtrlCap && d.srcA == SEaBase && d.indexFromEa))
      // (An)+ : n loads + An write-back + apply
      val post = program(load = true, bucket = 1, popcount = n)
      assert(post.size == n + 2, s"(An)+ pop=$n: ${post.size} rows")
      assert(post(n).uop == UAddDrop && post(n).imm == SFpCtrlDelta, "(An)+ write-back row")
      // -(An) : An write-back + n loads + apply
      val pre = program(load = true, bucket = 2, popcount = n)
      assert(pre.size == n + 2, s"-(An) pop=$n: ${pre.size} rows")
      assert(pre.head.uop == UAddDrop && pre.head.imm == SFpCtrlDelta, "-(An) write-back row")
    }
  }

  test("load direction: the terminal apply is the LAST row in every bucket") {
    // Load-bearing for (An)+ specifically: Task 6b's own AUTO_POST bucket puts its An
    // write-back last, which would silently discard it here.
    for ((b, name) <- buckets; n <- 1 to 3) {
      val p = program(load = true, bucket = b, popcount = n)
      assert(p.last.fpCtrlApply, s"$name pop=$n: last row is not the apply")
      assert(!p.init.exists(_.fpCtrlApply), s"$name pop=$n: an apply row appears before the last")
    }
  }

  test("load direction: transfers ASCEND in position order in every bucket (D9)") {
    for (n <- 1 to 3) {
      // Non-auto EA: base + disp, disp+4, disp+8.
      val base = program(load = true, bucket = 0, popcount = n)
      assert(base.take(n).map(_.imm) == Seq(SEaDispLo, SFpDispMid, SFpDispHi).take(n))
      assert(base.take(n).map(_.dst) == Seq(ST0, ST1, ST2).take(n))
      // Both auto buckets: An+0, An+4, An+8 off the SAME (unmodified / already fully
      // decremented) An. NO per-transfer reversal, and no per-transfer decrement.
      for (b <- Seq(1, 2)) {
        val p = program(load = true, bucket = b, popcount = n)
        val loads = p.filter(_.fpCtrlCap)
        assert(loads.size == n)
        assert(loads.forall(_.srcA == SAy), s"bucket $b: auto transfers must address off SAy")
        assert(loads.map(d => if (d.useImm) d.imm else SNone) == Seq(SNone, SImm4, SImm8).take(n),
               s"bucket $b pop=$n: transfer offsets are not ascending 0/+4/+8")
        assert(loads.map(_.dst) == Seq(ST0, ST1, ST2).take(n))
      }
    }
  }

  test("-(An): the single up-front decrement precedes every transfer (D9, both directions)") {
    for (load <- Seq(true, false); n <- 1 to 3) {
      val p = program(load, bucket = 2, popcount = n)
      assert(p.head.uop == UAddDrop && p.head.dst == SAy && p.head.imm == SFpCtrlDelta,
             s"load=$load pop=$n: -(An) must start with ONE An -= 4*popcount row")
      assert(p.tail.forall(_.uop != UAddDrop),
             s"load=$load pop=$n: -(An) must not decrement again per transfer " +
             "(that is Musashi's confirmed-wrong behaviour, D9)")
    }
  }

  // ── STORE direction ──────────────────────────────────────────────────────────────

  test("store direction: no sysOp anywhere, reads precede stores, positions in order") {
    for ((b, name) <- buckets; n <- 1 to 3) {
      val p = program(load = false, bucket = b, popcount = n)
      assert(!p.exists(d => d.fpCtrlApply || d.fpCtrlCap),
             s"$name pop=$n: the store direction must use no sysOp and no capture at all")
      val reads  = p.filter(_.uop == UFpCtrlRead)
      val stores = p.filter(_.mem == MStore)
      assert(reads.size == n && stores.size == n, s"$name pop=$n: ${reads.size} reads / ${stores.size} stores")
      // Every read is decoded before every store (the stores consume their temps).
      assert(p.indexWhere(_.uop == UFpCtrlRead) < p.indexWhere(_.mem == MStore))
      assert(p.lastIndexWhere(_.uop == UFpCtrlRead) < p.indexWhere(_.mem == MStore))
      // Position order: read i -> T_i, and store i writes T_i to the i-th address.
      assert(reads.map(_.dst) == Seq(ST0, ST1, ST2).take(n), s"$name pop=$n: read dst order")
      assert(reads.map(_.imm) == Seq(SFpCtrlRc, SFpCtrlSel1, SFpCtrlSel2).take(n),
             s"$name pop=$n: read position-selector order")
      assert(reads.forall(_.useImm))
      assert(stores.map(_.srcB) == Seq(ST0, ST1, ST2).take(n), s"$name pop=$n: store data order")
    }
  }

  test("store direction: row counts and ASCENDING addresses per bucket") {
    for (n <- 1 to 3) {
      val base = program(load = false, bucket = 0, popcount = n)
      assert(base.size == 2 * n, s"non-auto pop=$n: ${base.size} rows")
      assert(base.filter(_.mem == MStore).map(_.imm) == Seq(SEaDispLo, SFpDispMid, SFpDispHi).take(n))
      assert(base.filter(_.mem == MStore).forall(d => d.srcA == SEaBase && d.indexFromEa))
      for ((b, extraLast) <- Seq(1 -> true, 2 -> false)) {
        val p = program(load = false, bucket = b, popcount = n)
        assert(p.size == 2 * n + 1, s"bucket $b pop=$n: ${p.size} rows")
        val stores = p.filter(_.mem == MStore)
        assert(stores.forall(_.srcA == SAy))
        assert(stores.map(d => if (d.useImm) d.imm else SNone) == Seq(SNone, SImm4, SImm8).take(n),
               s"bucket $b pop=$n: store offsets are not ascending 0/+4/+8")
        // (An)+ writes An back LAST (nothing may follow the final store's address use);
        // -(An) writes it back FIRST.
        if (extraLast) assert(p.last.uop == UAddDrop) else assert(p.head.uop == UAddDrop)
      }
    }
  }

  test("the ROM's other 310 rows are untouched by this task") {
    val pre = rom.take(fpCtrlEntry(load = true, bucket = 0, popcount = 1))
    assert(pre.size == 310, s"pre-Task-9b ROM size changed: ${pre.size}")
    assert(!pre.exists(d => d.fpCtrlApply || d.fpCtrlCap || d.uop == UFpCtrlRead))
  }
}
