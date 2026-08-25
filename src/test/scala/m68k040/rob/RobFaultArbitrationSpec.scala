package m68k040.rob

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.decode.DecOp
import m68k040.rename.RenamedUop
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** ROB-fold Slice C: the four fault-completion ports (ls / sq / eu / fp) are
  * age-arbitrated into ONE write port over `faultDynMem`.
  *
  * The structural invariant these tests pin, and the reason dropping the losers is
  * sound, is written out in full at the arbitration block in `RobPlugin.scala`. In
  * short: the ROB retires strictly in order; a faulted head can NEVER retire
  * normally (retire0/retire1 are gated on `!faultedStore(h0/h1)`) and its only exit
  * is `faultRetire`, which squashes the ENTIRE ROB — so a younger entry whose mark
  * was dropped is destroyed before it can ever be read at the head.
  *
  * Covered here:
  *  1. two ports, same cycle, DIFFERENT robIds -> the OLDER one's record is what the
  *     exception delivers, and the younger robId carries no mark at all;
  *  2. the same case across the 0/63 ring WRAP, where age must be
  *     (robId - head) mod 64 and a naive robId compare would pick the wrong entry;
  *  3. a single port firing alone (the overwhelmingly common case) is unchanged;
  *  4. a same-robId same-cycle collision preserves the OLD last-assign priority
  *     (fp > eu > sq > ls).
  *
  * A `GenerationFlags.simulation` tripwire inside RobPlugin additionally compares
  * the arbitrated derivation at h0 against an unarbitrated shadow of the deleted
  * seven-array structure on every headReady/faultRetire cycle, in every simulation
  * this project runs — so these directed cases are the sharp edge of a check that is
  * always on.
  */
class RobFaultArbitrationSpec extends AnyFunSuite {

  class RobDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink)) }
  }

  private def pokeRu(u: RenamedUop, pc: Long): Unit = {
    u.valid #= true; u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.LS; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0; u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false; u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false; u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false; u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
  }

  /** Idle every driven port. Every input here is a real `in()`/simPublic net whose
    * value would otherwise randomize per seed (this project's documented gotcha). */
  private def init(dut: RobDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    idleFaults(dut)
    pokeRu(dut.rsrc.logic.src.payload(0), 0x1000)
    pokeRu(dut.rsrc.logic.src.payload(1), 0x1002)
    cd.waitSampling(3)
  }

  private def idleFaults(dut: RobDut): Unit = {
    val l = dut.rob.logic
    l.lsFaultCompletion.valid #= false
    l.lsFaultCompletion.payload.robId #= 0; l.lsFaultCompletion.payload.faultAddr #= 0
    l.lsFaultCompletion.payload.write #= false; l.lsFaultCompletion.payload.sizeBits #= 2
    l.lsFaultCompletion.payload.supervisor #= false; l.lsFaultCompletion.payload.atc #= true
    l.sqFaultCompletion.valid #= false
    l.sqFaultCompletion.payload.robId #= 0; l.sqFaultCompletion.payload.faultAddr #= 0
    l.sqFaultCompletion.payload.write #= false; l.sqFaultCompletion.payload.sizeBits #= 2
    l.sqFaultCompletion.payload.supervisor #= false; l.sqFaultCompletion.payload.atc #= true
    l.euFaultCompletion.valid #= false
    l.euFaultCompletion.payload.robId #= 0; l.euFaultCompletion.payload.vector #= 0
    l.euFaultCompletion.payload.faultAddr #= 0
    l.fpFaultCompletion.valid #= false
    l.fpFaultCompletion.payload.robId #= 0; l.fpFaultCompletion.payload.vector #= 0
    l.fpFaultCompletion.payload.faultAddr #= 0
  }

  private def driveLs(dut: RobDut, robId: Int, addr: Long, wr: Boolean = true,
                      size: Int = 2, sup: Boolean = true, atc: Boolean = true): Unit = {
    val p = dut.rob.logic.lsFaultCompletion
    p.valid #= true; p.payload.robId #= robId; p.payload.faultAddr #= BigInt(addr)
    p.payload.write #= wr; p.payload.sizeBits #= size
    p.payload.supervisor #= sup; p.payload.atc #= atc
  }
  private def driveSq(dut: RobDut, robId: Int, addr: Long, wr: Boolean = true,
                      size: Int = 1, sup: Boolean = false, atc: Boolean = false): Unit = {
    val p = dut.rob.logic.sqFaultCompletion
    p.valid #= true; p.payload.robId #= robId; p.payload.faultAddr #= BigInt(addr)
    p.payload.write #= wr; p.payload.sizeBits #= size
    p.payload.supervisor #= sup; p.payload.atc #= atc
  }
  private def driveEu(dut: RobDut, robId: Int, vector: Int, addr: Long): Unit = {
    val p = dut.rob.logic.euFaultCompletion
    p.valid #= true; p.payload.robId #= robId; p.payload.vector #= vector
    p.payload.faultAddr #= BigInt(addr)
  }
  private def driveFp(dut: RobDut, robId: Int, vector: Int, addr: Long): Unit = {
    val p = dut.rob.logic.fpFaultCompletion
    p.valid #= true; p.payload.robId #= robId; p.payload.vector #= vector
    p.payload.faultAddr #= BigInt(addr)
  }

  /** Allocate ONE uop (one ROB entry). Returns nothing; the caller tracks robIds. */
  private def allocOne(dut: RobDut, cd: ClockDomain, pc: Long): Unit = {
    pokeRu(dut.rsrc.logic.src.payload(0), pc)
    dut.rsrc.logic.src.valid #= true; dut.rsrc.logic.u1v #= false
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    cd.waitSampling()
  }

  /** Allocate + complete + retire ONE entry, advancing `head` by exactly one.
    * Used to walk `head` up to an arbitrary ring position for the wrap test. */
  private def cycleOneEntry(dut: RobDut, cd: ClockDomain, robId: Int): Unit = {
    allocOne(dut, cd, 0x2000L + 4 * robId)
    dut.rob.logic.completion(0).valid #= true
    dut.rob.logic.completion(0).payload #= robId
    cd.waitSampling()
    dut.rob.logic.completion(0).valid #= false
    var n = 0
    while (dut.rob.logic.head.toInt == robId && n < 40) { cd.waitSampling(); n += 1 }
    assert(dut.rob.logic.head.toInt == ((robId + 1) % 64),
      s"head failed to advance past robId $robId (head=${dut.rob.logic.head.toInt})")
  }

  /** Run until exceptionPending pulses, then hand the observed record to `check`. */
  private def expectException(dut: RobDut, cd: ClockDomain, what: String)(check: => Unit): Unit = {
    var seen = false; var n = 0
    while (!seen && n < 80) {
      if (dut.rob.logic.exceptionPending.toBoolean) { seen = true; check }
      n += 1; cd.waitSampling()
    }
    assert(seen, s"exceptionPending never pulsed ($what)")
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 1. Cross-entry age arbitration, no wrap.
  // ────────────────────────────────────────────────────────────────────────────
  test("two fault ports, same cycle, different robIds: the OLDER entry's fault wins") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      // Four in-flight entries, robIds 0..3, head = 0.
      for (i <- 0 until 4) allocOne(dut, cd, 0x500L + 4 * i)
      assert(dut.rob.logic.head.toInt == 0)

      // Same cycle: a YOUNGER ls fault at robId 3 and an OLDER eu fault at robId 1.
      // Fixed port priority alone (fp > eu > sq > ls, the old last-assign order)
      // would be ambiguous here; AGE is what must decide, and it picks robId 1.
      driveLs(dut, robId = 3, addr = 0x3333L)
      driveEu(dut, robId = 1, vector = 3, addr = 0x1111L)
      cd.waitSampling()
      idleFaults(dut)
      cd.waitSampling()

      // The winner is marked; the loser carries NO mark at all (neither `faulted`
      // nor the dyn-record gate) -- gate and Mem row are written by one statement,
      // so a dropped port can never leave a half-written entry behind.
      assert(dut.rob.logic.faultedStore(1).toBoolean, "older entry (robId 1) must be marked faulted")
      assert(dut.rob.logic.faultDynStore(1).toBoolean, "older entry's dyn-record gate must be open")
      assert(!dut.rob.logic.faultedStore(3).toBoolean,
        "younger entry (robId 3) must NOT be marked -- its fault was dropped by arbitration")
      assert(!dut.rob.logic.faultDynStore(3).toBoolean,
        "younger entry's dyn-record gate must stay closed (no half-written entry)")

      // Complete 0 and 1 so the head walks to the faulted entry and delivers.
      for (id <- Seq(0, 1)) {
        dut.rob.logic.completion(0).valid #= true
        dut.rob.logic.completion(0).payload #= id
        cd.waitSampling()
      }
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "older eu fault at robId 1") {
        assert(dut.rob.logic.exceptionVector.toInt == 3,
          s"vector must be the OLDER eu fault's 3, got ${dut.rob.logic.exceptionVector.toInt}")
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x1111L,
          f"faultAddr must be the OLDER eu fault's 0x1111, got 0x${dut.rob.logic.exceptionFaultAddr.toLong}%x")
        assert(dut.rob.logic.exceptionPc.toLong == 0x504L,
          f"excPc must be robId 1's pc 0x504, got 0x${dut.rob.logic.exceptionPc.toLong}%x")
      }
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 2. The 0/63 ring wrap -- the case a naive `robId <` comparison gets wrong.
  // ────────────────────────────────────────────────────────────────────────────
  test("wraparound: with head near 63, age is (robId - head) mod 64, not a raw compare") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      // Walk head to 62 by cycling 62 single entries through the ring.
      for (i <- 0 until 62) cycleOneEntry(dut, cd, i)
      assert(dut.rob.logic.head.toInt == 62, s"head=${dut.rob.logic.head.toInt}, want 62")

      // Four in-flight entries straddling the wrap: robIds 62, 63, 0, 1.
      // Ages are 0, 1, 2, 3 respectively.
      val pcs = Seq(62 -> 0x6200L, 63 -> 0x6300L, 0 -> 0x0100L, 1 -> 0x0200L)
      for ((_, pc) <- pcs) allocOne(dut, cd, pc)

      // Same cycle: sq fault at robId 63 (age 1, OLDER) and ls fault at robId 1
      // (age 3, younger). A raw magnitude compare on robId would call 1 < 63 and
      // pick the WRONG (younger) entry; mod-64 age picks robId 63.
      driveSq(dut, robId = 63, addr = 0x6363L, wr = false, size = 0, sup = true, atc = false)
      driveLs(dut, robId = 1, addr = 0x0101L)
      cd.waitSampling()
      idleFaults(dut)
      cd.waitSampling()

      assert(dut.rob.logic.faultedStore(63).toBoolean,
        "robId 63 (age 1) is the OLDER entry across the wrap and must win")
      assert(!dut.rob.logic.faultedStore(1).toBoolean,
        "robId 1 (age 3) is YOUNGER across the wrap -- its fault must be dropped")

      // Retire 62 so the winner reaches the head.
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 62
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 63
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "sq fault at robId 63 across the wrap") {
        assert(dut.rob.logic.exceptionVector.toInt == 2,
          s"an sq access fault is vector 2, got ${dut.rob.logic.exceptionVector.toInt}")
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x6363L,
          f"faultAddr must be robId 63's 0x6363, got 0x${dut.rob.logic.exceptionFaultAddr.toLong}%x")
        // The full SSW attribute set must be the winner's, not a hybrid.
        assert(!dut.rob.logic.exceptionFaultWr.toBoolean, "sq fault was a READ")
        assert(dut.rob.logic.exceptionFaultSize.toInt == 0, "sq fault was BYTE-sized")
        assert(dut.rob.logic.exceptionFaultSup.toBoolean, "sq fault was supervisor")
        assert(!dut.rob.logic.exceptionFaultAtc.toBoolean, "sq fault was a plain bus error, not ATC")
      }
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 3. The common case: one port alone, unchanged.
  // ────────────────────────────────────────────────────────────────────────────
  test("a single fault port firing alone delivers its whole record unchanged") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      allocOne(dut, cd, 0x700L)
      driveLs(dut, robId = 0, addr = 0x2000L, wr = true, size = 1, sup = true, atc = true)
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      idleFaults(dut)
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "lone ls fault") {
        assert(dut.rob.logic.exceptionVector.toInt == 2, "ls access fault -> vector 2")
        assert(dut.rob.logic.exceptionPc.toLong == 0x700L, "faulting pc")
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x2000L, "fault VA")
        assert(dut.rob.logic.exceptionFaultWr.toBoolean, "write attr")
        assert(dut.rob.logic.exceptionFaultSize.toInt == 1, "WORD size attr")
        assert(dut.rob.logic.exceptionFaultSup.toBoolean, "supervisor attr")
        assert(dut.rob.logic.exceptionFaultAtc.toBoolean, "ATC attr")
        assert(!dut.rob.logic.exceptionFaultInstr.toBoolean,
          "a DATA access fault is never an instruction fetch")
      }
    }
  }

  test("an ALLOC-time (I-fetch) fault still reads its alloc record, and a defined SIZE") {
    // Slice D's bug: the old faultSizeStore had NO alloc write, so a re-used index
    // stacked the PREVIOUS occupant's LsFault.sizeBits into its format-$7 SSW. Here
    // robId 0 first takes a BYTE-sized ls fault, the entry is retired/reallocated,
    // and the new occupant's alloc-time vector-2 fault must read LONG (SSW SIZE=00),
    // not the byte size left behind.
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      // Occupant #1 at robId 0: a BYTE ls fault. Do NOT retire it through the
      // exception path (that would wedge the standalone FSM); just mark it, then
      // flush the ring so robId 0 is handed back for re-allocation.
      allocOne(dut, cd, 0x800L)
      driveLs(dut, robId = 0, addr = 0x9000L, wr = true, size = 0, sup = true, atc = false)
      cd.waitSampling()
      idleFaults(dut)
      cd.waitSampling()
      assert(dut.rob.logic.faultDynStore(0).toBoolean, "occupant #1's dyn record is live")

      dut.rob.logic.flush.valid #= true; cd.waitSampling()
      dut.rob.logic.flush.valid #= false; cd.waitSampling(3)

      // Occupant #2 at robId 0: an I-FETCH fault captured at ALLOC (vector 2,
      // sswInstr set, EA = the fetch PC).
      val u = dut.rsrc.logic.src.payload(0)
      pokeRu(u, 0x900L)
      u.faulted #= true; u.faultVector #= 2; u.sswInstr #= true; u.faultAtc #= true
      dut.rsrc.logic.src.valid #= true; dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      assert(!dut.rob.logic.faultDynStore(0).toBoolean,
        "alloc must close the dyn gate -- occupant #2 must not inherit occupant #1's record")

      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "alloc-time I-fetch fault after slot reuse") {
        assert(dut.rob.logic.exceptionVector.toInt == 2, "I-fetch access fault -> vector 2")
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x900L,
          f"EA is the fetch PC, got 0x${dut.rob.logic.exceptionFaultAddr.toLong}%x")
        assert(dut.rob.logic.exceptionFaultInstr.toBoolean, "sswInstr must survive alloc capture")
        assert(dut.rob.logic.exceptionFaultSize.toInt == 2,
          "SLICE D FIX: a re-used slot's alloc-time fault must read LONG (SSW SIZE=00), " +
          s"not the previous occupant's BYTE; got ${dut.rob.logic.exceptionFaultSize.toInt}")
        assert(!dut.rob.logic.exceptionFaultWr.toBoolean, "alloc-time fault has wr=False")
        assert(!dut.rob.logic.exceptionFaultSup.toBoolean, "alloc-time fault has sup=False")
        assert(dut.rob.logic.exceptionFaultAtc.toBoolean, "alloc-captured faultAtc")
      }
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 4. Same-robId collision: the OLD last-assign priority (fp > eu > sq > ls).
  //    Unreachable in the real core (an entry is issued to exactly one EU pipeline,
  //    and ls/sq are sequential PHASES of one store) -- pinned anyway so a future
  //    change to the fold order is caught rather than silently reordering.
  // ────────────────────────────────────────────────────────────────────────────
  test("same-robId same-cycle collision keeps the old port priority (fp > eu > sq > ls)") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      allocOne(dut, cd, 0xA00L)
      // All four ports at robId 0 in one cycle -> fp (the last in the fold) must win.
      driveLs(dut, robId = 0, addr = 0x1111L)
      driveSq(dut, robId = 0, addr = 0x2222L)
      driveEu(dut, robId = 0, vector = 6, addr = 0x3333L)
      driveFp(dut, robId = 0, vector = 51, addr = 0x4444L)
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      idleFaults(dut)
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "four-way same-robId collision") {
        assert(dut.rob.logic.exceptionVector.toInt == 51,
          s"fp is the highest-priority port, got vector ${dut.rob.logic.exceptionVector.toInt}")
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x4444L,
          f"fp's faultAddr, got 0x${dut.rob.logic.exceptionFaultAddr.toLong}%x")
      }
    }
  }

  test("eu beats sq and ls on a same-robId collision; sq beats ls") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      allocOne(dut, cd, 0xB00L)
      driveLs(dut, robId = 0, addr = 0x1111L)
      driveSq(dut, robId = 0, addr = 0x2222L)
      driveEu(dut, robId = 0, vector = 5, addr = 0x3333L)
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      idleFaults(dut)
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "ls+sq+eu same-robId collision") {
        assert(dut.rob.logic.exceptionVector.toInt == 5,
          s"eu outranks sq and ls, got vector ${dut.rob.logic.exceptionVector.toInt}")
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x3333L, "eu's faultAddr")
      }
    }
  }

  test("sq beats ls on a same-robId collision (whole record, not a per-field hybrid)") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      allocOne(dut, cd, 0xC00L)
      driveLs(dut, robId = 0, addr = 0x1111L, wr = true,  size = 2, sup = true,  atc = true)
      driveSq(dut, robId = 0, addr = 0x2222L, wr = false, size = 1, sup = false, atc = false)
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      idleFaults(dut)
      dut.rob.logic.completion(0).valid #= false

      expectException(dut, cd, "ls+sq same-robId collision") {
        assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x2222L, "sq's faultAddr")
        assert(!dut.rob.logic.exceptionFaultWr.toBoolean,  "sq's write bit, not ls's")
        assert(dut.rob.logic.exceptionFaultSize.toInt == 1, "sq's size, not ls's")
        assert(!dut.rob.logic.exceptionFaultSup.toBoolean, "sq's supervisor bit, not ls's")
        assert(!dut.rob.logic.exceptionFaultAtc.toBoolean, "sq's ATC bit, not ls's")
      }
    }
  }
}
