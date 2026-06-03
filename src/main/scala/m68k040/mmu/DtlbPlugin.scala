package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.misc.plugin.FiberPlugin

/** D-side MMU plugin: a banked DTLB + a hardware 3-level table walker behind
  * `DTranslationService`, replacing the identity stub.
  *
  *  - `mmuEnable` (test-poked; MOVEC decode deferred): when LOW the plugin is a pure
  *    identity passthrough (ppn=vpn, cacheable, no fault, always ready) — every
  *    existing MMU-disabled test/lock-step is unchanged.
  *  - When HIGH: on a translation demand (`req.valid`), look up the TLB. A HIT
  *    returns ppn/perms/cacheMode in 1 cycle (`rsp.ready`), with a perm fault flagged
  *    for a write to a write-protected page or a user access to a supervisor page. A
  *    MISS drops `rsp.ready` (the consumer stalls on its existing back-pressure path)
  *    and launches the `TableWalker`; on completion the TLB is filled (speculative
  *    fill OK) and a subsequent lookup hits. A walk that faults (non-resident /
  *    write-protect / supervisor) is served directly from a result latch with
  *    `rsp.fault` (FLAGGED, no exception delivery this slice).
  *  - The walk's deferred U/M descriptor write is exposed on `umWrite` (drained at
  *    commit by the U/M-write queue — Task 4); the DTLB does NOT write it itself.
  *
  * `rootPtr` (URP/SRP) is a test-poked register until a real MOVEC path exists. */
class DtlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks) extends FiberPlugin with DTranslationService {
  var _req: TranslationReq = null
  var _rsp: TranslationRsp = null

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  during setup {
    // Allocate the service ports in the plugin's own scope (deterministic — avoids
    // a cross-scope assignment when a consumer touches rsp before this plugin builds).
    _req = TranslationReq()
    _rsp = TranslationRsp()
    // U/M queue hooks (sibling-driven; default-idle in logic via allowOverride so a
    // standalone DUT that doesn't wire them still elaborates).
    umAccessRobId = UInt(6 bits)
    umCommitValid = Bool()
    umCommitId    = UInt(6 bits)
    umFlush       = Bool()
  }

  // control registers (test-poked; simPublic). RegInit so MMU-disabled is the
  // power-on default -> existing tests unchanged.
  var mmuEnableReg: Bool = null
  var rootPtrReg: UInt   = null
  // U/M deferred-write queue hooks (driven by the LS-cluster wiring):
  //  - umAccessRobId : robId of the access currently being translated (tags a walk's
  //    U/M write so it drains at THAT instruction's commit)
  //  - umCommit      : ROB retired this robId (mark the queued U/M write committable)
  //  - umFlush       : mispredict squash (discard speculative U/M writes)
  var umAccessRobId: UInt = null
  var umCommitValid: Bool = null
  var umCommitId:    UInt = null
  var umFlush:       Bool = null
  // AXI port for the walker + U/M descriptor write drain (full Axi4). Surfaces as
  // top IO so the testbench / synth top attaches the page-table memory.
  var walkerAxi: Axi4 = null
  def axiCfg: Axi4Config = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()
    walkerAxi = master(Axi4(axiCfg)).setName("dtlbAxi")
    walkerAxi.ar << walker.io.axi.ar
    walkerAxi.r  >> walker.io.axi.r
    // aw/w are driven by the U/M descriptor-write drain below (default idle).
    walkerAxi.aw.valid := False; walkerAxi.aw.payload.assignDontCare()
    walkerAxi.w.valid  := False; walkerAxi.w.payload.assignDontCare()
    walkerAxi.b.ready  := True

    // U/M queue hooks: default-idle (allowOverride) so a standalone DUT elaborates;
    // the LS-cluster wiring OVERRIDES them.
    umAccessRobId.allowOverride; umAccessRobId := U(0, 6 bits)
    umCommitValid.allowOverride; umCommitValid := False
    umCommitId.allowOverride;    umCommitId    := U(0, 6 bits)
    umFlush.allowOverride;       umFlush       := False

    val mmuEnable = RegInit(False); mmuEnable.simPublic()
    val rootPtr   = Reg(UInt(32 bits)) init 0; rootPtr.simPublic()
    mmuEnableReg = mmuEnable
    rootPtrReg   = rootPtr

    // ---- TLB lookup (combinational) ----
    tlb.io.lookupVpn := _req.vpn
    tlb.io.invalidateAll := False
    val tlbHit   = tlb.io.hit
    val tlbEntry = tlb.io.hitEntry

    // ---- result latch (serves the cycle(s) around a walk completion + faults) ----
    val latchValid  = RegInit(False)
    val latchVpn    = Reg(UInt(20 bits))
    val latchPpn    = Reg(UInt(20 bits))
    val latchWp     = Reg(Bool())
    val latchSup    = Reg(Bool())
    val latchCmode  = Reg(CacheMode())
    val latchFault  = Reg(Bool())

    // ---- walker control ----
    walker.io.req.vpn     := _req.vpn
    walker.io.req.rootPtr := rootPtr
    walker.io.req.isWrite := _req.write
    walker.io.req.isSuper := _req.supervisor
    walker.io.start := False

    // A walk is needed when enabled + a live demand + TLB miss + the latch doesn't
    // already hold this VPN's result + the walker is idle.
    val latchMatch = latchValid && (latchVpn === _req.vpn)
    // Suppress re-trigger on the `done` cycle: busy has dropped but the fill/latch
    // (registered) have not yet taken effect, so a naive needWalk would spuriously
    // restart the walker for the just-resolved VPN.
    val needWalk   = mmuEnable && _req.valid && !tlbHit && !latchMatch &&
                     !walker.io.busy && !walker.io.done
    // VPN a walk is servicing: latched at walk-start so the fill/latch target the
    // right VPN even if _req.vpn changes while walking.
    val walkVpn = Reg(UInt(20 bits))
    val walkRobId = Reg(UInt(6 bits))   // robId of the access that triggered the walk
    when(needWalk) {
      walker.io.start := True
      walkVpn   := _req.vpn
      walkRobId := umAccessRobId
    }

    // On walk completion: latch the result and (if no fault) fill the TLB.
    val fe = TlbEntry()
    fe.vpnTag     := 0   // set by the TLB fill from fillVpn
    fe.ppn        := walker.io.rsp.ppn
    fe.writeProt  := walker.io.rsp.writeProt
    fe.supervisor := walker.io.rsp.supervisor
    fe.cacheMode  := walker.io.rsp.cacheMode
    tlb.io.fillValid := False
    tlb.io.fillVpn   := walkVpn
    tlb.io.fillEntry := fe
    when(walker.io.done) {
      latchValid := True
      latchVpn   := walkVpn
      latchPpn   := walker.io.rsp.ppn
      latchWp    := walker.io.rsp.writeProt
      latchSup   := walker.io.rsp.supervisor
      latchCmode := walker.io.rsp.cacheMode
      latchFault := walker.io.rsp.fault
      when(!walker.io.rsp.fault) {
        tlb.io.fillValid := True
      }
    }

    // ---- deferred U/M descriptor-write queue (speculative; drained at commit) ----
    // A non-faulting walk that needs to set U (and M on a write) pushes {robId, addr,
    // newByte}; the entry drains at the triggering instruction's commit (RMW the
    // descriptor byte over this same AXI bus) and is discarded on a flush. NOT
    // performed speculatively.
    val umq = new UmWriteQueue(4)
    umq.io.alloc.valid          := walker.io.done && walker.io.rsp.umWrite.valid && !walker.io.rsp.fault
    umq.io.alloc.payload.robId  := walkRobId
    umq.io.alloc.payload.addr   := walker.io.rsp.umWrite.addr
    umq.io.alloc.payload.newByte:= walker.io.rsp.umWrite.newByte
    umq.io.commit.valid   := umCommitValid
    umq.io.commit.payload := umCommitId
    umq.io.flush          := umFlush

    // ---- U/M drain: single-byte RMW over the DTLB AXI write channel ----
    // The descriptor lives at a physical byte address; write just that byte (16-byte
    // beat with a one-hot strobe at addr[3:0]). Single-outstanding: present aw+w,
    // hold until both handshake, ack on b. The queue holds the entry until `drainAck`.
    val drainAwDone = RegInit(True)
    val drainWDone  = RegInit(True)
    val drainByteOff = umq.io.drain.payload.addr(3 downto 0)
    val drainBeat = Bits(128 bits)
    val drainStrb = Bits(16 bits)
    drainBeat := B(0, 128 bits)
    drainStrb := B(0, 16 bits)
    for (i <- 0 until 16) {
      when(drainByteOff === U(i, 4 bits)) {
        drainBeat(8 * i + 7 downto 8 * i) := umq.io.drain.payload.newByte
        drainStrb(i) := True
      }
    }
    val drainAddrReg = Reg(UInt(32 bits))
    val drainBeatReg = Reg(Bits(128 bits))
    val drainStrbReg = Reg(Bits(16 bits))
    when(umq.io.drain.valid && drainAwDone && drainWDone) {
      drainAddrReg := (umq.io.drain.payload.addr(31 downto 4) ## U(0, 4 bits)).asUInt
      drainBeatReg := drainBeat
      drainStrbReg := drainStrb
      drainAwDone  := False
      drainWDone   := False
    }
    when(!drainAwDone) {
      walkerAxi.aw.valid        := True
      walkerAxi.aw.payload.addr := drainAddrReg
      walkerAxi.aw.payload.id   := U(3, 4 bits)
      walkerAxi.aw.payload.len  := U(0, 8 bits)
      walkerAxi.aw.payload.size := U(4, 3 bits)
      walkerAxi.aw.payload.burst := spinal.lib.bus.amba4.axi.Axi4.burst.INCR
      when(walkerAxi.aw.ready) { drainAwDone := True }
    }
    when(!drainWDone) {
      walkerAxi.w.valid        := True
      walkerAxi.w.payload.data := drainBeatReg
      walkerAxi.w.payload.strb := drainStrbReg
      walkerAxi.w.payload.last := True
      when(walkerAxi.w.ready) { drainWDone := True }
    }
    // ack the queue once the write lands (b handshake). drainAck pops the entry.
    umq.io.drainAck := walkerAxi.b.valid && walkerAxi.b.ready

    // ---- response mux ----
    // hit-class perm fault: a write to a write-protected page, or a user access to
    // a supervisor page (computed from the TLB/latch perms + the access class).
    def permFault(wp: Bool, sup: Bool): Bool =
      (_req.write && wp) || (sup && !_req.supervisor)

    when(!mmuEnable) {
      // identity passthrough (existing behavior)
      _rsp.ready     := True
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.CACHEABLE
      _rsp.fault     := False
    } elsewhen(tlbHit) {
      _rsp.ready     := True
      _rsp.ppn       := tlbEntry.ppn
      _rsp.cacheMode := tlbEntry.cacheMode
      _rsp.fault     := permFault(tlbEntry.writeProt, tlbEntry.supervisor)
    } elsewhen(latchMatch) {
      // walk just resolved this VPN (fault, or the 1-cycle gap before the fill).
      _rsp.ready     := True
      _rsp.ppn       := latchPpn
      _rsp.cacheMode := latchCmode
      _rsp.fault     := latchFault || permFault(latchWp, latchSup)
    } otherwise {
      // miss: walking (rsp not ready -> consumer stalls).
      _rsp.ready     := False
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.CACHEABLE
      _rsp.fault     := False
    }
  }
}
