package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

/** Task 4: directed validation of the 68040 software-MMU INSTRUCTION-FETCH fault in
  * the Musashi oracle (the byte-for-byte reference for the RTL's I-fetch format-$7
  * delivery). A jump to a NON-RESIDENT instruction page raises a vector-2 access
  * fault with a PROGRAM-space SSW (FC bit1) and the faulting PC as the EA, stacks a
  * format-$7 frame, runs a handler that copies the frame out + writes the sentinel.
  *
  * Page table (long-format, RTL TableWalker layout): root @ 0x80000, ptr @ 0x81000,
  * pageA @ 0x82000. The instruction window is [0x6000, 0x7000): VA 0x6000 ->
  * rootIdx=0, ptrIdx=0, pageIdx=6 — left NON-RESIDENT. The data window [0x4000,
  * 0x6000) holds the scratch copy. Code at loadAddr is identity (outside both
  * windows). */
class ItlbFaultOracleSpec extends AnyFunSuite {

  val Root = 0x80000L
  val Ptr  = 0x81000L
  val PagA = 0x82000L

  // Preloaded resident root[0]/ptr[0]; pageA[6] explicitly NON-RESIDENT (the I-fetch
  // fault). pageA[6] MUST be written 0 — an unwritten descriptor reads 0xFFFFFFFF
  // (PDT=11=resident) and would NOT fault. pageIdx(0x6000) = 6 -> byte offset 24.
  val oraclePt = Seq(
    Root        -> ((Ptr  & 0xfffffff0L) | 0x2L),
    Ptr         -> ((PagA & 0xfffffff0L) | 0x2L),
    (PagA + 6*4) -> 0x0L)
  // No DATA window (the handler's scratch + stack accesses are identity); the
  // INSTRUCTION window covers the non-resident code page 0x6000.
  val mmu = Some(Musashi.MmuConfig(rootPtr = Root, dataLo = 0L, dataHi = 0L,
                                   ptPreload = oraclePt, instrLo = 0x6000L, instrHi = 0x7000L))

  // The program: install the vector-2 handler @ 0x8, then JMP to the non-resident
  // I-page 0x6000 -> faults. The handler copies the 60-byte ($3C) format-$7 frame
  // from (A7) to scratch @ 0x5000 (data window), then writes the sentinel and halts.
  val Sentinel = 0xFFFF0000L
  val program =
    "move.l #handler,%d1 ; move.l %d1,0x8 ; " +     // vector 2 (access fault) @ 0x8
    "jmp 0x6000 ; " +                               // FAULTS: non-resident I-page
    "handler: move.l %a7,%a0 ; move.l #0x5000,%a1 ; moveq #14,%d2 ; " +
    "cpy: move.l (%a0)+,(%a1)+ ; dbra %d2,cpy ; " + // copy 15 longs (60 bytes)
    "move.l #0xdead,%d3 ; move.l %d3,0xFFFF0000 ; " + // sentinel -> stop
    "done: bra done"

  test("oracle: jump to a non-resident I-page -> format-$7 (SSW program), fault addr = PC") {
    val st = Musashi.assembleAndRun(program, mmu = mmu, stopPc = None, maxCycles = 20000) match {
      case Right(s)  => s
      case Left(err) => fail(s"oracle run failed: ${err.reason}")
    }
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
    // format/vector word = 0x7000 | (2<<2) = 0x7008
    assert(fmtVec == 0x7008, f"format/vector word must be 0x7008 (got 0x$fmtVec%04x)")
    // EA / fault address = the faulting instruction page VA 0x6000
    assert(effAddr == 0x6000L, f"EA must be the faulting I-fetch VA 0x6000 (got 0x$effAddr%08x)")
    assert(faultAddr == 0x6000L, f"fault address must be 0x6000 (got 0x$faultAddr%08x)")
    // SSW: in_mmu (0x400) | fc(supervisor PROGRAM = 0x6) | (rw=read -> 1<<8 = 0x100)
    //   => 0x400 | 0x6 | 0x100 = 0x506
    assert(ssw == 0x0506, f"SSW must be 0x0506 (in_mmu|super-program|read) (got 0x$ssw%04x)")
    assert((sr & 0x2000) != 0, f"stacked SR must have S set (got 0x$sr%04x)")
  }
}
