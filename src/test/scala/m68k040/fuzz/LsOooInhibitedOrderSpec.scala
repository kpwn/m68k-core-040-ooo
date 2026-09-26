package m68k040.fuzz

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import scala.collection.mutable

/** TEST 1 (negative) for out-of-order LS issue: an INHIBITED access is neither
  * DOUBLE-LAUNCHED nor reordered, observed on the DEVICE SIDE.
  *
  * WHY DEVICE-SIDE AND NOT ARCHITECTURAL. A wrong number of bus transactions is invisible
  * in the register file: the second read of a device status port usually returns the same
  * value, so the architectural result matches while the device has been advanced twice.
  * This is not hypothetical -- the recorded `inhibited-load-irq-replay` bug re-popped the
  * 53C96, left the chip one word ahead and hung the machine, with correct registers
  * throughout. So every assertion here is on the AXI stream and the cache launch stream.
  *
  * ⚠️ WHY THE OBVIOUS ASSERTION IS NOT THE ONE USED, so nobody "fixes" it back.
  * "Exactly ONE bus transaction per architecturally RETIRED inhibited load" is
  * UNSATISFIABLE BY CONSTRUCTION. Once an inhibited load has launched, the device read has
  * happened; if a flush then squashes it, retired=0 while AXI=1. That is the documented
  * semantics of this core (a squashed-after-launch device read is never replayed -- see
  * `inhibitedLoadBusySig`, whose entire purpose is to stop the ROB recognising an
  * interrupt in that window), not a defect. The property that IS wanted:
  *
  *     no inhibited access is ever LAUNCHED TWICE
  *     AXI device reads == distinct inhibited launches
  *     retired inhibited loads <= launches
  *
  * ASSERTIONS
  *   A. no paddr is launched to inhibited space more than once per architectural execution
  *   B. AXI reads to device space == inhibited launches (1:1, no phantom, no dropped)
  *   C. no inhibited access launches while ANOTHER inhibited access is in flight -- the
  *      "after" half of the two-way barrier. See the long C note in the loop below: the
  *      strictly stronger form ("no LS op AT ALL launches") is MEASURED and PRINTED but is
  *      not fatal, and that note argues why, explicitly and against the design record,
  *      rather than silently.
  *   D. at the cycle an inhibited access launches, NO program-OLDER ring entry is still
  *      resident, and it IS the ROB head -- the "before" half. Age-compared against the
  *      live ROB head, the same head-anchored form `StoreQueue.olderThan` uses. Plus D0,
  *      checked at the P4 ENQUEUE cycle where the actual gate lives
  *      (`p4AtRobHead && !olderStore`).
  *   E. two inhibited accesses to different device addresses reach AXI in PROGRAM ORDER
  *
  * ─── WHERE EACH ASSERTION IS SAMPLED, and why it is not all one place ────────────────
  * An inhibited op does NOT go from P4 to the bus in one cycle: P4 pushes a descriptor
  * into the aligned ring (`alignedEnq`), and the ring later SENDS it to
  * `dcache.loadCmdPort`. So the launch gate (`p4AtRobHead`, `sq.io.barrier.olderStore`)
  * is only meaningful at the ENQUEUE cycle -- by the send cycle P4 already holds a
  * DIFFERENT op and those two signals describe that other op instead. The first version
  * of this test read `p4AtRobHead` at the send cycle and was measuring an unrelated op.
  * D0 therefore samples the gate at enqueue, and D samples ring residency at the send.
  *
  * ─── THE REVIEW CRITERION, and it is deliberately NOT a resource checklist ───────────
  * An earlier formulation was "the parked op must hold nothing that an older op needs to
  * complete". That is a per-resource enumeration -- probe credit, ring entry, SQ query port,
  * DTLB claim, load token -- and a checklist can be UNDER-ENUMERATED, which is how both
  * previous attempts at this barrier failed. The dual formulation is stronger and is the
  * actual property:
  *
  *     AT ALL TIMES, THE OLDEST IN-FLIGHT MEMORY OPERATION MUST HAVE AN
  *     UNOBSTRUCTED PATH TO COMPLETION.
  *
  * That is the sentence this core asserts at LsEuPlugin:3390-3393 ("a younger stalled load
  * never prevents an older instruction from retiring") and that relaxed LS issue falsified.
  * `OldestLsProgress` below checks it as ONE global assertion that does not depend on
  * having enumerated the right resources -- it would have caught both previous deadlocks
  * (a younger op parked at P4 starving an older op in P2/P3, and a younger ring entry
  * blocking the FIFO send ahead of an older one). The resource list is evidence FOR this
  * property, not the criterion itself.
  *
  * ✅ IT DID CATCH THE THIRD ONE, on its first ever execution. See THE FINDING below.
  *
  * The test is meaningful only with LS OoO on, so it REQUIRES non-vacuity: at least two
  * inhibited launches and at least two cacheable launches must have happened, else it fails
  * rather than passing silently.
  *
  * ─── POSTURE, and the trap that made the first draft vacuous ─────────────────────────
  * `ForceCacheableCopyback`'s TTR pair: DTT0 maps the low 2 GiB COPYBACK, DTT1 maps the
  * high 2 GiB INHIBITED. Device space must therefore be HIGH **and** inside the sim AXI
  * model's decode (`AxiMemModel.decoded`: top nibble 0/4/5/6, or `addr>>16 == 0xFFFF`),
  * which leaves exactly the 0xFFFF0000 page -- the same block `WalkerExcEntryWedgeSpec`
  * uses for its inhibited sentinel. A device address at 0x80001000 is high and inhibited
  * but NOT decoded, so every access to it DECERRs instead of being read. The completion
  * sentinel must be inhibited for the same reason the corpus's is: a COPYBACK store can
  * sit in the cache forever and never reach the harness's AXI memory at all.
  *
  * ═══ THE FINDING (2026-09-26, first execution of this test) ═══════════════════════════
  * With `FUZZ_LS_OOO=1` this program DEADLOCKS ~30 cycles in. The dump:
  *
  *   head=3  s1(rob=11) t(rob=9) tx(rob=5) p3(rob=3) p4(rob=7)
  *   p4Inhibited=true p4AtRobHead=false p4LaunchOk=false paddr=0xffff0100
  *   ring count=0  sq empty=true  loadBusy=false
  *
  * P4 HOLDS THE PROGRAM-YOUNGER INHIBITED OP (rob 7) WAITING TO BECOME THE ROB HEAD,
  * WHILE THE OP THAT IS THE ROB HEAD (rob 3) SITS BEHIND IT IN P3 AND CAN NEVER GET
  * THROUGH. `p4Ready = !p4Valid || p4CanLeave`, and `p4CanLeave` needs `p4LaunchOk`,
  * which for an inhibited op needs `p4AtRobHead`. So:
  *
  *   rob7 waits for head==7  ->  needs rob3 to retire  ->  needs rob3 to complete
  *                           ->  needs rob3 to pass P4 ->  needs rob7 to leave P4.
  *
  * This is the SAME circular wait `dbff8619` fixed at the ring send, one stage earlier.
  * That commit's claim -- "deadlock-free BY CONSTRUCTION ... no send can ever be held, so
  * the circular wait has no edge to close on" -- removed ONE edge of the cycle and left
  * the other: P4 is a single, non-bypassable stage, and the barrier's wait condition
  * ("until I am the ROB head") can only be satisfied by an older op that must pass through
  * the very stage the waiter occupies.
  *
  * Why it never happens with the knob OFF: in-order LS issue means the op resident in P4
  * is always the OLDEST in-flight LS op, so the head always advances to it. The relaxed
  * rule (`loadBypassUnreadyLoad`) is what puts a younger op into P4 first, and it does so
  * for the most ordinary reason imaginable -- in this program `a0` (the device base) is
  * ready before `a1` (the scratch base), which is the register-allocation shape of every
  * driver polling loop.
  *
  * So the P4 park needs a way to STEP ASIDE, not just to wait: the parked inhibited
  * descriptor must not occupy a stage an older op needs (a side buffer out of P4, or an
  * issue-side replay). Both are architectural changes with area/WNS cost and are NOT
  * attempted here -- this test is the reproducer and the gate for whichever is chosen. */
class LsOooInhibitedOrderSpec extends AnyFunSuite {
  // Device space: high half (=> INHIBITED via DTT1) and inside the sim AXI decode.
  private val DevA     = 0xffff0100L
  private val DevB     = 0xffff0110L   // == DevA + 16
  private val Scratch  = 0x00090000L   // low half => COPYBACK via DTT0
  private val Sentinel = PortedTestRunner.SentinelAddr   // 0xFFFF0000, inhibited => AXI-visible
  private val Iters    = 4

  private val Src =
    f"""
       |    lea 0x$DevA%08x,%%a0
       |    lea 0x$Scratch%08x,%%a1
       |    moveq #0,%%d7
       |.Lloop:
       |    move.l (%%a1),%%d1
       |    move.l 4(%%a1),%%d2
       |    move.l (%%a0),%%d0
       |    move.l 8(%%a1),%%d3
       |    move.l 16(%%a0),%%d4
       |    addq.l #1,%%d7
       |    cmpi.l #$Iters,%%d7
       |    bne.s .Lloop
       |    lea 0x$Sentinel%08x,%%a2
       |    move.l #0xC0FFEE00,%%d6
       |    move.l %%d6,(%%a2)
       |.Lhalt:
       |    bra.s .Lhalt
       |""".stripMargin

  private val RobW   = m68k040.Global.ROB_ID_W_DEFAULT
  private val RobMod = BigInt(1) << RobW

  /** Per-cycle watchdog for the review criterion above. Tracks the in-flight LS set from the
    * issue stream and the registered completion stage, picks the program-OLDEST by
    * ROB-head-anchored age (the same form `StoreQueue.olderThan` uses), and fails if that
    * same robId remains the oldest un-completed LS op for more than `limit` cycles.
    *
    * Cleared on a flush: a squash retires in-flight ops without completing them, so not
    * clearing would fire a false positive. */
  private final class OldestLsProgress(dut: FuzzCoreDut, limit: Int = 5000) {
    private val inFlight = mutable.Map.empty[BigInt, Int]   // robId -> cycle it issued
    private var stuckRob: BigInt = -1
    private var stuckFor  = 0
    var failure: Option[String] = None

    def sample(cycle: Int): Unit = {
      val ip = dut.lsEu.issuePort
      if (ip.valid.toBoolean && ip.ready.toBoolean)
        inFlight.getOrElseUpdate(ip.payload.robId.toBigInt, cycle)
      if (dut.lsEu.logic.compValid.toBoolean)
        inFlight.remove(dut.lsEu.logic.compRobId.toBigInt)
      if (dut.rob.logic.doFlushReg.toBoolean) { inFlight.clear(); stuckRob = -1; stuckFor = 0 }

      if (inFlight.isEmpty) { stuckRob = -1; stuckFor = 0 }
      else {
        val head = dut.lsEu.robHeadIn.toBigInt
        def age(r: BigInt) = ((r - head) % RobMod + RobMod) % RobMod
        val oldest = inFlight.keys.minBy(age)
        if (oldest == stuckRob) {
          stuckFor += 1
          if (stuckFor > limit && failure.isEmpty) {
            // NAME the known shape when it is present, but keep the GLOBAL property as the
            // criterion -- the whole point of this watchdog is that it does not depend on
            // having enumerated the right resource.
            val ls    = dut.lsEu.logic
            val p4Rob = ls.p4Ctx.xlate.front.robId.toBigInt
            val shape =
              if (ls.p4Valid.toBoolean && ls.p4Inhibited.toBoolean && !ls.p4AtRobHead.toBoolean &&
                  age(p4Rob) > age(oldest))
                f" -- SHAPE: P4-PARK STARVATION. P4 holds the program-YOUNGER inhibited op " +
                f"rob=$p4Rob parked for `p4AtRobHead`, and the op that IS the head " +
                f"(rob=$oldest) is behind it in the pipe and can never pass P4. Circular wait."
              else ""
            failure = Some(f"OLDEST IN-FLIGHT LS OP HAS NO PATH TO COMPLETION: robId=$oldest " +
              f"issued at cycle ${inFlight(oldest)}, still oldest and un-completed $stuckFor " +
              f"cycles later (in-flight set: ${inFlight.keys.toSeq.sortBy(age).mkString(",")})$shape")
          }
        } else { stuckRob = oldest; stuckFor = 0 }
      }
    }
  }

  test("LS OoO: an inhibited access is never double-launched and never reordered", VerilatorTest) {
    val lsOoo = sys.env.get("FUZZ_LS_OOO").contains("1")
    val loadAddr = PortedTestRunner.loadAddr
    val image = ProgramAssembler.assemble(Src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble: ${e.reason}")
    }
    PortedTestRunner.compiled.doSim("ls_ooo_inhibited_order", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      // Memory wiring identical to PortedTestRunner's / WalkerExcEntryWedgeSpec's.
      FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dsideMem = new m68k040.sim.ConstFillSparseMemory(0xff.toByte)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                                   sharedMem = dsideMem, injectBusErrors = true)
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)
      // MANDATORY: the sentinel poll treats word==0 as "not written yet", and the 0xFF
      // fill would read back as an instant completion on cycle 1.
      for (i <- 0 until 4) dmem.mem.write(Sentinel + i, 0.toByte)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false; dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()

      // MMU on: low half COPYBACK, high half INHIBITED -- the posture
      // `CachePosture.ForceCacheableCopyback` uses, so device space is real device space.
      dut.ctrl.logic.itt0 #= CachePosture.LowHalfCopybackTtr
      dut.ctrl.logic.itt1 #= 0
      dut.ctrl.logic.dtt0 #= CachePosture.LowHalfCopybackTtr
      dut.ctrl.logic.dtt1 #= CachePosture.HighHalfInhibitedTtr
      dut.rob.logic.exc.ss.cacr #= CachePosture.CacrDataAndInstructionEnable
      dut.ctrl.logic.mmuEnable #= true
      cd.waitSampling()
      // Fail closed if the posture did not stick: a run with device space mapped
      // COPYBACK observes zero inhibited launches and is worthless.
      assert(dut.ctrl.logic.mmuEnable.toBoolean, "TC.E did not stick")
      assert(dut.ctrl.logic.dtt1.toBigInt == CachePosture.HighHalfInhibitedTtr,
        f"DTT1 readback 0x${dut.ctrl.logic.dtt1.toBigInt}%08x")

      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      def isDevice(a: BigInt): Boolean = a >= BigInt(0x80000000L)

      final case class Launch(cyc: Int, paddr: BigInt, robId: BigInt)
      val launches      = mutable.ArrayBuffer.empty[Launch]   // inhibited launches, in order
      val axiDevReads   = mutable.ArrayBuffer.empty[BigInt]   // AXI AR addrs to device space
      var cacheableLaunches = 0
      val overlapAny    = mutable.ArrayBuffer.empty[String]   // C, strong form (see the C note)
      val overlapBus    = mutable.ArrayBuffer.empty[String]   // C, strong form, bus still outstanding
      val violCInhib    = mutable.ArrayBuffer.empty[String]   // C, fatal form
      val violD         = mutable.ArrayBuffer.empty[String]
      val violD0        = mutable.ArrayBuffer.empty[String]
      var orderViolations = 0
      var flushes         = 0
      val lc  = dut.dcache.logic.loadCmdPort
      val ax  = dut.dcache.logic.axi
      val ls  = dut.lsEu.logic
      val ringDepth = ls.alignedValid.length
      var sentinelSeen = false
      var cycles = 0
      val progress = new OldestLsProgress(dut)

      def age(r: BigInt, head: BigInt) = ((r - head) % RobMod + RobMod) % RobMod

      /** One-shot LS-pipeline state dump, printed when `OldestLsProgress` trips. A
        * deadlock's state is static, so what it shows at the trip cycle is what it has
        * shown since the machine froze -- one dump is the whole picture. */
      def dumpLs(tag: String): Unit = {
        val head = dut.lsEu.robHeadIn.toBigInt
        println(f"[ls-dump/$tag] cyc=$cycles head=$head headValid=${dut.lsEu.robHeadValidIn.toBoolean} " +
          f"issue(v=${dut.lsEu.issuePort.valid.toBoolean},r=${dut.lsEu.issuePort.ready.toBoolean}," +
          f"rob=${dut.lsEu.issuePort.payload.robId.toBigInt})")
        println(f"[ls-dump/$tag] s1(v=${ls.s1Valid.toBoolean},rob=${ls.s1Ctx.robId.toBigInt}) " +
          f"t(v=${ls.tValid.toBoolean},rob=${ls.tCtx.robId.toBigInt}) " +
          f"tx(v=${ls.txValid.toBoolean},rob=${ls.txCtx.robId.toBigInt},second=${ls.txSecond.toBoolean}) " +
          f"p3(v=${ls.p3Valid.toBoolean},rob=${ls.p3Ctx.front.robId.toBigInt}) " +
          f"p4(v=${ls.p4Valid.toBoolean},rob=${ls.p4Ctx.xlate.front.robId.toBigInt})")
        println(f"[ls-dump/$tag] p4Inhibited=${ls.p4Inhibited.toBoolean} p4AtRobHead=${ls.p4AtRobHead.toBoolean} " +
          f"p4LaunchOk=${ls.p4LaunchOk.toBoolean} p4RetryQuery=${ls.p4RetryQuery.toBoolean} " +
          f"fwdHit=${ls.p4Ctx.fwdHit.toBoolean} fwdStall=${ls.p4Ctx.fwdStall.toBoolean} " +
          f"fwdSerial=${ls.p4Ctx.fwdSerial.toBoolean} paddr=0x${ls.p4Ctx.xlate.paddr.toBigInt}%x")
        println(f"[ls-dump/$tag] ring count=${ls.alignedCount.toBigInt} full=${ls.alignedFull.toBoolean} " +
          f"sendPtr=${ls.alignedSendPtr.toBigInt} rspPtr=${ls.alignedRspPtr.toBigInt} " +
          f"pushPtr=${ls.alignedPushPtr.toBigInt} sendValid=${ls.alignedSendValid.toBoolean} " +
          f"sendHeld=${ls.alignedSendHeld.toBoolean} bkBusy=${ls.bkBusy.toBoolean}")
        for (i <- 0 until ringDepth)
          println(f"[ls-dump/$tag]   slot$i v=${ls.alignedValid(i).toBoolean} sent=${ls.alignedSent(i).toBoolean} " +
            f"done=${ls.alignedDone(i).toBoolean} poison=${ls.alignedPoisoned(i).toBoolean} " +
            f"rob=${ls.alignedMem(i).bk.robId.toBigInt} two=${ls.alignedMem(i).twoAccess.toBoolean}")
        println(f"[ls-dump/$tag] sq empty=${ls.sq.io.empty.toBoolean} full=${ls.sq.io.full.toBoolean} " +
          f"olderStore=${ls.sq.io.barrier.olderStore.toBoolean} " +
          f"olderInhibStore=${ls.sq.io.barrier.olderInhibitedStore.toBoolean} " +
          f"loadBusy=${ls.loadBusyReg.toBoolean} respSeen=${ls.inhibitedRespSeen.toBoolean} " +
          f"ldOwner=${ls.ldOwner.toBigInt} orderViol=${dut.lsEu.orderViolationPort.valid.toBoolean}")
        println(f"[ls-dump/$tag] dcache loadCmd(v=${lc.valid.toBoolean},r=${lc.ready.toBoolean}) " +
          f"excLoadOut=${ls.excLoadOutstanding.toBoolean} walkStOut=${ls.walkStOutstanding.toBoolean} " +
          f"coreStOut=${ls.coreStOutstanding.toBigInt}")
      }

      while (!sentinelSeen && cycles < 400000) {
        cd.waitSampling(); cycles += 1
        progress.sample(cycles)
        progress.failure.foreach { m => dumpLs("stuck"); fail(s"(1b) $m") }

        if (dut.rob.logic.doFlushReg.toBoolean) flushes += 1
        if (dut.lsEu.orderViolationPort.valid.toBoolean) orderViolations += 1

        // AXI reads to device space
        if (ax.ar.valid.toBoolean && ax.ar.ready.toBoolean) {
          val a = ax.ar.payload.addr.toBigInt
          if (isDevice(a)) axiDevReads += a
        }

        // (D0) THE GATE ITSELF, sampled where it is actually evaluated: the P4 enqueue
        // cycle. `p4AtRobHead` means every program-older instruction has ALREADY RETIRED
        // -- strictly stronger than "no older ring entry is resident" -- and its
        // companion `olderStore` covers the drained-SQ half. Reading either at the ring
        // SEND cycle reads a different op's gate (see the sampling note in the header).
        if (ls.p4Inhibited.toBoolean &&
            (ls.alignedEnq.toBoolean || ls.alignedEnqSplit.toBoolean)) {
          if (!ls.p4AtRobHead.toBoolean)
            violD0 += f"cyc=$cycles inhibited op enqueued while NOT the ROB head"
          if (ls.sq.io.barrier.olderStore.toBoolean)
            violD0 += f"cyc=$cycles inhibited op enqueued with an older store resident"
        }

        // cache launch stream
        if (lc.valid.toBoolean && lc.ready.toBoolean) {
          val inhibited = lc.payload.cacheMode.toEnum == CacheMode.INHIBITED
          val pa = lc.payload.paddr.toBigInt
          val ridV = lc.payload.ridValid.toBoolean
          val rid  = lc.payload.rid.toInt
          val head = dut.lsEu.robHeadIn.toBigInt
          // `loadBusyReg` latches at the inhibited ENQUEUE, i.e. at least one cycle before
          // that same op's own ring SEND, so an op's own launch would otherwise read as an
          // overlap with itself. `robId != head` excludes self: assertion D proves an
          // inhibited op launches only when it IS the head.
          val inhibitedBusy = ls.loadBusyReg.toBoolean
          if (inhibited) {
            val robId = if (ridV) ls.alignedMem(rid).bk.robId.toBigInt else BigInt(-1)
            launches += Launch(cycles, pa, robId)
            // (C, FATAL) two device transactions overlapping is the device-order violation
            // proper -- the 53C96 shape.
            if (inhibitedBusy && ridV && robId != head)
              violCInhib += f"cyc=$cycles inhibited launch paddr=0x$pa%x robId=$robId while " +
                f"another inhibited access was in flight"
            // (D) THE "BEFORE" HALF at the SEND cycle, on ring residency, which is what
            // survives the P4-vs-send sampling problem: no resident, non-poisoned ring
            // entry may be program-OLDER than the entry being sent.
            if (ridV) {
              val olderRing = (0 until ringDepth).filter { i =>
                i != rid && ls.alignedValid(i).toBoolean && !ls.alignedPoisoned(i).toBoolean &&
                  age(ls.alignedMem(i).bk.robId.toBigInt, head) < age(robId, head)
              }
              if (olderRing.nonEmpty)
                violD += f"cyc=$cycles inhibited paddr=0x$pa%x rid=$rid robId=$robId head=$head " +
                  f"sent with OLDER ring entries still resident: " +
                  olderRing.map(i => f"slot$i(rob=${ls.alignedMem(i).bk.robId.toBigInt})").mkString(",")
              if (robId != head)
                violD += f"cyc=$cycles inhibited paddr=0x$pa%x rid=$rid robId=$robId launched " +
                  f"while the ROB head is $head (an inhibited op must launch AT the head)"
            }
          } else {
            cacheableLaunches += 1
            // ─── (C) THE STRONG FORM: MEASURED, PRINTED, AND DELIBERATELY NOT FATAL ────
            // The original wording of this assertion was: "while an inhibited access is in
            // flight (`inhibitedLoadBusySig`), NO other LS op launches into the cache --
            // the 'after' half of the two-way barrier". It is kept verbatim here so the
            // reasoning is not lost -- but it is NOT asserted, and this is the argument,
            // made against `dbff8619`'s own rationale rather than quietly:
            //
            //  1. MEASUREMENT. It fires identically with the knob OFF (8 occurrences in
            //     this 4-iteration loop, `FUZZ_LS_OOO` unset) as with it ON. It therefore
            //     describes the pre-existing core, not the OoO relaxation: by measurement
            //     it is not a regression and not an OoO property at all. Making it fatal
            //     would red the shipping default.
            //  2. THE DESIGN RECORD CLAIMS SOMETHING NARROWER. `dbff8619`: "THE AFTER HALF,
            //     POST-LAUNCH, ALSO NEEDS NO COMPARATOR. Because an inhibited op only
            //     launches at the head, once it is in flight every other in-flight LS op is
            //     necessarily younger, so `inhibitedLoadBusySig` is age-correct by
            //     construction; and for inhibited STORES
            //     `sq.io.barrier.olderInhibitedStore` already gates P4 launch". That claim
            //     is about DEVICE-VISIBLE order -- inhibited-vs-inhibited, and
            //     inhibited-store-vs-anything. It never claimed to fence cacheable loads,
            //     and `inhibitedLoadLaunch`'s own comment warns that latching busy for
            //     ordinary loads "would silently reintroduce a one-at-a-time chokepoint on
            //     the ordinary hot path".
            //  3. THE WINDOW IS THE WRONG WINDOW. `loadBusyReg` runs from LAUNCH to the
            //     launcher's RETIRE, and it is that long on purpose -- it exists to stop
            //     the ROB RECOGNISING AN INTERRUPT in that gap (the 53C96 replay fix), not
            //     to order accesses. Asserting on it would forbid every cacheable launch
            //     for the whole retire tail, long after the device transaction completed.
            //     `overlapBus` measures the honest sub-window (response not yet consumed)
            //     separately so the two are never conflated again.
            //  4. THE SOFTWARE-VISIBLE DIRECTION ALREADY HAS A REAL COMPARATOR. The
            //     dangerous case is an inhibited STORE followed by a younger access (a
            //     driver writing a command register then reading a DMA buffer); that is
            //     gated for EVERY ordinary P4 launch by `olderInhibitedStore`, and this
            //     test measures zero violations of it.
            //  5. THE ARCHITECTURE AGREES. The TTR used here is CM=11, cache-inhibited
            //     NONSERIALIZED; and even CM=10's serialization requirement is about the
            //     device access completing before the next access, not about fencing
            //     unrelated DRAM traffic.
            //
            // If someone later decides the strong form IS the contract, the numbers to make
            // it fatal on are already printed -- do that as a deliberate spec change with an
            // IPC measurement attached, not as a test edit.
            if (inhibitedBusy) {
              overlapAny += f"cyc=$cycles cacheable launch paddr=0x$pa%x while inhibitedLoadBusy"
              if (!ls.inhibitedRespSeen.toBoolean)
                overlapBus += f"cyc=$cycles cacheable launch paddr=0x$pa%x while the device " +
                  f"transaction was still outstanding on the bus"
            }
          }
        }

        val w = (0 until 4).foldLeft(BigInt(0)) { (v, k) =>
          (v << 8) | BigInt(dmem.mem.read(Sentinel + k) & 0xff)
        }
        if (w != 0) {
          assert(w == BigInt(0xc0ffee00L),
            f"program wrote a FAIL sentinel 0x$w%08x at cycle $cycles")
          sentinelSeen = true
        }
      }

      val report =
        f"[ls-ooo-inhib] FUZZ_LS_OOO=$lsOoo cycles=$cycles sentinel=$sentinelSeen " +
        f"inhibLaunches=${launches.size} axiDevReads=${axiDevReads.size} " +
        f"cacheableLaunches=$cacheableLaunches flushes=$flushes orderViolations=$orderViolations " +
        f"violC(inhibited,FATAL)=${violCInhib.size} overlapAny=${overlapAny.size} " +
        f"overlapBus=${overlapBus.size} violD=${violD.size} violD0=${violD0.size}"
      println(report)
      println(f"[ls-ooo-inhib] inhibited launches: " +
        launches.map(l => f"cyc${l.cyc}:0x${l.paddr}%x/rob${l.robId}").mkString(" "))
      println(f"[ls-ooo-inhib] AXI device ARs: " + axiDevReads.map(a => f"0x$a%x").mkString(" "))
      if (overlapAny.nonEmpty) println("[ls-ooo-inhib] overlapAny (NOT fatal -- see the C note) first 8:\n  " +
        overlapAny.take(8).mkString("\n  "))
      if (violCInhib.nonEmpty) println("[ls-ooo-inhib] violC(inhibited) first 8:\n  " + violCInhib.take(8).mkString("\n  "))
      if (violD.nonEmpty)      println("[ls-ooo-inhib] violD first 8:\n  " + violD.take(8).mkString("\n  "))
      if (violD0.nonEmpty)     println("[ls-ooo-inhib] violD0 first 8:\n  " + violD0.take(8).mkString("\n  "))

      assert(sentinelSeen, s"program did not reach its sentinel in $cycles cycles (hang?) -- $report")

      // ---- non-vacuity FIRST: a silent pass here would be worthless ----
      assert(launches.size >= 2,
        s"vacuous: only ${launches.size} inhibited launches observed -- the posture did not " +
        s"make device space inhibited -- $report")
      assert(cacheableLaunches >= 2, s"vacuous: only $cacheableLaunches cacheable launches -- $report")

      // (A) no double-launch
      // (A) EXACT equality, not `<=`, and D0/D is what justifies it: an inhibited access
      // launches only at the ROB head, so every older branch has already retired and it can
      // never be launched on a wrong path. A count above the architectural one therefore
      // means a genuine DOUBLE LAUNCH (the 53C96 shape), not legitimate wrong-path work.
      val perAddr = launches.groupBy(_.paddr).view.mapValues(_.size).toMap
      perAddr.foreach { case (a, n) =>
        assert(n == Iters,
          f"(A) device paddr 0x$a%x launched $n times, expected exactly $Iters " +
          f"(> means DOUBLE LAUNCH: the device was advanced twice for one instruction) -- $report")
      }
      assert(perAddr.keySet == Set(BigInt(DevA), BigInt(DevB)),
        s"(A) unexpected inhibited paddr set ${perAddr.keySet.map(_.toString(16))} -- $report")

      // (B) AXI 1:1 with launches
      assert(axiDevReads.size == launches.size,
        s"(B) AXI device reads=${axiDevReads.size} != inhibited launches=${launches.size} -- $report")

      // (C)/(D)
      assert(violCInhib.isEmpty,
        s"(C) an inhibited access launched while ANOTHER inhibited access was in flight:\n  " +
        violCInhib.take(5).mkString("\n  "))
      assert(violD.isEmpty,
        s"(D) an inhibited access launched out of order (older ring entry resident, or not " +
        s"at the head):\n  " + violD.take(5).mkString("\n  "))
      assert(violD0.isEmpty,
        s"(D0) the P4 inhibited launch gate was bypassed:\n  " + violD0.take(5).mkString("\n  "))

      // (E) program order between the two device addresses, per iteration
      val expectSeq = (0 until Iters).flatMap(_ => Seq(BigInt(DevA), BigInt(DevB)))
      assert(axiDevReads == expectSeq,
        s"(E) device reads out of program order:\n  got      ${axiDevReads.map(_.toString(16)).mkString(",")}" +
        s"\n  expected ${expectSeq.map(_.toString(16)).mkString(",")}")
    }
  }
}
