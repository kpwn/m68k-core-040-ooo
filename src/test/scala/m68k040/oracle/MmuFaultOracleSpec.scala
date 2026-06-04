package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

/** Directed validation of the 68040 software-MMU + format-$7 access-fault oracle
  * added to the Musashi runner (the byte-for-byte reference for the RTL's
  * format-$7 page-fault delivery). NOT a lock-step — it confirms the oracle
  * itself: a data access to a non-resident page raises a vector-2 access fault,
  * stacks a format-$7 frame, runs a handler that maps the page, RTEs, and the
  * re-executed access succeeds. Asserts the stacked frame bytes + the resume.
  *
  * Page table (long-format, our RTL TableWalker layout):
  *   root @ 0x80000, pointer @ 0x81000, page @ 0x82000.
  *   VA 0x2000: rootIdx=VA[31:25]=0, ptrIdx=VA[24:18]=0, pageIdx=VA[17:12]=2.
  * The program builds root/ptr resident + page NON-RESIDENT, installs the
  * vector-2 handler @ 0x8 (VBR=0), then `move.l %d0,0x2000` faults. The handler
  * writes a resident page descriptor (PPN 0x42) and RTEs; the store re-executes
  * and lands at PA 0x42000. Data window = [0x2000, 0x3000).
  */
class MmuFaultOracleSpec extends AnyFunSuite {

  val Root = 0x80000L
  val Ptr  = 0x81000L
  val Page = 0x82000L
  val mmu  = Some(Musashi.MmuConfig(rootPtr = Root, dataLo = 0x2000L, dataHi = 0x3000L))

  // Build descriptors with MOVE.L to the (identity, outside-window) PT addresses.
  // root[0] = Ptr | 0x2 (UDT resident)  ; ptr[0] = Page | 0x2 ; page[2] = 0 (PDT=00 non-resident).
  // Handler writes page[2] = (0x42<<12) | 0x1 (PDT resident) then RTE.
  // Handler copies the 60-byte ($3C) format-$7 frame from (A7) to scratch @ 0x5000
  // (identity, outside the data window) so the test can inspect the stacked bytes,
  // then maps the page (page[2] resident, PPN 0x42) and RTEs.
  val program =
    "move.l #0x00081002,%d1 ; move.l %d1,0x80000 ; " +   // root[0] -> Ptr resident
    "move.l #0x00082002,%d1 ; move.l %d1,0x81000 ; " +   // ptr[0]  -> Page resident
    "move.l #0x00000000,%d1 ; move.l %d1,0x82008 ; " +   // page[2] -> non-resident (pageIdx=2 -> +8)
    "move.l #handler,%d1 ; move.l %d1,0x8 ; " +          // vector 2 (access fault) @ 0x8
    "moveq #42,%d0 ; move.l %d0,0x2000 ; " +             // FAULTS (page non-resident), then re-runs
    "loop: bra loop ; " +
    "handler: move.l %a7,%a0 ; move.l #0x5000,%a1 ; moveq #14,%d2 ; " +
    "cpy: move.l (%a0)+,(%a1)+ ; dbra %d2,cpy ; " +      // copy 15 longs (60 bytes)
    "move.l #0x00042001,%d1 ; move.l %d1,0x82008 ; rte"

  test("oracle: non-resident page -> format-$7 -> handler maps -> RTE -> resume") {
    val steps = Musashi.assembleAndTrace(program, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v
      case Left(err) => fail(s"oracle trace failed: ${err.reason}")
    }
    assert(steps.nonEmpty, "no trace steps")
    // The trace should show the handler entry (PC in the handler region) then a
    // return to the faulting `move.l %d0,0x2000` re-execution then `bra loop`.
    val pcs = steps.map(_.pc)
    info(s"first 20 PCs: ${pcs.take(20).map(p => f"0x$p%08x").mkString(" ")}")
    // The faulting store must have eventually succeeded: run final-state + check PA.
    val st = Musashi.assembleAndRun(program, mmu = mmu, stopPc = None, maxCycles = 20000) match {
      case Right(s)  => s
      case Left(err) => fail(s"oracle run failed: ${err.reason}")
    }
    // PA for VA 0x2000 with PPN 0x42 = 0x42000. The store wrote 42 (0x2a) as a long.
    val pa = 0x42000L
    val b0 = st.finalRam.getOrElse(pa + 0, -1)
    val b3 = st.finalRam.getOrElse(pa + 3, -1)
    info(f"PA 0x$pa%08x bytes: [0]=0x$b0%02x [3]=0x$b3%02x")
    assert(b3 == 0x2a, f"re-executed store must land 0x2a at PA 0x${pa + 3}%08x (got 0x$b3%02x)")

    // Inspect the copied format-$7 frame @ 0x5000 (big-endian words).
    def w(off: Int): Int = ((st.finalRam.getOrElse(0x5000L + off, 0) << 8) |
                             st.finalRam.getOrElse(0x5000L + off + 1, 0)) & 0xffff
    def l(off: Int): Long = ((w(off).toLong << 16) | w(off + 2)) & 0xffffffffL
    val sr        = w(0x00)
    val pc        = l(0x02)
    val fmtVec    = w(0x06)
    val effAddr   = l(0x08)
    val ssw       = w(0x0c)
    val faultAddr = l(0x14)
    info(f"frame: SR=0x$sr%04x PC=0x$pc%08x fmtVec=0x$fmtVec%04x EA=0x$effAddr%08x SSW=0x$ssw%04x fault=0x$faultAddr%08x")
    // MAME m68ki_stack_frame_0111 for our write fault (vector 2):
    //   fmtVec = 0x7000 | (2<<2) = 0x7008
    //   EA == fault address == faulting VA 0x2000
    //   SSW = (in_mmu 0x400) | fc(supervisor data = 0x5) | (rw=write -> 0 << 8) = 0x405
    //   PC = faulting instruction PC (the move.l %d0,0x2000)
    assert(fmtVec == 0x7008, f"format/vector word must be 0x7008 (got 0x$fmtVec%04x)")
    assert(effAddr == 0x2000L, f"effective address must be the faulting VA 0x2000 (got 0x$effAddr%08x)")
    assert(faultAddr == 0x2000L, f"fault address must be the faulting VA 0x2000 (got 0x$faultAddr%08x)")
    assert(ssw == 0x0405, f"SSW must be 0x0405 (in_mmu|super-data|write) (got 0x$ssw%04x)")
    assert((sr & 0x2000) != 0, f"stacked SR must have S set (got 0x$sr%04x)")
  }
}
