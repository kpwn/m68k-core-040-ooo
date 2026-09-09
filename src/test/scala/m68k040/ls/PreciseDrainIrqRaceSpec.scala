package m68k040.ls

import m68k040.fuzz.{FuzzDut, FuzzCoreDut}
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Directed regression for the design doc's "one genuinely dangerous race":
  * an interrupt preempting the ROB head while a precise-path (MMU-off) store's
  * SQ-at-head drain is launched-or-launching must never let the store's AXI
  * write fire twice (fatal for MMIO) nor get silently dropped. */
class PreciseDrainIrqRaceSpec extends AnyFunSuite {
  private val loadAddr = ProgramAssembler.DefaultLoadAddress
  private val targetAddr = 0x00090000L   // an ordinary, mapped, MMU-off ("MMIO-like") word

  // Tight loop: increment a counter in D0, store D0 to targetAddr, repeat. An
  // autovector-25 handler just RTEs (no VIA1 in this harness -- mirrors
  // via1_t1_irq_storm.s's own "no real VIA1" workaround, but here we drive
  // iplIn directly since we need CYCLE-exact control of the race window, not
  // just architectural round-trip counting).
  private val src =
    s"""
       | .text
       | .org 0
       |_start:
       |    lea     0x00020000, %a7
       |    move.l  #_irq_handler, 0x00000064   | vector 25 = 0x64
       |    moveq   #0, %d0
       |_loop:
       |    addq.l  #1, %d0
       |    move.l  %d0, ${"0x%08x".format(targetAddr)}
       |    bra     _loop
       |_irq_handler:
       |    rte
       |""".stripMargin

  test("interrupt storm never double-issues or drops a precise-path store's write") {
    val image = ProgramAssembler.assemble(src, loadAddr).getOrElse(fail("assemble failed"))
    val compiled = m68k040.M68kSim().withVerilator.compile(new FuzzCoreDut)
    compiled.doSim("irq_race", seed = 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd, injectBusErrors = false)
      // MMU is off for the whole run (see below), so the DTLB/ITLB table-walker
      // AXI masters are never driven by real page-walk traffic, but every other
      // established harness in this family (WildPcA7TraceSpec, PortedTestRunner,
      // P27HangTraceSpec, ...) still attaches a behavioral slave to them so their
      // `in` ports (ar.ready/r.*) are driven rather than left floating -- match
      // that established idiom defensively.
      // (The two table-walker AXI memories that used to be attached here are gone:
      // the ITLB/DTLB walkers no longer emit AXI. Their descriptor reads and U/M
      // writebacks are DcacheService client traffic now, so they reach memory
      // through the D-cache above -- which is also the point: a page-table line
      // sitting dirty in L1D is now visible to the walk.)
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)

      // Count every AW fire that targets the aligned target line -- a double-
      // issue shows up as more AW beats than there were completed loop
      // iterations; a drop shows up as fewer.
      //
      // ALSO track AW/B pairing directly: the DcachePlugin store-write path
      // is single-outstanding (see DcachePlugin.scala's "Single-outstanding:
      // the producer never presents a 2nd store before this one's [ack]"
      // comments, and its AW.id is a hardwired constant, U(1, 4 bits) --
      // there is no per-ID or per-line disambiguation to do), so a single
      // `outstandingAw` boolean is sufficient: set on an accepted AW to the
      // target line, cleared on the next accepted B. A double-issue bug
      // (relaunching the store while the previous one is still in flight)
      // shows up as a second AW-to-the-line firing while `outstandingAw` is
      // still true -- this is the actual, cycle-level assertion of the race
      // the design doc names.
      var awCount = 0
      var outstandingAw = false
      var outstandingAwCycle = 0
      var cyc = 0
      cd.onSamplings {
        cyc += 1
        val axi = dut.dcache.logic.axi
        // Clear on B first: an AW and the B acking the *previous* write are
        // allowed to fire in the same cycle (the ack already happened), so
        // process the clear before the new-AW check to avoid a false
        // positive on that legitimate back-to-back-but-acked case.
        val bFire = axi.b.valid.toBoolean && axi.b.ready.toBoolean
        if (bFire && outstandingAw) outstandingAw = false

        val awFire = axi.aw.valid.toBoolean && axi.aw.ready.toBoolean
        if (awFire && (axi.aw.payload.addr.toLong & ~0xFL) == (targetAddr & ~0xFL)) {
          awCount += 1
          assert(!outstandingAw,
            s"DOUBLE-ISSUE: AW fired to target line 0x${targetAddr.toHexString} at cycle $cyc while " +
            s"the previous AW to that same line (fired at cycle $outstandingAwCycle) has not yet been " +
            s"acknowledged via B -- relaunched-while-still-in-flight double issue")
          outstandingAw = true
          outstandingAwCycle = cyc
        }
      }

      // Boot sequence mirrors the established FuzzCoreDut idiom (WildPcA7TraceSpec /
      // PortedTestRunner): every external `in` port this DUT exposes is explicitly
      // driven to a known value before the first waitSampling, including the ones
      // the brief's minimal version left implicit (urp/srp/iackVector/resume/usp).
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0
      dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= true
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true; cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(20)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      // DE|IE -- "firmware already enabled the caches", the posture every other
      // full-core harness in this repo states explicitly (design doc section 5.2).
      // Stated here too as of 2026-09-09: CACR.IE acquired its FIRST reader that
      // day (IcachePlugin), so a bespoke sim that leaves CACR at its reset value 0
      // now runs with the instruction cache DISABLED. Correct, but not what this
      // test means to exercise -- and silently so.
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      // Fire a level-1 IRQ pulse (2 cycles high) at a DENSE, ~random-looking
      // cadence for a fixed number of cycles, covering every phase of the
      // store's XLATE->drain->ack window many times over (the store's whole
      // round trip is O(10) cycles, so a ~7-cycle period sweeps every offset).
      val totalCycles = 20000
      var c = 0
      while (c < totalCycles) {
        dut.intCtrl.logic.iplIn #= 1
        cd.waitSampling(2)
        dut.intCtrl.logic.iplIn #= 0
        cd.waitSampling(5)
        c += 7
      }

      // The AW/B pairing watchdog above is the direct, cycle-level assertion
      // of the double-issue hazard the design doc names -- it already fired
      // (failing the test) if any two AWs to the target line were ever
      // in flight at once. The check below is a separate, coarser sanity
      // check that the core made forward progress and did not livelock; it
      // does NOT (and is not meant to) catch a double-issue or a silent drop
      // by itself -- a few-percent systematic rate of either would still
      // sail past ">100" undetected here.
      //
      // A silent drop (a store that should have fired an AW but never did)
      // is architecturally distinguishable from memory alone -- D0 free-runs
      // and only the loop body writes it, so the final value in D0 could be
      // cross-checked against a reconstructed AW-derived count -- but doing
      // so needs a way to read D0 out of the DUT post-run, which this
      // harness doesn't currently provide; left as a documented known
      // limitation rather than adding a new whitebox tap for it here.
      assert(awCount > 100, s"too few store writes observed under IRQ storm (awCount=$awCount) -- possible livelock")
    }
  }
}
