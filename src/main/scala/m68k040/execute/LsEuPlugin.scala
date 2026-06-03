package m68k040.execute

import m68k040.cache.{DcacheService, DLoadCmd, DStoreCmd}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import m68k040.isa.MemOp
import m68k040.ls.{StoreQueue, SqAlloc, SqFwdQuery}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Plain-wire LS EU service. Producer (IQ/test) drives `issue`; the ROB-side
  * wiring drives `sqCommit`/`sqFlush` and reads `completion`. */
trait LsEuService {
  def issue: Stream[IqContext]
  def completion: Flow[UInt]   // robId (dynamic-completion wakeup source)
  def sqCommit: Flow[UInt]     // ROB retired this store robId
  def sqFlush: Bool            // mispredict squash
  // Dynamic load-wakeup broadcast: valid (with the produced pdst) the cycle a LOAD
  // completes and its data is in the PRF. Registered alongside the completion stage
  // so consumers do not have to reach into the (now-pipelined) internal s1 context.
  def wakeup: Flow[UInt]       // pdst of a completing load (valid only when it writes a reg)
}

/** AGU + Load/Store EU (LS-1 slice): conservative single-outstanding pipe.
  *
  * S0  read base reg (psrcA) + store data (psrcB); va = base + disp(imm);
  *     request translation (vpn). M2S register.
  * S1  receive ppn -> paddr.
  *     LOAD : query SQ forward + drive dcache.loadCmd. If SQ full-overlap hit ->
  *            forwarded data. Else wait for dcache.loadRsp (refill back-pressures
  *            via dcache.loadBusy). On data ready -> int PRF write + completion
  *            (the dynamic wakeup) + wbObs.
  *     STORE: sq.alloc(robId, paddr, data, size); completion fires (store
  *            "executes" == SQ-allocated; it drains at commit).
  * issue.ready deasserts while a load is in flight (busy) -> EU is occupied. */
class LsEuPlugin extends FiberPlugin with LsEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[UInt]   = null
  var sqCommitPort: Flow[UInt]     = null
  var sqFlushSig: Bool             = null
  var wakeupPort: Flow[UInt]       = null
  var rdBase, rdData: RegFileReadPort = null
  var intW: RegFileWritePort = null
  var intByp: RegFileBypassPort = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def sqCommit: Flow[UInt]     = sqCommitPort
  override def sqFlush: Bool            = sqFlushSig
  override def wakeup: Flow[UInt]       = wakeupPort

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    sqCommitPort   = Flow(UInt(6 bits))
    sqFlushSig     = Bool()
    wakeupPort     = Flow(UInt(6 bits))
    val irf = host[IntRegFileService]
    rdBase = irf.newRead()
    rdData = irf.newRead()
    intW   = irf.newWrite(latency = 1)
    intByp = irf.newBypass()
  }

  val logic = during build new Area {
    val dcache = host[DcacheService]
    val xlate  = host[DTranslationService]

    // ---- store queue instance ----
    val sq = new StoreQueue(8)
    sq.io.commit << sqCommitPort
    sq.io.flush  := sqFlushSig
    dcache.store << sq.io.drain
    sq.io.drainAck := dcache.storeAck   // pop a drained entry only once memory is written

    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdBase.addr := u0.psrcA
    rdData.addr := u0.psrcB
    // Address = base + disp. Absolute / PC-relative EAs carry NO base register
    // (psrcAValid=false; the assembler folded the absolute/PC value into imm), so
    // the base contribution must be ZERO there — otherwise the stale psrcA (which
    // defaults to physreg 0) would corrupt the computed address.
    val base0 = Mux(u0.psrcAValid, rdBase.data.asUInt, U(0, 32 bits))
    val disp0 = u0.imm.asSInt
    val va0   = (base0.asSInt + disp0).asUInt
    val data0 = rdData.data

    // ---- AGU cross-line / cross-page detection (S0, combinational) ----
    // sizeBytes from the access size (1/2/4). A single m68k access spans at most
    // two 16-byte lines / two 4 KB pages, so a single "second access" suffices.
    def sizeBytes(s: m68k040.isa.Size.C): UInt = {
      val n = UInt(3 bits); n := 1
      switch(s) {
        is(m68k040.isa.Size.BYTE) { n := 1 }
        is(m68k040.isa.Size.WORD) { n := 2 }
        is(m68k040.isa.Size.LONG) { n := 4 }
      }
      n
    }
    val nBytes0    = sizeBytes(u0.size)
    val lineOff0   = va0(3 downto 0)
    val pageOff0   = va0(11 downto 0)
    val crossLine0 = (lineOff0 +^ nBytes0) > U(16)
    val crossPage0 = (pageOff0 +^ nBytes0) > U(4096)
    val twoAccess0 = crossLine0 || crossPage0
    // addrB = next line base = (va & ~15) + 16. When crossPage this equals the
    // next page base (the line that crosses the page boundary is the page-aligned
    // first line of the next page).
    val addrB0     = (va0 & ~U(15, 32 bits)) + 16

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegInit(False)
    val s1Ctx   = Reg(IqContext())
    val s1Va    = Reg(UInt(32 bits))
    val s1Data  = Reg(Bits(32 bits))
    // Registered cross-detection (alongside s1Va). simPublic for directed probing.
    val s1CrossLine = RegInit(False)
    val s1CrossPage = RegInit(False)
    val s1TwoAccess = RegInit(False)
    val s1AddrB     = Reg(UInt(32 bits))
    s1CrossLine.simPublic(); s1CrossPage.simPublic(); s1TwoAccess.simPublic(); s1AddrB.simPublic()
    val u1 = s1Ctx.uop

    // Translation is driven by the D-cache from loadCmd.payload.vaddr (which we
    // set to s1Va unconditionally below), so we just consume xlate.rsp here for
    // the physical address used by the SQ query/alloc. (Single TranslationService:
    // the cache owns the request port; we read the response.)
    val s1Paddr = (xlate.rsp.ppn ## s1Va(11 downto 0)).asUInt

    // ---- dcache load cmd defaults ----
    dcache.loadCmd.valid        := False
    dcache.loadCmd.payload.vaddr := s1Va
    dcache.loadCmd.payload.size  := u1.size

    // ---- SQ alloc + fwd defaults ----
    sq.io.alloc.valid          := False
    sq.io.alloc.payload.robId  := s1Ctx.robId
    sq.io.alloc.payload.paddr  := s1Paddr
    sq.io.alloc.payload.data   := s1Data
    sq.io.alloc.payload.size   := u1.size
    sq.io.fwd.query.robId := s1Ctx.robId
    sq.io.fwd.query.paddr := s1Paddr
    sq.io.fwd.query.size  := u1.size

    val isLoad  = u1.memOp === MemOp.LOAD
    val isStore = u1.memOp === MemOp.STORE

    // ─────────────────────────────────────────────────────────────────────────
    // FMax: registered COMPLETION + WRITEBACK stage.
    //
    // The decision (SQ-forward compare / store-alloc / cache hit-detect) is a long
    // combinational arc off s1Paddr. Previously it fed completionPort/intW/wbObs and
    // hence ROB.completes in the SAME cycle (the 21-level, 72%-route critical path).
    // We now CAPTURE the decision result into registers in the decision cycle and
    // DRIVE completion/writeback/wakeup from those registers the NEXT cycle (one
    // mux level: reg -> port). This splits the arc into
    //   s1Paddr -> SQ-fwd/cache-hit -> comp* reg   (short)
    //   comp* reg -> completion -> ROB.completes    (short)
    // at the cost of ONE extra completion-latency cycle (lock-step is latency-
    // agnostic). All comp* are RegInit/Reg (no uninit fanout).
    val compValid     = RegInit(False)
    val compRobId     = Reg(UInt(6 bits))
    val compData      = Reg(Bits(32 bits))
    val compPdst      = Reg(UInt(6 bits))
    val compPdstValid = RegInit(False)
    val compIsLoad    = RegInit(False)   // load (writes a reg + wakes) vs store
    val compDstArch   = Reg(UInt(5 bits))

    // ─────────────────────────────────────────────────────────────────────────
    // FMax #2: PIPELINE the SQ-forward query result -> completion decision.
    //
    // The 8-entry SQ overlap-compare + youngest-full-overlap `best` reduce off
    // `s1Paddr` (== s1Va_reg -> xlate -> paddr) feeding compData/compValid in the
    // SAME cycle was the 17-level, 70%-route critical path (s1Va_reg ->
    // SQ-compare -> compData_reg/CE). We REGISTER the SQ forward response
    // (hit/data/stall) for a load in the decision cycle, and DRIVE the completion
    // capture from those registers the NEXT cycle (a RESOLVE state). This splits
    //   s1Va_reg -> s1Paddr -> SQ-fwd-compare -> fwd* reg          (short)
    //   fwd* reg -> captureCompletion -> compData_reg              (short, 1 mux)
    // at the cost of ONE extra load-latency cycle (lock-step is latency-agnostic).
    //
    // SAFE under single-outstanding (issue.ready := !busy && !s1Valid &&
    // !compValid): at most one LS µop is ever in the forward/resolve stages, and
    // the s1 context (hence the SQ query) is held stable across the registered
    // query and its RESOLVE use (busy holds s1Valid; no concurrent store-alloc in
    // this pipe; a draining entry is held-resident-until-ack so it keeps
    // forwarding). A wrong-path load still completes but the ROB filters its
    // completion by robId, exactly as before this retime (the LS EU did not gate
    // the in-flight load on flush previously either). All RegInit / Reg (no uninit
    // fanout).
    val fwdHit   = RegInit(False)
    val fwdStall = RegInit(False)
    val fwdData  = Reg(Bits(32 bits))

    // captured-decision -> register (called in the decision cycle)
    def captureCompletion(result: Bits): Unit = {
      compValid     := True
      compRobId     := s1Ctx.robId
      compData      := result
      compPdst      := u1.pdst
      compPdstValid := u1.pdstValid
      compIsLoad    := isLoad
      compDstArch   := u1.dstArch
    }

    // ---- drive completion / writeback / wakeup from the registered stage ----
    completionPort.valid   := compValid
    completionPort.payload := compRobId
    intW.valid     := compValid && compPdstValid
    intW.address   := compPdst
    intW.data      := compData
    intByp.valid   := compValid && compPdstValid
    intByp.address := compPdst
    intByp.data    := compData
    // Dynamic load-wakeup: only a completing LOAD that produces a physreg broadcasts
    // (a store completes too but writes no register — its pdst is stale).
    wakeupPort.valid   := compValid && compIsLoad && compPdstValid
    wakeupPort.payload := compPdst

    val busy = RegInit(False)
    // Single-outstanding: do not accept a new µop while a decision is pending
    // (s1Valid), a load is in flight (busy), or a registered completion is occupying
    // the writeback stage this cycle (compValid). compValid is a 1-cycle pulse, so
    // this only stalls issue for that one extra cycle.
    issuePort.ready := !busy && !s1Valid && !compValid

    // S0 -> S1 advance (only when not busy in a wait state)
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1Va    := va0
      s1Data  := data0
      s1CrossLine := crossLine0
      s1CrossPage := crossPage0
      s1TwoAccess := twoAccess0
      s1AddrB     := addrB0
    } otherwise {
      when(!busy) { s1Valid := False }
    }

    // compValid is a single-cycle pulse: default-clear, re-set only by a capture.
    compValid := False

    val fsm = new StateMachine {
      val IDLE    = new State with EntryPoint
      val RESOLVE = new State  // registered SQ-fwd result -> completion / cache launch
      val WAIT    = new State   // cache load cmd accepted, awaiting loadRsp

      IDLE.whenIsActive {
        busy := False
        when(s1Valid) {
          when(isStore) {
            // allocate into the SQ; "executes" immediately (no int dst). Store
            // completion drives a CONSTANT compData (off the SQ-compare arc), so it
            // captures here directly without the extra resolve cycle.
            sq.io.alloc.valid := True
            captureCompletion(B(0, 32 bits))
          } elsewhen(isLoad) {
            // Capture the SQ-forward compare result into registers; resolve next
            // cycle. Hold s1 (busy) so the SQ query stays stable into RESOLVE.
            fwdHit   := sq.io.fwd.rsp.hit
            fwdStall := sq.io.fwd.rsp.stall
            fwdData  := sq.io.fwd.rsp.data
            busy := True
            goto(RESOLVE)
          } otherwise {
            captureCompletion(B(0, 32 bits))   // non-memory (defensive)
          }
        }
      }

      // Decision-resolve from the REGISTERED SQ-forward result (off the long
      // s1Paddr -> SQ-compare arc). s1 is still held (busy), so a re-query for the
      // stall-retry path reads stable SQ content.
      RESOLVE.whenIsActive {
        busy := True
        when(fwdHit) {
          // full-overlap forward: skip the cache.
          captureCompletion(fwdData)
          busy    := False
          s1Valid := False
          goto(IDLE)
        } elsewhen(fwdStall) {
          // partial/ambiguous overlap: re-sample the SQ this cycle and retry. The
          // overlapping older store will drain/forward; once it resolves to a full
          // forward (or no overlap) we proceed. Stays in a (busy) hold.
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
        } otherwise {
          // no forward: drive the cache load; back-pressure on dcache.loadBusy.
          dcache.loadCmd.valid := True
          when(dcache.loadCmd.fire) {
            goto(WAIT)
          }
          // else cache occupied; stay in RESOLVE (busy) and retry next cycle.
        }
      }

      // cmd accepted; the dcache delivers exactly one loadRsp (fixed for a hit,
      // late after a refill). Do NOT re-drive loadCmd here. Route the refilled
      // load through the SAME registered completion stage for uniformity.
      WAIT.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid) {
          captureCompletion(dcache.loadRsp.payload.data)
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }
    }

    // ---- wbObs (sim-only whitebox) ----
    // Driven straight from the registered completion stage (already a register), so
    // the observed writeback aligns exactly with the completion/wakeup the ROB sees.
    val wbObs = WbObs()
    wbObs.valid     := compValid
    wbObs.robId     := compRobId
    wbObs.dstArch   := compDstArch
    wbObs.result    := compData
    wbObs.intWrite  := compPdstValid
    wbObs.nzvc      := B(0, 4 bits)
    wbObs.nzvcWrite := False
    wbObs.x         := False
    wbObs.xWrite    := False
    wbObs.simPublic()
  }
}
