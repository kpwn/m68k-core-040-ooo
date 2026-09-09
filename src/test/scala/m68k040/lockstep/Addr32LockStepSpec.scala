package m68k040.lockstep

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.SparseMemory
import org.scalatest.funsuite.AnyFunSuite

/** 32-BIT-ADDRESSING lock-step battery.
  *
  * Every pre-existing lock-step program in this repo puts its DATA below 16 MB
  * (0x2000/0x3000 and the 0x0010_0000/0x0020_0000 stacks), so the top address
  * BYTE is always zero. Mac OS in 24-bit addressing mode has the same property,
  * which is exactly why the machine boots in that mode and bombs (`dsBadPatchHeader`,
  * SysError 99) in 32-bit mode with the OS living at ~0x01FF_xxxx.
  *
  * These programs drive the axis that has never been tested: data addresses whose
  * bits 24..25 are SET, plus aliases that differ ONLY in those bits. Any structure
  * that compares, tags, forwards or indexes on fewer than 32 address bits collapses
  * 0x01FF6FC8 onto 0x00FF6FC8 and shows up here as a register/memory divergence.
  */
class Addr32LockStepSpec extends AnyFunSuite {

  private val h = new ExecuteLockStepSpec

  // Four addresses identical in bits 23..0, differing only in bits 25..24.
  private val A0 = 0x00FF6FC8L   // bit24=0 bit25=0  -- reachable in 24-bit mode
  private val A1 = 0x01FF6FC8L   // bit24=1          -- the Mac OS 32-bit-mode System heap
  private val A2 = 0x02FF6FC8L   // bit25=1
  private val A3 = 0x03FF6FC8L   // bit25=1 bit24=1  -- top of the 64 MB window

  // ── 1. absolute-long store/load, all four aliases live at once ────────────────
  test("addr32: four bit-24/25 aliases hold independent longs (abs.l)", VerilatorTest) {
    h.runLockStep("addr32-alias-absl", Seq(
      f"move.l #0x11111111,0x$A0%x",
      f"move.l #0x22222222,0x$A1%x",
      f"move.l #0x33333333,0x$A2%x",
      f"move.l #0x44444444,0x$A3%x",
      f"move.l 0x$A0%x,%%d0",
      f"move.l 0x$A1%x,%%d1",
      f"move.l 0x$A2%x,%%d2",
      f"move.l 0x$A3%x,%%d3").mkString(" ; "),
      checkMem = Seq(A0, A1, A2, A3))
  }

  // ── 2. the same, register-indirect, with the loads issued while the stores are
  //      still resident in the store queue (exercises the SQ forward compare) ────
  test("addr32: SQ store-to-load forwarding must not alias across bit 24/25", VerilatorTest) {
    h.runLockStep("addr32-fwd-alias", Seq(
      f"move.l #0x$A0%x,%%a0",
      f"move.l #0x$A1%x,%%a1",
      f"move.l #0x$A2%x,%%a2",
      f"move.l #0x$A3%x,%%a3",
      "move.l #0x0badf00d,(%a0)",
      "move.l #0xaaaa5555,(%a1)",
      "move.l #0xdeadbeef,(%a2)",
      "move.l #0xcafebabe,(%a3)",
      "move.l (%a0),%d0",
      "move.l (%a1),%d1",
      "move.l (%a2),%d2",
      "move.l (%a3),%d3").mkString(" ; "),
      checkMem = Seq(A0, A1, A2, A3))
  }

  // ── 3. partial-overlap forwarding at a high address: byte/word reads of a long
  //      store, plus the alias that must NOT be served by it ─────────────────────
  test("addr32: byte/word partial forwards at a high address, with a bit-24 alias", VerilatorTest) {
    h.runLockStep("addr32-fwd-partial", Seq(
      f"move.l #0x$A0%x,%%a0",
      f"move.l #0x$A1%x,%%a1",
      "move.l #0x01020304,(%a0)",
      "move.l #0xa1b2c3d4,(%a1)",
      "move.b (%a1),%d0",
      "move.b 3(%a1),%d1",
      "move.w 2(%a1),%d2",
      "move.b (%a0),%d3",
      "move.w (%a0),%d4",
      "move.l (%a1),%d5").mkString(" ; "),
      checkMem = Seq(A0, A1))
  }

  // ── 4. unaligned + 16-byte-cache-line-straddling accesses at a high address ────
  test("addr32: odd and line-straddling accesses above 16 MB", VerilatorTest) {
    // 0x01FF6FCF is line offset 15: a long here spans two cache lines, and a word
    // here is the `DcacheByteLane.extract` wrap case.
    h.runLockStep("addr32-linecross", Seq(
      "move.l #0x01ff6fcf,%a0",
      "move.l #0x01ff6fdf,%a1",
      "move.l #0x89abcdef,(%a0)",
      "move.l #0x01234567,(%a1)",
      "move.l (%a0),%d0",
      "move.l (%a1),%d1",
      "move.w (%a0),%d2",
      "move.b 3(%a0),%d3").mkString(" ; "),
      checkMem = Seq(0x01ff6fcfL, 0x01ff6fdfL))
  }

  // ── 5. postincrement / predecrement rings walking a high address ───────────────
  test("addr32: (An)+ / -(An) chains above 16 MB", VerilatorTest) {
    h.runLockStep("addr32-autoinc", Seq(
      "move.l #0x01ff7000,%a0",
      "move.l #0x11223344,(%a0)+",
      "move.l #0x55667788,(%a0)+",
      "move.l #0x99aabbcc,(%a0)+",
      "move.l -(%a0),%d0",
      "move.l -(%a0),%d1",
      "move.l -(%a0),%d2",
      "move.l %a0,%d3").mkString(" ; "),
      checkMem = Seq(0x01ff7000L, 0x01ff7004L, 0x01ff7008L))
  }

  // ── 6. MOVEM whose element span crosses a 4 KB boundary, at a high address ─────
  //      (drives LsEuPlugin's movemCrosses / movemCrossAddr far-page probe)
  test("addr32: MOVEM crossing a page boundary above 16 MB", VerilatorTest) {
    h.runLockStep("addr32-movem-crosspage", Seq(
      "move.l #0x01ffeff8,%a0",
      "move.l #0x0a0a0a0a,%d0",
      "move.l #0x0b0b0b0b,%d1",
      "move.l #0x0c0c0c0c,%d2",
      "move.l #0x0d0d0d0d,%d3",
      "movem.l %d0-%d3,(%a0)",
      "movem.l (%a0),%d4-%d7").mkString(" ; "),
      nInstr = 7, checkMem = Seq(0x01ffeff8L, 0x01ffeffcL, 0x01fff000L, 0x01fff004L))
  }

  // ── 7. a SUPERVISOR STACK above 16 MB, odd, exactly as the bombing machine had
  //      it (A7 = 0x01FF6A7A) -- push/pop, LINK/UNLK, BSR/RTS ───────────────────
  test("addr32: odd supervisor stack above 16 MB (push/pop, LINK/UNLK)", VerilatorTest) {
    h.runLockStep("addr32-odd-hi-sp", Seq(
      "move.l #0x01ff6a7a,%a7",
      "move.l #0x11223344,-(%a7)",
      "move.l #0x55667788,-(%a7)",
      "link.w %a6,#-76",
      "move.l #0x9abcdef0,-4(%a6)",
      "move.l -4(%a6),%d2",
      "unlk %a6",
      "move.l (%a7)+,%d0",
      "move.l (%a7)+,%d1",
      "move.l %a7,%d3").mkString(" ; "))
  }

  test("addr32: BSR/RTS over a high stack", VerilatorTest) {
    h.runLockStep("addr32-bsr-hi-sp", Seq(
      "move.l #0x01ff6b00,%a7",
      "bsr.s 1f",
      "moveq #7,%d1",
      "bra.s 2f",
      "1: moveq #3,%d0",
      "move.l (%a7),%d2",
      "rts",
      "2: moveq #9,%d3").mkString(" ; "),
      nInstr = 8)
  }

  // ── 8. MMU: a translated data page whose VA needs address bit 24 (ptrIdx >= 64) ─
  //      24-bit mode can never produce ptrIdx > 63, so this walk shape is new.
  test("addr32: MMU walk with pointer index >= 64 (VA bit 24 set)", VerilatorTest) {
    h.runLockStep("addr32-mmu-ptridx-hi",
      "moveq #5,%d0 ; move.l %d0,0x01ff6000 ; move.l 0x01ff6000,%d1 ; add.l %d1,%d1 ; move.l %d1,0x01ff6004",
      checkMem = Seq(0x01ff6000L, 0x01ff6004L), mmuMap = Some((0x01ff6000L, 0x42L)))
  }

  // ── 9. MMU: a translated data page whose VA needs address bit 25 (rootIdx != 0) ─
  test("addr32: MMU walk with root index != 0 (VA bit 25 set)", VerilatorTest) {
    h.runLockStep("addr32-mmu-rootidx-hi",
      "moveq #6,%d0 ; move.l %d0,0x02006000 ; move.l 0x02006000,%d1 ; add.l %d1,%d1 ; move.l %d1,0x02006004",
      checkMem = Seq(0x02006000L, 0x02006004L), mmuMap = Some((0x02006000L, 0x43L)))
  }

  // ── 10. bit-scan / RMW ops at a high address (read-modify-write byte lane) ─────
  test("addr32: BSET/BCLR/TAS and CLR at a high address", VerilatorTest) {
    h.runLockStep("addr32-rmw", Seq(
      f"move.l #0x$A1%x,%%a1",
      f"move.l #0x$A0%x,%%a0",
      "move.l #0x00000000,(%a0)",
      "move.l #0x00000000,(%a1)",
      "bset #3,(%a1)",
      "bset #5,1(%a1)",
      "bclr #3,(%a1)",
      "move.l (%a1),%d0",
      "move.l (%a0),%d1",
      "clr.w 2(%a1)",
      "move.l (%a1),%d2").mkString(" ; "),
      checkMem = Seq(A0, A1))
  }

  // ── 11. D-cache CONFLICT pressure: six lines that share the SAME set index and
  //      differ ONLY in address bits 24..26, driven through a 4-way set so every
  //      one of them is evicted (dirty) and refilled at least once. An eviction /
  //      writeback address rebuilt from a tag that dropped a high bit lands the
  //      dirty line in the WRONG place; a refill tag compare that dropped one
  //      returns another stream's data. Both show up here.
  private val S = Seq(0x00FF0000L, 0x01FF0000L, 0x02FF0000L, 0x03FF0000L,
                      0x04FF0000L, 0x05FF0000L)   // all D-cache set 0, offset 0

  test("addr32: 6-deep conflict set differing only in address bits 24..26", VerilatorTest) {
    val body =
      S.zipWithIndex.map { case (a, i) => f"move.l #0x$a%x,%%a${i}" } ++
      S.zipWithIndex.map { case (_, i) => f"move.l #0x${0x11111111L * (i + 1)}%08x,(%%a${i})" } ++
      // second pass: every line above has been evicted by the ones after it, so each
      // of these loads is a refill whose tag must still discriminate bits 24..26
      Seq(0, 1, 2, 3, 4, 5).map(i => f"move.l (%%a${i}),%%d${i}")
    h.runLockStep("addr32-conflict-set", body.mkString(" ; "), checkMem = S, maxCycles = 20000)
  }

  test("addr32: 6-deep conflict set, dirty re-store then re-read", VerilatorTest) {
    val body =
      S.zipWithIndex.map { case (a, i) => f"move.l #0x$a%x,%%a${i}" } ++
      S.zipWithIndex.map { case (_, i) => f"move.l #0x${0x11111111L * (i + 1)}%08x,(%%a${i})" } ++
      // re-dirty the two oldest (already evicted) lines, then read every stream back
      Seq("move.l #0xaaaaaaaa,(%a0)", "move.l #0xbbbbbbbb,(%a1)") ++
      Seq(0, 1, 2, 3, 4, 5).map(i => f"move.l (%%a${i}),%%d${i}")
    h.runLockStep("addr32-conflict-redirty", body.mkString(" ; "), checkMem = S, maxCycles = 20000)
  }

  // ── 12. the same conflict pressure with the D-cache DISABLED (CACR.DE=0), which
  //      is the posture the real ROM runs long stretches in: every access becomes a
  //      cache-INHIBITED precise store / uncached load straight to AXI.
  test("addr32: high-address streams with the D-cache disabled (DE=0)", VerilatorTest) {
    val body =
      S.zipWithIndex.map { case (a, i) => f"move.l #0x$a%x,%%a${i}" } ++
      S.zipWithIndex.map { case (_, i) => f"move.l #0x${0x11111111L * (i + 1)}%08x,(%%a${i})" } ++
      Seq(0, 1, 2, 3, 4, 5).map(i => f"move.l (%%a${i}),%%d${i}")
    h.runLockStep("addr32-conflict-de0", body.mkString(" ; "), checkMem = S,
      cacr = 0x00008000L, maxCycles = 40000)
  }

  // ── 13. a real copy loop through high memory: 128 longs from one high buffer to
  //      another, then summed back. Exercises sustained refill/evict/writeback
  //      traffic with every address above 16 MB, and the sum is compared against the
  //      oracle every single instruction.
  test("addr32: 128-long copy loop between two high buffers, summed back", VerilatorTest) {
    val src = Seq(
      "move.l #0x01ff0000,%a0",
      "move.l #0x02ff0000,%a1",
      "move.l #0x0000007f,%d7",
      "move.l #0x01000001,%d6",
      "1: move.l %d6,(%a0)+",
      "add.l #0x01010101,%d6",
      "dbra %d7,1b",
      "move.l #0x01ff0000,%a0",
      "move.l #0x0000007f,%d7",
      "2: move.l (%a0)+,(%a1)+",
      "dbra %d7,2b",
      "move.l #0x02ff0000,%a1",
      "move.l #0x0000007f,%d7",
      "moveq #0,%d0",
      "3: add.l (%a1)+,%d0",
      "dbra %d7,3b").mkString(" ; ")
    // 4 + 128*3 + 2 + 128*2 + 3 + 128*2 executed instructions
    h.runLockStep("addr32-copy-loop", src, nInstr = 4 + 128 * 3 + 2 + 128 * 2 + 3 + 128 * 2,
      maxCycles = 200000)
  }

  // ── 14. MMU under real pressure: 64 mapped data pages, all above 16 MB, walked
  //      through a 32-entry ATC so most accesses take a full 3-level table search.
  //      This is the traffic shape 32-bit addressing creates and 24-bit mode never
  //      does: pointer index 0x7F, non-identity PPNs, one deferred U/M descriptor
  //      write per page, and continuous ATC eviction/refill. A walker that returns
  //      the wrong descriptor, an ATC that answers a MuxOH of two matching ways, or
  //      a U/M drain that lands on the wrong line all show up as a wrong sum.
  private val PGBASE = 0x01FC0000L      // pointer index 0x7F, page index 0..63
  private val PGN    = 64
  private def fwdPages  = (0 until PGN).map(i => (PGBASE + i * 0x1000L, 0x500L + i))
  private def revPages  = (0 until PGN).map(i => (PGBASE + i * 0x1000L, 0x500L + (PGN - 1 - i)))

  private val writeAllThenReadAll = Seq(
    f"move.l #0x$PGBASE%x,%%a0",
    f"move.l #${PGN - 1},%%d7",
    "move.l #0x10000001,%d6",
    "1: move.l %d6,(%a0)",
    "add.l #0x01010101,%d6",
    "lea 0x1000(%a0),%a0",
    "dbra %d7,1b",
    f"move.l #0x$PGBASE%x,%%a0",
    f"move.l #${PGN - 1},%%d7",
    "moveq #0,%d0",
    "2: add.l (%a0),%d0",
    "lea 0x1000(%a0),%a0",
    "dbra %d7,2b").mkString(" ; ")
  private val writeAllThenReadAllN = 3 + PGN * 4 + 3 + PGN * 3

  test("addr32: 64 mapped pages above 16 MB, ascending PPNs, write-all then read-all", VerilatorTest) {
    h.runLockStep("addr32-mmu-64p-fwd", writeAllThenReadAll, nInstr = writeAllThenReadAllN,
      mmuMap = Some(fwdPages.head), extraMmuPages = fwdPages.tail, maxCycles = 400000)
  }

  test("addr32: 64 mapped pages above 16 MB, DESCENDING PPNs (VA order != PA order)", VerilatorTest) {
    h.runLockStep("addr32-mmu-64p-rev", writeAllThenReadAll, nInstr = writeAllThenReadAllN,
      mmuMap = Some(revPages.head), extraMmuPages = revPages.tail, maxCycles = 400000)
  }

  test("addr32: 64 mapped pages, read-modify-write per page (write-hit M refresh)", VerilatorTest) {
    val src = Seq(
      f"move.l #0x$PGBASE%x,%%a0",
      f"move.l #${PGN - 1},%%d7",
      "move.l #0x10000001,%d6",
      "1: move.l %d6,(%a0)",
      "add.l #0x01010101,%d6",
      "lea 0x1000(%a0),%a0",
      "dbra %d7,1b",
      f"move.l #0x$PGBASE%x,%%a0",
      f"move.l #${PGN - 1},%%d7",
      "2: move.l (%a0),%d1",
      "addq.l #1,%d1",
      "move.l %d1,(%a0)",
      "lea 0x1000(%a0),%a0",
      "dbra %d7,2b",
      f"move.l #0x$PGBASE%x,%%a0",
      f"move.l #${PGN - 1},%%d7",
      "moveq #0,%d0",
      "3: add.l (%a0),%d0",
      "lea 0x1000(%a0),%a0",
      "dbra %d7,3b").mkString(" ; ")
    h.runLockStep("addr32-mmu-64p-rmw", src,
      nInstr = 3 + PGN * 4 + 2 + PGN * 5 + 3 + PGN * 3,
      mmuMap = Some(fwdPages.head), extraMmuPages = fwdPages.tail, maxCycles = 600000)
  }

  // ── 15. the same 64-page walk with an ODD, cache-line-straddling access in every
  //      page: the access itself is at page offset 0xFFE, so a LONG there also
  //      CROSSES INTO THE NEXT PAGE -- a two-translation split whose second half
  //      lands on a page with an unrelated PPN. Under 24-bit mode this shape exists
  //      too, but never with the high VA / non-identity PPN pair.
  test("addr32: page-crossing LONG in each of 63 mapped high pages", VerilatorTest) {
    val src = Seq(
      f"move.l #0x${PGBASE + 0xFFE}%x,%%a0",
      f"move.l #${PGN - 2},%%d7",
      "move.l #0x10000001,%d6",
      "1: move.l %d6,(%a0)",
      "add.l #0x01010101,%d6",
      "lea 0x1000(%a0),%a0",
      "dbra %d7,1b",
      f"move.l #0x${PGBASE + 0xFFE}%x,%%a0",
      f"move.l #${PGN - 2},%%d7",
      "moveq #0,%d0",
      "2: add.l (%a0),%d0",
      "lea 0x1000(%a0),%a0",
      "dbra %d7,2b").mkString(" ; ")
    h.runLockStep("addr32-mmu-64p-crosspage", src,
      nInstr = 3 + (PGN - 1) * 4 + 3 + (PGN - 1) * 3,
      mmuMap = Some(fwdPages.head), extraMmuPages = fwdPages.tail, maxCycles = 600000)
  }

  // ── 16. THE BOARD'S EXACT EXCEPTION POSTURE. In 24-bit mode this machine runs
  //      VBR = 0; in 32-bit mode Mac OS relocates the vector table and the live
  //      value is VBR = 0x01FF6978 with an ODD supervisor stack at A7 = 0x01FF6A7A.
  //      A NON-ZERO VBR is therefore something only 32-bit mode produces, and no
  //      pre-existing lock-step test puts VBR above 0x3000. These take the very
  //      A-line trap the failing trace shows (_A9C9), through a vector slot stored
  //      at run time into high RAM, on an odd high stack, at every SSP alignment.
  private val HiVbr = 0x01FF6978L

  private def hiVbrTrap(tag: String, ssp: Long, handlerBody: String, nInstr: Int): Unit =
    h.runLockStep(tag, Seq(
      f"move.l #0x$HiVbr%x,%%d0",
      "movec %d0,%vbr",
      f"move.l #0x$ssp%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x${HiVbr + 0x28}%x",       // vector 10 (line-A) slot
      "move.l #0x11223344,%d5",
      ".short 0xa9c9",                          // _A9C9 -- the trap the bomb trace shows
      "moveq #7,%d3",
      "bra stop",
      "handler: " + handlerBody,
      "stop: bra stop").mkString(" ; "), nInstr = nInstr, maxCycles = 40000)

  // stacked-PC fixup handler: read the frame's PC long off an ODD high stack, bump it
  // past the 2-byte trap word, write it back, RTE.
  private val fixupHandler = "move.l 2(%a7),%d0 ; addq.l #2,%d0 ; move.l %d0,2(%a7) ; moveq #1,%d2 ; rte"

  for (d <- 0 until 16) {
    test(f"addr32: high VBR + A-line trap + RTE, SSP = 0x01FF6A70+$d%d", VerilatorTest) {
      hiVbrTrap(f"addr32-hivbr-ssp$d%d", 0x01FF6A70L + d, fixupHandler, nInstr = 13)
    }
  }

  // the same, with the handler saving and restoring the whole register file onto the
  // ODD high stack first -- a 60-byte multi-access spanning four 16-byte cache lines,
  // which is what the ROM's own SysError entry does.
  test("addr32: high VBR + A-line trap, MOVEM save/restore on the odd high stack", VerilatorTest) {
    hiVbrTrap("addr32-hivbr-movem", 0x01FF6A7AL,
      "movem.l %d0-%d7/%a0-%a6,-(%a7) ; movem.l (%a7)+,%d0-%d7/%a0-%a6 ; " + fixupHandler,
      nInstr = 15)
  }

  // ── 17. THE RTE-CCR CHECK, RE-RUN IN THE BOARD'S 32-BIT POSTURE. `1c4daf25`
  //      fixed "an IRQ after a MOVE to memory RTEs with the HANDLER's flags"; the
  //      A-trap dispatch epilogue the failing trace implicates returns its result
  //      exactly that way (D0 = result, CCR = flags of D0, then the caller branches),
  //      and a wrong branch there IS a bad-patch-header verdict. The existing checks
  //      run with VBR = 0 and an even low SSP; these re-run them with the live board
  //      values -- VBR = 0x01FF6978, an ODD supervisor stack at 0x01FF6A7A -- at
  //      four SSP alignments.
  for (d <- Seq(0, 1, 7, 10)) {
    test(f"addr32: RTE restores the CCR across an IRQ, high VBR + odd high SSP +$d%d", VerilatorTest) {
      val ssp = 0x01FF6A70L + d
      val src = Seq(
        f"move.l #0x$HiVbr%x,%%d0",
        "movec %d0,%vbr",
        f"move.l #0x$ssp%x,%%a7",
        "move.l #handler,%d0",
        f"move.l %%d0,0x${HiVbr + 0x64}%x",     // level-1 autovector slot
        "move.w #0x2000,%sr",
        "moveq #0,%d1",
        "tst.l %d1",
        "nop",
        "nop",
        "beq.s ok",
        "move.l #0xbad,%d2",
        "bra.s end",
        "ok: move.l #0x600d,%d2",
        "end: bra.s end",
        "handler: moveq #1,%d5",
        "rte").mkString(" ; ")
      val plain = m68k040.oracle.Musashi.assembleAndTrace(src, initialSr = Some(0x2700)) match {
        case Right(v)  => v
        case Left(err) => fail(s"Musashi.assembleAndTrace failed: ${err.reason}")
      }
      val beqPc = plain(10).pc   // `beq.s ok` is the 11th executed instruction
      h.runIrqLockStep(f"addr32-hivbr-rteccr-$d%d", src, nInstr = 16,
        irqEvents = Seq((beqPc, 1)), initialSr = 0x2700, maxCycles = 40000)
    }
  }

  // ── 18. WRITE CODE, CPUSH, THEN EXECUTE IT -- the patch installer's own pattern ──
  //
  // NO test in this repository has ever backed the I-cache and the D-cache with the
  // SAME memory: `runLockStep` gives the I-side its own `SparseMemory` through
  // `attachProgram` and the D-side an independent `BehavioralMemAgent`, and every
  // bespoke doSim in `ExecuteLockStepSpec` does the same. Self-modifying code is
  // therefore STRUCTURALLY unobservable in this harness -- and "store instruction
  // bytes through the D-cache, CPUSH, then fetch them" is exactly what the System
  // patch installer does, and exactly the class of transaction v1 (with no copyback
  // D-cache, hence no dirty line to push) never issues at all.
  //
  // These wire ONE `SparseMemory` to both ports and run the pattern for real:
  //
  //   jsr (a0)                  -- executes the PRE-IMAGE, so the I-cache caches it
  //   move.l %d3,%d6            -- witness: proves the pre-image really ran
  //   move.l #<new code>,(a0)   -- overwrite through the D-cache (dirty, copyback)
  //   cpush{a,l,p} bc           -- push the dirty line, invalidate the I-cache
  //   jsr (a0)                  -- MUST now execute the NEW bytes
  //
  // A stale fetch leaves D3 at the pre-image's value instead of the new one, and the
  // program still terminates cleanly, so the failure signal is a wrong VALUE rather
  // than a crash. The low-address variant is the 24-bit-mode control.
  private val StaleWord = 0x76114E75L    // moveq #0x11,%d3 ; rts
  private val FreshWord = 0x765A4E75L    // moveq #0x5A,%d3 ; rts

  private def smcRun(tag: String, codeVa: Long, cpush: String): Unit = {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val src = Seq(
      "move.l #0x00120000,%a7",
      f"move.l #0x$codeVa%x,%%a0",
      "jsr (%a0)",                                   // runs the PRE-IMAGE  -> d3 = 0x11
      "move.l %d3,%d6",                              // witness
      f"move.l #0x$FreshWord%08x,(%%a0)",            // overwrite through the D-cache
      cpush,
      "jsr (%a0)",                                   // must run the NEW code -> d3 = 0x5A
      "stop: bra stop").mkString(" ; ")
    val image = m68k040.oracle.ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$tag] assemble failed: ${err.reason}")
    }
    var d3 = -1L; var d6 = -1L
    h.compiledDut.doSim(s"$tag-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      // ONE memory behind BOTH cache ports. `loadProgramIFetch` is a plain linear
      // byte write (architectural big-endian order, byte at the lowest address =
      // opword MSB), which is the same layout a real `move.l` store produces through
      // `DcacheByteLane.storeData` -- so the two ports agree byte-for-byte.
      val mem = SparseMemory()
      m68k040.sim.AxiMemModel.loadProgramIFetch(mem, loadAddr, image.bytes)
      m68k040.sim.AxiMemModel.fillIFetchRunAheadGuard(
        mem, loadAddr + image.bytes.length, m68k040.sim.AxiMemModel.LockStepRunAheadGuardWords)
      // the PRE-IMAGE routine, plus a `bra .-0` fence so a run-ahead prefetch past the
      // rts can never execute PRNG fill.
      for (i <- 0 until 4) mem.write(codeVa + i, ((StaleWord >> (8 * (3 - i))) & 0xff).toByte)
      m68k040.sim.AxiMemModel.fillIFetchRunAheadGuard(mem, codeVa + 4, 1024)

      m68k040.sim.AxiMemModel.attachReadOnly(dut.icache.logic.axi, cd, sharedMem = mem)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd, sharedMem = mem)

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      dut.intCtrl.logic.iplIn #= 0; dut.intCtrl.logic.iackAvec #= true; dut.intCtrl.logic.iackVector #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.rob.logic.exc.ss.isp  #= 0x00120000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L      // DE|IE
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00120000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      cd.waitSampling(20000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d6 = probe.readArch(6)
    }
    // NOT-VACUOUS first: if the pre-image never ran, the harness is wrong, not the RTL.
    assert(d6 == 0x11L,
      f"[$tag] VACUOUS: the PRE-IMAGE routine at 0x$codeVa%08x never ran (D6 = 0x$d6%08x, expected 0x11) " +
        "-- the shared-memory attach or the boot sequence is wrong, not the cache")
    assert(d3 == 0x5AL,
      f"[$tag] STALE INSTRUCTION FETCH: after storing new code to 0x$codeVa%08x and `$cpush`, " +
        f"the fetch still executed the OLD bytes (D3 = 0x$d3%08x, expected 0x5A, pre-image value 0x11)")
  }

  test("addr32: store code at 0x01FF7000, CPUSHA BC, execute it", VerilatorTest) {
    smcRun("smc-hi-cpusha", 0x01FF7000L, "cpusha %bc")
  }
  test("addr32: store code at 0x01FF7000, CPUSHL BC (line), execute it", VerilatorTest) {
    smcRun("smc-hi-cpushl", 0x01FF7000L, "cpushl %bc,(%a0)")
  }
  test("addr32: store code at 0x01FF7000, CPUSHP BC (page), execute it", VerilatorTest) {
    smcRun("smc-hi-cpushp", 0x01FF7000L, "cpushp %bc,(%a0)")
  }
  // 24-bit-mode controls: the identical pattern below 16 MB.
  test("addr32 control: store code at 0x00007000, CPUSHA BC, execute it", VerilatorTest) {
    smcRun("smc-lo-cpusha", 0x00007000L, "cpusha %bc")
  }
  test("addr32 control: store code at 0x00007000, CPUSHL BC (line), execute it", VerilatorTest) {
    smcRun("smc-lo-cpushl", 0x00007000L, "cpushl %bc,(%a0)")
  }

  // ── 19. THE EXCEPTION SEQUENCER MUST TRANSLATE ─────────────────────────────────
  //
  // Until 2026-09-09 every entry-frame push, every RTE frame pop and every VBR vector
  // fetch used PA = VA (ExceptionUnit `ldoPaddr := ldoVaddr`, `driveStoreNoXlate`), with
  // the file's own comment calling a real translation "a fast-follow". That is inert
  // under an identity map and SILENT DATA CORRUPTION under any other: the frame is
  // written to the wrong physical page and the handler address is read out of memory the
  // OS never wrote. It is also, on a 68040, unobservable -- no fault is raised.
  //
  // This maps ONE data page NON-IDENTITY (VA 0x01FF6000 -> PA 0x00700000) and puts BOTH
  // the vector table (VBR = 0x01FF6000) and the supervisor stack (A7 = 0x01FF6F00) in
  // it, which is exactly the 32-bit-mode posture the board reports. Then it takes the
  // _A9C9 line-A trap and RTEs.
  //
  // Both untranslated destinations are PRE-POISONED so the unfixed behaviour is
  // deterministic rather than dependent on SparseMemory's PRNG fill:
  //   * PA 0x01FF6028 (the vector slot AT ITS VIRTUAL ADDRESS) holds 0xDEAD0000, so an
  //     untranslated vector fetch redirects to 0xDEAD0000 and the handler never runs;
  //   * PA 0x01FF6EF8..0x01FF6EFF (the frame base at its virtual address) holds 0xEE
  //     bytes, so an untranslated frame push overwrites them.
  test("addr32: exception frame + vector fetch translate through the DTLB (non-identity map)", VerilatorTest) {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val PageVa   = 0x01FF6000L
    val PagePpn  = 0x00700L
    val PagePa   = PagePpn << 12                 // 0x00700000
    val VecSlotVa = PageVa + 10 * 4              // vector 10 (line-A) = 0x01FF6028
    val StackVa   = PageVa + 0xF00               // A7 = 0x01FF6F00
    val FrameVa   = StackVa - 8                  // format-$0 frame base = 0x01FF6EF8
    def pa(va: Long) = PagePa | (va & 0xFFFL)

    val src = Seq(
      f"move.l #0x$PageVa%x,%%d0",
      "movec %d0,%vbr",
      f"move.l #0x$StackVa%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x$VecSlotVa%x",
      "moveq #0,%d3",
      ".short 0xa9c9",                            // line-A trap -> vector 10
      "moveq #33,%d4",                            // runs only if the RTE resumed here
      "bra stop",
      "handler: move.l 2(%a7),%d0",
      "addq.l #2,%d0",
      "move.l %d0,2(%a7)",
      "moveq #51,%d3",
      "rte",
      "stop: bra stop").mkString(" ; ")
    val image = m68k040.oracle.ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"assemble failed: ${err.reason}")
    }

    var d3 = -1L; var d4 = -1L
    var vecWordAtPa = -1; var guardIntact = true; var poisonIntact = true
    h.compiledDut.doSim(s"addr32-exc-xlate-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Page table: the code identity-mapped (so I-fetch works), the ONE data page
      // deliberately NOT.
      h.buildMmuTable(dmem, PageVa, PagePpn)
      // Poison both untranslated destinations.
      for (i <- 0 until 4) dmem.pokeByte(VecSlotVa + i, Seq(0xDE, 0xAD, 0x00, 0x00)(i))
      for (i <- 0 until 8) dmem.pokeByte(FrameVa + i, 0xEE)

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      dut.intCtrl.logic.iplIn #= 0; dut.intCtrl.logic.iackAvec #= true; dut.intCtrl.logic.iackVector #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= h.MMU_ROOT
      dut.ctrl.logic.srp #= h.MMU_ROOT
      dut.rob.logic.exc.ss.isp  #= 0x00120000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00120000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      cd.waitSampling(40000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d4 = probe.readArch(4)
      // The frame's format/vector word sits at frameBase+6 and must read 0x0028 for
      // vector 10 (vec << 2 = 40). Read it at the TRANSLATED physical address.
      vecWordAtPa = (dmem.peekByte(pa(FrameVa) + 6) << 8) | dmem.peekByte(pa(FrameVa) + 7)
      guardIntact = (0 until 8).forall(i => dmem.peekByte(FrameVa + i) == 0xEE)
      poisonIntact = Seq(0xDE, 0xAD, 0x00, 0x00).zipWithIndex
                       .forall { case (b, i) => dmem.peekByte(VecSlotVa + i) == b }
    }
    // 1. the handler ran => the VECTOR FETCH read the slot at its TRANSLATED address.
    assert(d3 == 51L,
      f"UNTRANSLATED VECTOR FETCH: the handler never ran (D3 = 0x$d3%08x, expected 51). " +
        "The fetch read VBR+40 as a physical address and redirected to the 0xDEAD0000 poison.")
    // 2. the RTE resumed at the instruction after the trap => the frame POP translated.
    assert(d4 == 33L,
      f"UNTRANSLATED RTE POP: execution did not resume after the trap (D4 = 0x$d4%08x, expected 33)")
    // 3. the frame landed at the TRANSLATED physical address...
    assert(vecWordAtPa == 0x0028,
      f"UNTRANSLATED FRAME PUSH: the format/vector word is not at the translated PA " +
        f"0x${pa(FrameVa) + 6}%08x (read 0x$vecWordAtPa%04x, expected 0x0028)")
    // 4. ...and NOT at the virtual address treated as physical.
    assert(guardIntact,
      f"UNTRANSLATED FRAME PUSH: the 0xEE guard at PA 0x$FrameVa%08x -- the frame base's " +
        "VIRTUAL address -- was overwritten, so the push used PA = VA")
    assert(poisonIntact,
      f"the 0xDEAD0000 poison at PA 0x$VecSlotVa%08x was modified; the test's own " +
        "assumptions about what maps where are wrong")
  }

  // ── 20. FSAVE / FRESTORE TRANSLATION FAULT MUST BE AN ACCESS FAULT, NOT A HALT ──
  //
  // F_XWAIT and F_RXWAIT used to answer a DTLB fault on an FSAVE frame push or an
  // FRESTORE header read with `fsXlateFault := True; goto(F_HALT)` -- they HALTED THE
  // PROCESSOR. FSAVE and FRESTORE are ordinary instructions and halting is never the
  // correct 68040 response to one faulting: a non-resident state-frame page is an ACCESS
  // FAULT the OS pages in and retries. 24-bit mode never reaches it because everything
  // it touches is low and already resident; 32-bit mode does `frestore (%a7)+` on a high
  // supervisor stack on every FPU context switch.
  //
  // The state-frame pointer is aimed at an UNMAPPED high page while the supervisor stack
  // and vector table stay in a mapped one -- so the fault is on the instruction's own
  // access, and the vector-2 entry it raises can be stacked successfully. The handler
  // does NOT RTE (that would re-run the faulting instruction and fault again); it sets a
  // marker and stops, which is enough to prove the core raised the exception and kept
  // running.
  private def fsaveFaultRun(tag: String, insn: String, marker: Int): Unit = {
    val loadAddr = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val PageVa   = 0x01FF6000L      // MAPPED   -> PA 0x00700000 (stack + vector table)
    val PagePpn  = 0x00700L
    val UnmapVa  = 0x01FF5000L      // NOT mapped at all -> the walk faults NON_RESIDENT
    val src = Seq(
      f"move.l #0x$PageVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x${PageVa + 0xF00}%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x${PageVa + 8}%x",          // vector 2 (access fault) slot
      f"move.l #0x${UnmapVa + 0x800}%x,%%a0",
      "moveq #0,%d3",
      insn,                                       // faults: the page has no descriptor
      "moveq #99,%d4",                            // must NOT be reached
      "bra stop",
      f"handler: moveq #$marker,%%d3",
      "bra stop",
      "stop: bra stop").mkString(" ; ")
    val image = m68k040.oracle.ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$tag] assemble failed: ${err.reason}")
    }
    var d3 = -1L; var d4 = -1L
    var halted = false; var fsFault = false; var dblFault = false; var vecWord = -1
    h.compiledDut.doSim(s"$tag-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      h.buildMmuTable(dmem, PageVa, PagePpn)     // maps PageVa + the 8 code pages ONLY
      // ...and make UnmapVa's leaf descriptor EXPLICITLY invalid (PDT = 00). Writing it
      // is not optional: `buildMmuTable` only fills the entries it needs, and the D-side
      // `SparseMemory` PRNG-fills everything else -- so an untouched descriptor slot
      // reads as a random, usually "resident" descriptor with a random PPN, and the walk
      // succeeds with a garbage translation instead of faulting.
      val unmapPageIdx = ((UnmapVa >> 12) & 0x3f).toInt
      for (i <- 0 until 4) dmem.pokeByte(h.MMU_PAGT + unmapPageIdx * 4 + i, 0)
      cd.onSamplings {
        if (dut.rob.logic.coreHaltedIn.toBoolean) halted = true
        if (dut.rob.logic.exc.fsXlateFault.toBoolean) fsFault = true
        if (dut.rob.logic.exc.dblFault.toBoolean) dblFault = true
      }
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      dut.intCtrl.logic.iplIn #= 0; dut.intCtrl.logic.iackAvec #= true; dut.intCtrl.logic.iackVector #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true; cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= h.MMU_ROOT; dut.ctrl.logic.srp #= h.MMU_ROOT
      dut.rob.logic.exc.ss.isp  #= 0x00120000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00120000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      cd.waitSampling(40000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d4 = probe.readArch(4)
      // format-$7 frame at (mapped) SSP-60; its format/vector word is at +6 and must
      // read 0x7008 -- format $7, vector 2 (2 << 2 = 8).
      val frameBasePa = 0x00700000L | ((0xF00L - 60) & 0xFFFL)
      vecWord = (dmem.peekByte(frameBasePa + 6) << 8) | dmem.peekByte(frameBasePa + 7)
    }
    assert(!halted,
      f"[$tag] THE CORE HALTED on a faulting ordinary instruction " +
        f"(fsXlateFault=$fsFault dblFault=$dblFault). FSAVE/FRESTORE must raise an " +
        "access fault the OS can handle, not stop the processor.")
    assert(!fsFault, s"[$tag] fsXlateFault was raised; the halt path is still taken")
    assert(d3 == marker.toLong,
      f"[$tag] the vector-2 handler never ran (D3 = 0x$d3%08x, expected $marker; " +
        f"D4 = 0x$d4%08x, frame format/vector word = 0x$vecWord%04x, halted=$halted " +
        f"fsXlateFault=$fsFault dblFault=$dblFault)")
    assert(d4 != 99L, f"[$tag] execution fell through the faulting instruction (D4 = 0x$d4%08x)")
    assert(vecWord == 0x7008,
      f"[$tag] the stacked frame is not a format-7 vector-2 access fault " +
        f"(format/vector word = 0x$vecWord%04x, expected 0x7008)")
  }

  test("addr32: an FSAVE frame-push translation fault raises vector 2 and keeps running", VerilatorTest) {
    fsaveFaultRun("fsave-xlate-fault", "fsave -(%a0)", 41)
  }
  test("addr32: an FRESTORE header-read translation fault raises vector 2 and keeps running", VerilatorTest) {
    fsaveFaultRun("frestore-xlate-fault", "frestore (%a0)+", 42)
  }

  // ── 21. THE SAME, WITH 8 KB PAGES -- the granule the board actually runs ────────
  //
  // The machine under test has TC = 0x0000C000, i.e. TCR.P = 1, 8 KB pages. `excPaOf`
  // therefore takes its 8 KB arm -- `excPpn(19 downto 1) ## va(12 downto 0)` -- on every
  // real exception, and test 19 above exercises only the 4 KB arm, because
  // `buildMmuTable`/`mapPage` are 4 KB-only. An 8 KB-mode bug would put every exception
  // frame 4 KB away from where it belongs.
  //
  // Both the vector table and the frame are deliberately placed in the UPPER half of
  // their 8 KB page (offsets 0x1028 and 0x1EF8), which is exactly where the two formulas
  // disagree: the 4 KB assembly would drop VA[12] and land them 0x1000 lower. Those two
  // wrong destinations are the ones that get poisoned.
  test("addr32: exception frame + vector fetch translate with 8 KB pages (TC.P=1)", VerilatorTest) {
    val loadAddr  = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val PageVa    = 0x01FF6000L                 // 8 KB-aligned
    val PagePa    = 0x00700000L                 // 8 KB-aligned
    val VbrVa     = PageVa + 0x1000             // UPPER half -> VA[12] = 1
    val VecSlotVa = VbrVa + 10 * 4              // 0x01FF7028, page offset 0x1028
    val StackVa   = PageVa + 0x1F00             // 0x01FF7F00, also upper half
    val FrameVa   = StackVa - 8                 // 0x01FF7EF8, page offset 0x1EF8
    def pa8k(va: Long) = PagePa | (va & 0x1FFFL)
    def pa4k(va: Long) = PagePa | (va & 0x0FFFL)   // what a 4 KB assembly would produce

    val src = Seq(
      f"move.l #0x$VbrVa%x,%%d0", "movec %d0,%vbr",
      f"move.l #0x$StackVa%x,%%a7",
      "move.l #handler,%d1",
      f"move.l %%d1,0x$VecSlotVa%x",
      "moveq #0,%d3",
      ".short 0xa9c9",
      "moveq #33,%d4",
      "bra stop",
      "handler: move.l 2(%a7),%d0", "addq.l #2,%d0", "move.l %d0,2(%a7)",
      "moveq #51,%d3", "rte",
      "stop: bra stop").mkString(" ; ")
    val image = m68k040.oracle.ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"assemble failed: ${err.reason}")
    }

    var d3 = -1L; var d4 = -1L; var vecWordAt8k = -1
    var guard4kIntact = true; var poison4kIntact = true
    h.compiledDut.doSim(s"addr32-exc-xlate-8k-${System.nanoTime()}") { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      h.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)

      // ── hand-built 8 KB page tables: root VA[31:25], pointer VA[24:18], page VA[17:13]
      def poke32BE(a: Long, w: Long): Unit =
        for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      def map8k(va: Long, phys: Long, leaf: Long): Unit = {
        poke32BE(h.MMU_ROOT + ((va >> 25) & 0x7f) * 4, (h.MMU_PTRT & 0xfffffff0L) | 0x3L)
        poke32BE(h.MMU_PTRT + ((va >> 18) & 0x7f) * 4, (leaf & 0xfffffff0L) | 0x3L)
        poke32BE(leaf + ((va >> 13) & 0x1f) * 4, (phys & 0xffffe000L) | 0x1L)
      }
      map8k(PageVa, PagePa, h.MMU_PAGT)                                  // data, non-identity
      for (i <- 0 until 4)                                               // code, identity
        map8k(loadAddr + i * 0x2000L, loadAddr + i * 0x2000L, h.MMU_PAGT2)

      // Poison exactly where a 4 KB assembly would go.
      for (i <- 0 until 4) dmem.pokeByte(pa4k(VecSlotVa) + i, Seq(0xDE, 0xAD, 0x00, 0x00)(i))
      for (i <- 0 until 8) dmem.pokeByte(pa4k(FrameVa) + i, 0xEE)

      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      dut.intCtrl.logic.iplIn #= 0; dut.intCtrl.logic.iackAvec #= true; dut.intCtrl.logic.iackVector #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true; cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.ctrl.logic.mmuEnable  #= true
      dut.ctrl.logic.pageSize8K #= true          // TCR.P = 1, the board's own posture
      dut.ctrl.logic.urp #= h.MMU_ROOT; dut.ctrl.logic.srp #= h.MMU_ROOT
      dut.rob.logic.exc.ss.isp  #= 0x00120000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00120000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      cd.waitSampling(40000)
      val probe = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      d3 = probe.readArch(3); d4 = probe.readArch(4)
      vecWordAt8k = (dmem.peekByte(pa8k(FrameVa) + 6) << 8) | dmem.peekByte(pa8k(FrameVa) + 7)
      guard4kIntact  = (0 until 8).forall(i => dmem.peekByte(pa4k(FrameVa) + i) == 0xEE)
      poison4kIntact = Seq(0xDE, 0xAD, 0x00, 0x00).zipWithIndex
                         .forall { case (b, i) => dmem.peekByte(pa4k(VecSlotVa) + i) == b }
    }
    assert(d3 == 51L,
      f"8 KB-mode VECTOR FETCH went to the wrong physical address: the handler never ran " +
        f"(D3 = 0x$d3%08x, expected 51). A 4 KB page-offset assembly reads " +
        f"0x${pa4k(VecSlotVa)}%08x instead of 0x${pa8k(VecSlotVa)}%08x.")
    assert(d4 == 33L, f"8 KB-mode RTE did not resume after the trap (D4 = 0x$d4%08x)")
    assert(vecWordAt8k == 0x0028,
      f"8 KB-mode FRAME PUSH is not at 0x${pa8k(FrameVa) + 6}%08x " +
        f"(read 0x$vecWordAt8k%04x, expected 0x0028)")
    assert(guard4kIntact,
      f"8 KB-mode FRAME PUSH landed at the 4 KB address 0x${pa4k(FrameVa)}%08x -- VA[12] " +
        "was dropped from the page offset")
    assert(poison4kIntact, "the 4 KB-address poison was modified; the test's assumptions are wrong")
  }
}
