package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** LIVENESS reproducers for out-of-order LS issue (`FUZZ_LS_OOO=1`).
  *
  * ONE MECHANISM, SEVERAL SHAPES. The LS EU is a single in-order pipe (S1 -> T -> TX -> P3
  * -> P4). Every stage that can WAIT was written for in-order issue, where the op resident
  * in a stage is always the oldest LS op in flight, so whatever it waits for can only be
  * something OLDER than itself -- which is ahead of it and progresses. Relaxed issue puts a
  * YOUNGER op in front of an OLDER one; if the younger op then waits on anything whose
  * release needs the older op to complete, the older op is stuck behind it: a circular wait.
  * The P4 park (2026-09-26) broke exactly one instance (an inhibited op waiting to become the
  * ROB head). These are the others:
  *
  *   A  STORE bypass -> inhibited-store serialisation. An inhibited STORE passes an older
  *      unready load; a younger cacheable load then waits in P4 for the store to drain
  *      (`fwdSerial` / `olderInhibitedStore`), the store drains only at the ROB head, and
  *      the head is the unready load, now issued and stuck in P3 behind P4.
  *   B  STORE bypass -> SQ capacity. Nine cacheable stores pass an older unready load and
  *      fill the 8-entry SQ; none can commit before that load retires; the ninth waits in
  *      P3 for SQ space with the load behind it.
  *   C  PARK OVERFLOW. Five inhibited loads pass an older unready load: four park, the
  *      fifth waits in P4 for the ROB head with the load behind it.
  *   D  SPLIT inhibited bypasser. A line-crossing device long is never parked (it needs two
  *      adjacent slots), so it waits in P4 for the head with the older load behind it.
  *   E  STORE bypass -> late store data (SHIPPING LSU: `earlyStoreAddress`). A store's
  *      ADDRESS issues past an older unready load whose value is the store's DATA; a younger
  *      load to the same address waits in P4 for that data; the producer is behind it.
  *
  * Every program's older unready load gets its address from a DIVIDE, so it is unready for
  * tens of cycles while everything after it is ready -- the relaxed select is forced to
  * reorder. Pass criterion: the sentinel. With LS-OoO OFF every one of them passes, which is
  * the control; `olderBehindStuckP4` (see `LsLivenessMonitor`) is printed so a run that never
  * built the shape is visible as such. */
class LsOooLivenessSpec extends AnyFunSuite {
  private val lsOoo = sys.env.get("FUZZ_LS_OOO").contains("1")
  private val Timeout = 60000L

  private val passTail =
    """
      |    lea     0xFFFF0000, %a6
      |    move.l  #0xC0FFEE00, %d0
      |    move.l  %d0, (%a6)
      |_halt:
      |    bra     _halt
      |_fail:
      |    lea     0xFFFF0000, %a6
      |    move.l  %d0, (%a6)
      |    bra     _halt
      |""".stripMargin

  /** Common prologue. a0 = device (INHIBITED under DTT1), a3 = cacheable scratch (warmed),
    * d7 = loop counter. `slowA1` makes a1 = 0x00089000 through a divide. */
  private val prologue =
    """    .text
      |_start:
      |    lea     0xFFFF0100, %a0
      |    lea     0x00090000, %a3
      |    move.l  #0x11111111, (%a3)
      |    move.l  #0x22222222, 4(%a3)
      |    move.l  #0x33333333, 0x00089000
      |    moveq   #7, %d7
      |""".stripMargin
  private val slowA1 =
    """    move.l  #0x00009000, %d5
      |    divu.w  #1, %d5
      |    divu.w  #1, %d5
      |    and.l   #0xffff, %d5
      |    add.l   #0x00080000, %d5
      |    movea.l %d5, %a1
      |""".stripMargin

  private def run(name: String, body: String): Unit = {
    val src = prologue + body + passTail
    val outcome = PortedTestRunner.run(name, src, Timeout,
      cachePosture = CachePosture.ForceCacheableCopyback, liveness = true)
    val mon = PortedTestRunner.lastLiveness
    info(mon.summary)
    println(s"[ls-ooo-liveness] $name FUZZ_LS_OOO=$lsOoo outcome=$outcome ${mon.summary}")
    assert(outcome == PortedPass,
      s"$name: $outcome\n${PortedTestRunner.lastLivenessFailure.getOrElse("(no liveness trip)")}")
  }

  test("LS-OoO liveness A: inhibited store passes an unready load, younger load serialises on it",
       VerilatorTest) {
    run("lsooo_live_a_inhib_store", "_la:\n" + slowA1 +
      """    move.l  (%a1), %d1              | older load: address from the divide
        |    move.l  %d7, 4(%a0)             | inhibited STORE, ready -> may pass it
        |    move.l  (%a3), %d2              | cacheable load, ready -> waits on the store
        |    dbf     %d7, _la
        |""".stripMargin)
  }

  test("LS-OoO liveness B: nine stores pass an unready load and fill the SQ", VerilatorTest) {
    run("lsooo_live_b_sq_full", "_lb:\n" + slowA1 +
      """    move.l  (%a1), %d1
        |    move.l  %d7, (%a3)
        |    move.l  %d7, 4(%a3)
        |    move.l  %d7, 8(%a3)
        |    move.l  %d7, 12(%a3)
        |    move.l  %d7, 16(%a3)
        |    move.l  %d7, 20(%a3)
        |    move.l  %d7, 24(%a3)
        |    move.l  %d7, 28(%a3)
        |    move.l  %d7, 32(%a3)
        |    move.l  %d7, 36(%a3)
        |    dbf     %d7, _lb
        |""".stripMargin)
  }

  test("LS-OoO liveness C: five inhibited loads pass an unready load (park overflow)",
       VerilatorTest) {
    run("lsooo_live_c_park_full", "_lc:\n" + slowA1 +
      """    move.l  (%a1), %d1
        |    move.l  (%a0), %d2
        |    move.l  4(%a0), %d3
        |    move.l  8(%a0), %d4
        |    move.l  12(%a0), %d6
        |    move.l  16(%a0), %d2
        |    move.l  20(%a0), %d3
        |    dbf     %d7, _lc
        |""".stripMargin)
  }

  test("LS-OoO liveness D: a line-crossing inhibited long passes an unready load",
       VerilatorTest) {
    run("lsooo_live_d_split_inhib", "_ld:\n" + slowA1 +
      """    move.l  (%a1), %d1
        |    move.l  14(%a0), %d2            | 0xFFFF010E: crosses a 16-byte line
        |    dbf     %d7, _ld
        |""".stripMargin)
  }

  test("LS-OoO liveness E: a store's address passes the load that produces its data",
       VerilatorTest) {
    run("lsooo_live_e_late_data", "    move.l  #0xBAD0000E, %d0\n_le:\n" + slowA1 +
      """    move.l  (%a1), %d1              | older load, unready; its value is the store's data
        |    move.l  %d1, 8(%a3)             | store: address ready, data pending
        |    move.l  8(%a3), %d2             | younger load of the same address
        |    cmp.l   %d1, %d2
        |    bne     _fail
        |    dbf     %d7, _le
        |""".stripMargin)
  }
}
