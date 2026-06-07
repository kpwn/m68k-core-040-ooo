package m68k040.execute

import m68k040.cache.{DcacheService, DLoadCmd, DStoreCmd}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
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
  // robId of the access currently being translated (tags a DTLB walk's deferred U/M
  // descriptor write so it drains at THAT instruction's commit).
  def xlateRobId: UInt
  // MMU access-fault completion: the cycle an LS access takes a DTLB rsp.fault, mark
  // its ROB entry FAULTED (vector 2, access fault) with the faulting VA + the SSW
  // access attributes {write, sizeBits, supervisor}. The ROB consumes this like
  // branchCompletion (records per-entry, raises the precise exception at retire).
  def faultCompletion: Flow[LsFault]
}

/** LS access-fault completion payload: which ROB entry faulted (vector 2 implied),
  * the faulting VA, and the SSW access attributes. `write` = store (R/W=write),
  * `sizeBits` = encoded access size (00=byte,01=word,10=long), `supervisor` = the
  * access function-code supervisor bit. */
case class LsFault() extends Bundle {
  val robId      = UInt(6 bits)
  val faultAddr  = UInt(32 bits)
  val write      = Bool()
  val sizeBits   = UInt(2 bits)
  val supervisor = Bool()
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
  var faultCompletionPort: Flow[LsFault] = null
  var rdBase, rdData: RegFileReadPort = null
  var intW: RegFileWritePort = null
  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null
  var nzvcByp: RegFileBypassPort = null
  // X-flag write/bypass for the RTR CCR-restore load (X := loaded[4]).
  var xW: RegFileWritePort = null
  var xByp: RegFileBypassPort = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def sqCommit: Flow[UInt]     = sqCommitPort
  override def sqFlush: Bool            = sqFlushSig
  override def wakeup: Flow[UInt]       = wakeupPort
  override def faultCompletion: Flow[LsFault] = faultCompletionPort
  var xlateRobIdSig: UInt = null
  override def xlateRobId: UInt         = xlateRobIdSig

  // ── Exception-unit cache arbitration (full-core wiring drives these) ─────────
  // While `excActive`, the commit-side ExceptionUnit owns the D-cache + D-TLB
  // request ports (the LS pipe is squashed/idle — serializing). The LS EU MUXes
  // these exc requests onto the cache it already owns. Default-idle (allowOverride)
  // so standalone LS tests / a DUT that doesn't wire them are unchanged.
  var excActive: Bool = null
  var excLoadCmdValid: Bool = null; var excLoadCmdVaddr: UInt = null; var excLoadCmdSize: m68k040.isa.Size.C = null
  var excLoadCmdReady: Bool = null
  var excStoreValid: Bool = null;   var excStorePayload: DStoreCmd = null
  var excXlateValid: Bool = null;   var excXlateVpn: UInt = null
  var excXlateWrite: Bool = null;   var excXlateSupervisor: Bool = null
  var sqEmptySig: Bool = null   // store queue drained (no committed store in flight)

  during setup {
    excActive       = Bool()
    excLoadCmdValid = Bool(); excLoadCmdVaddr = UInt(32 bits); excLoadCmdSize = m68k040.isa.Size()
    excLoadCmdReady = Bool()
    excStoreValid   = Bool(); excStorePayload = DStoreCmd()
    excXlateValid   = Bool(); excXlateVpn = UInt(20 bits)
    excXlateWrite   = Bool(); excXlateSupervisor = Bool()
    sqEmptySig      = Bool()
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    sqCommitPort   = Flow(UInt(6 bits))
    sqFlushSig     = Bool()
    wakeupPort     = Flow(UInt(6 bits))
    faultCompletionPort = Flow(LsFault()); faultCompletionPort.simPublic()
    xlateRobIdSig  = UInt(6 bits)
    val irf = host[IntRegFileService]
    rdBase = irf.newRead()
    rdData = irf.newRead()
    intW   = irf.newWrite(latency = 1)
    intByp = irf.newBypass()
    // MOVE-to-memory sets NZVC (impl (a)): the LS EU writes the NZVC PRF + bypass at
    // store completion. rename allocates the store µop a unique pNzvcDst, so this
    // distinct physical write port never collides with the ALU EUs' NZVC writers.
    val nz = host[NzvcRegFileService]
    nzvcW   = nz.newWrite(latency = 1)
    nzvcByp = nz.newBypass()
    // X write/bypass for the RTR CCR-restore load (X := loaded[4]). rename gives the
    // ccr-restore load a unique pXDst, so this is a distinct physical X write port.
    val xrf = host[XRegFileService]
    xW   = xrf.newWrite(latency = 1)
    xByp = xrf.newBypass()
  }

  val logic = during build new Area {
    val dcache = host[DcacheService]
    val xlate  = host[DTranslationService]

    // exc-arbitration inputs default-idle (allowOverride): a DUT that doesn't wire
    // the exception unit (standalone LS tests) sees excActive=False -> the LS EU
    // owns the cache exactly as before.
    excActive.allowOverride;            excActive := False
    excLoadCmdValid.allowOverride;      excLoadCmdValid := False
    excLoadCmdVaddr.allowOverride;      excLoadCmdVaddr := U(0, 32 bits)
    excLoadCmdSize.allowOverride;       excLoadCmdSize := m68k040.isa.Size.LONG
    excStoreValid.allowOverride;        excStoreValid := False
    excStorePayload.allowOverride;      excStorePayload.assignDontCare()
    excXlateValid.allowOverride;        excXlateValid := False
    excXlateVpn.allowOverride;          excXlateVpn := U(0, 20 bits)
    excXlateWrite.allowOverride;        excXlateWrite := False
    excXlateSupervisor.allowOverride;   excXlateSupervisor := True
    excLoadCmdReady.allowOverride;      excLoadCmdReady := False

    // ---- store queue instance ----
    val sq = new StoreQueue(8)
    sq.io.commit << sqCommitPort
    sq.io.flush  := sqFlushSig
    dcache.store << sq.io.drain
    sq.io.drainAck := dcache.storeAck   // pop a drained entry only once memory is written
    sqEmptySig := sq.io.empty           // surfaced for the exception FSM's drain wait

    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdBase.addr := u0.psrcA
    rdData.addr := u0.psrcB
    // Address = base + disp. Absolute / PC-relative EAs carry NO base register
    // (psrcAValid=false; the assembler folded the absolute/PC value into imm), so
    // the base contribution must be ZERO there — otherwise the stale psrcA (which
    // defaults to physreg 0) would corrupt the computed address.
    // FMax: the AGU BASE operand crosses the ALU->LS boundary COMBINATIONALLY — the
    // ALU EU's S1 result bypasses (intByp) into rdBase.data the same cycle a dependent
    // LS µop reads it. Computing `va0 = base + disp` here in S0 therefore chained the
    // ALU datapath adder + the AGU adder across the boundary into the `s1Va` flop (the
    // 26-level, 3x CARRY8, -2.375ns OOC worst path: AluEu.s1Src2 -> ALU result ->
    // bypass -> base0 -> va0 -> s1Va). FIX (standing rule: registered module
    // boundaries): REGISTER the bypassed base operand at the LS boundary (`s1Base`),
    // and compute the effective address `s1Va` in S1 off that flop + the shallow
    // (uop-derived, no-bypass) displacement. The ALU->base cone now ENDS at `s1Base`;
    // the AGU adder is a SEPARATE shallow stage (s1Base_reg + disp -> s1Va), not
    // chained onto the ALU's result. (Latency-agnostic: lock-step is instruction-level
    // and the EU is single-outstanding.)
    val base0 = Mux(u0.psrcAValid, rdBase.data.asUInt, U(0, 32 bits))
    // STORE DATA: `imm` (retPC) for a stack-push (BSR/JSR predecrement), else the
    // (possibly bypassed) source register. Captured into `s1Data` at the boundary; it
    // is NOT on the AGU cone (feeds only the SQ entry), so it stays a registered value.
    val data0 = Mux(u0.stkPush, u0.imm, rdData.data)   // push: data = retPC (imm)

    // ---- access size in bytes (1/2/4) ----
    // A single m68k access spans at most two 16-byte lines / two 4 KB pages, so a
    // single "second access" suffices. Used by the S1 cross-detection (below) and
    // the store byte-lane split.
    def sizeBytes(s: m68k040.isa.Size.C): UInt = {
      val n = UInt(3 bits); n := 1
      switch(s) {
        is(m68k040.isa.Size.BYTE) { n := 1 }
        is(m68k040.isa.Size.WORD) { n := 2 }
        is(m68k040.isa.Size.LONG) { n := 4 }
      }
      n
    }

    // ---- S0 -> S1 register (M2S) ----
    // `s1Base` is the REGISTERED (post-bypass) AGU base operand; the deep ALU->base
    // cone ends here. `s1Data` is the registered store data (off the AGU cone).
    val s1Valid = RegInit(False)
    val s1Ctx   = Reg(IqContext())
    val s1Base  = Reg(UInt(32 bits))
    val s1Data  = Reg(Bits(32 bits))
    val u1 = s1Ctx.uop

    // ---- S1: AGU effective address (off the REGISTERED base) ----
    // addr = base + disp. STACK-PUSH (BSR/JSR) predecrements: disp = -sizeBytes (the
    // store data is `imm`=retPC, handled via data0 -> s1Data). Otherwise disp = imm.
    // Both come from the registered uop (`u1`) — no bypass — so `s1Va` is a SINGLE
    // shallow adder off the `s1Base` flop, not chained onto the ALU result. `s1Va` is
    // combinational off held flops (s1Base/s1Ctx are held stable while busy), so every
    // FSM consumer (IDLE+ cycles) reads a consistent address; the translated paddr is
    // registered downstream in s2Paddr/s2PaddrB.
    def szBytes1(s: m68k040.isa.Size.C): SInt = {
      val n = SInt(32 bits); n := 1
      switch(s) {
        is(m68k040.isa.Size.BYTE) { n := 1 }
        is(m68k040.isa.Size.WORD) { n := 2 }
        is(m68k040.isa.Size.LONG) { n := 4 }
      }
      n
    }
    val s1Disp = Mux(u1.stkPush, -szBytes1(u1.size), u1.imm.asSInt)
    val s1Va   = (s1Base.asSInt + s1Disp).asUInt

    // ---- AGU cross-line / cross-page detection (S1, off s1Va) ----
    // The cross-detection / next-line base USED to be computed in S0 off the
    // combinational `va0` (= base+disp) and latched into s1* the same cycle, which —
    // because `base0` bypasses combinationally from the ALU — fused the +16 / cross
    // compares onto the ALU->va chain (2 of the original 4 chained CARRY8 adders). They
    // are now derived off `s1Va` (itself a shallow adder off the `s1Base` flop), so the
    // next-line base / cross compares are a SEPARATE shallow stage downstream of the
    // registered base — NOT chained onto the ALU result. `s1Va` is stable across the
    // FSM (s1Base is held while busy; issue.ready gates a new latch), so every FSM
    // consumer (IDLE+ cycles) reads consistent values; the slot-B paddr is registered
    // downstream in `s2PaddrB` (IDLE). simPublic for directed probing (AguCrossSpec
    // samples one cycle after the S1 latch, where s1Base/s1Va are valid+stable, so the
    // comb values match the old registered ones exactly).
    val nBytes1    = sizeBytes(u1.size)
    val lineOff1   = s1Va(3 downto 0)
    val pageOff1   = s1Va(11 downto 0)
    val s1CrossLine = (lineOff1 +^ nBytes1) > U(16)
    val s1CrossPage = (pageOff1 +^ nBytes1) > U(4096)
    val s1TwoAccess = s1CrossLine || s1CrossPage
    // addrB = next line base = (s1Va & ~15) + 16. When crossPage this equals the next
    // page base (the line that crosses the page boundary is the page-aligned first
    // line of the next page).
    val s1AddrB     = (s1Va & ~U(15, 32 bits)) + 16
    s1CrossLine.simPublic(); s1CrossPage.simPublic(); s1TwoAccess.simPublic(); s1AddrB.simPublic()

    // ---- translate-at-execute: the LS EU DRIVES the D-side translation port ----
    // It presents the access VPN (loadVaddr, which the FSM sets to s1Va for slot A
    // or s1AddrB for slot B), the access class (write?=store, supervisor?), and
    // `valid` (a real demand). The D-cache READS the response (rsp.ppn for its load
    // hit-tag, rsp.ready to gate load acceptance). On a DTLB miss `rsp.ready` is
    // False while the walker runs; the LS EU stalls (does NOT alloc a store / does
    // NOT accept the load) on its existing single-outstanding path until ready.
    // (req drivers are set after loadVaddr is declared, below.)
    val s1Paddr = (xlate.rsp.ppn ## s1Va(11 downto 0)).asUInt
    val xlateReady = xlate.rsp.ready    // False on an enabled-MMU TLB miss (walking)
    val xlateFault = xlate.rsp.fault

    // ─────────────────────────────────────────────────────────────────────────
    // FMax #3: PIPELINE the DTLB lookup -> SQ-forward / store-alloc.
    //
    // With mmuEnable LIVE the DTLB hit-path lookup (s1Va -> banked way-mux -> ppn)
    // sits IN FRONT of the s1Paddr that feeds the 8-entry SQ overlap-compare reduce
    // (-> fwdData). That fused arc (s1Va_reg -> DTLB -> s1Paddr -> SQ-compare ->
    // fwdData_reg) was the 25-level / 6.349ns critical path (157MHz). We REGISTER
    // the translated physical address (+perm fault) in a dedicated translate stage
    // (XLATE), so the SQ-forward query / store-alloc consume the REGISTERED paddr
    // the NEXT cycle. This splits the arc into
    //   s1Va_reg -> DTLB lookup -> s2Paddr_reg                 (translate stage)
    //   s2Paddr_reg -> SQ-compare -> fwd*_reg / alloc          (forward stage)
    // at the cost of ONE extra translate-latency cycle (lock-step is latency-
    // agnostic; the EU is single-outstanding and already stalls the walker on a
    // DTLB miss). Identity (MMU-disabled) flows through the SAME register so both
    // modes are pipelined uniformly. All RegInit / Reg (no uninit fanout).
    val s2Paddr  = Reg(UInt(32 bits))
    val s2PaddrB = Reg(UInt(32 bits))
    val s2Fault  = RegInit(False)

    // ---- dcache load cmd defaults ----
    // The FSM selects the access address: slot A = s1Va, slot B = s1AddrB (the
    // next-line / next-page base). The cache translates whatever vaddr we present
    // (its xlateVpn := loadCmd.vaddr[31:12]), so driving s1AddrB here naturally
    // issues slot B's translation (the next page's VPN on a page-cross, the same
    // page on a line-cross). We always issue size=LONG on a cross access so the
    // cache reads/extracts the whole line slice; the merge selects the bytes.
    val loadVaddr = UInt(32 bits)
    val loadPaddr = UInt(32 bits)
    loadVaddr := s1Va
    loadPaddr := s2Paddr          // REGISTERED physical address (slot A); slot B uses s2PaddrB
    dcache.loadCmd.valid        := False
    dcache.loadCmd.payload.vaddr := loadVaddr
    dcache.loadCmd.payload.paddr := loadPaddr
    dcache.loadCmd.payload.size  := u1.size

    // ---- store split (byte-lane) for the SQ entry ----
    // Under identity translation paddr == vaddr, so slot B's physical address is
    // s1AddrB directly (the real-DTLB slice will translate addrB's VPN separately).
    // sizeBytes of the store; bytes in slot A = (16 - lineOffSt), spill -> slot B.
    val stOff      = s1Va(3 downto 0)
    val stBytes    = sizeBytes(u1.size)                 // 1/2/4
    val bytesInA   = (U(16) - stOff.resize(5 bits))     // 1..16
    val nbytesA_st = Mux(s1TwoAccess, bytesInA.resize(3 bits), stBytes)
    val nbytesB_st = Mux(s1TwoAccess, (stBytes - bytesInA).resize(3 bits), U(0, 3 bits))
    val splitDataA = m68k040.cache.DcacheByteLane.storeDataA(stOff, u1.size, s1Data)
    val splitStrbA = m68k040.cache.DcacheByteLane.storeStrbA(stOff, u1.size)
    val splitDataB = m68k040.cache.DcacheByteLane.storeDataB(stOff, u1.size, s1Data)
    val splitStrbB = m68k040.cache.DcacheByteLane.storeStrbB(stOff, u1.size)
    val s1PaddrB   = s1AddrB   // identity translation

    // ---- SQ alloc + fwd defaults ----
    sq.io.alloc.valid          := False
    sq.io.alloc.payload.robId  := s1Ctx.robId
    sq.io.alloc.payload.paddr  := s2Paddr
    sq.io.alloc.payload.data   := s1Data
    sq.io.alloc.payload.size   := u1.size
    sq.io.alloc.payload.nbytesA   := nbytesA_st
    // Aligned store: drain via {data,size} (fast path). Split store: explicit strobe.
    sq.io.alloc.payload.useStrbA  := s1TwoAccess
    sq.io.alloc.payload.strbA     := splitStrbA
    sq.io.alloc.payload.lineDataA := splitDataA
    sq.io.alloc.payload.validB    := s1TwoAccess
    sq.io.alloc.payload.paddrB    := s2PaddrB
    sq.io.alloc.payload.nbytesB   := nbytesB_st
    sq.io.alloc.payload.strbB     := splitStrbB
    sq.io.alloc.payload.lineDataB := splitDataB
    sq.io.fwd.query.robId := s1Ctx.robId
    sq.io.fwd.query.paddr := s2Paddr
    sq.io.fwd.query.size  := u1.size

    val isLoad  = u1.memOp === MemOp.LOAD
    val isStore = u1.memOp === MemOp.STORE

    // ---- drive the D-side translation request (translate-at-execute) ----
    // VPN = the access address the EU is currently presenting (loadVaddr tracks
    // s1Va for slot A / s1AddrB for slot B). `valid` asserts whenever a memory µop
    // is resident in S1 (a real translation demand -> the DTLB may walk on a miss).
    // `write` selects the store M-bit / write-protect check. supervisor=False (user
    // accesses this slice; MOVEC/SR-source deferred).
    xlate.req.valid      := s1Valid && (isLoad || isStore)
    xlate.req.vpn        := loadVaddr(31 downto 12)
    xlate.req.supervisor := False
    xlate.req.write      := isStore
    xlateRobIdSig        := s1Ctx.robId

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
    // A STACK-PUSH store produces an int reg (the predecremented A7) — unlike a plain
    // store. It must write the int PRF AND broadcast a wakeup (a consumer of the new
    // A7 — e.g. a following push/pop — waits on it). `compWakes` gates the wakeup for
    // BOTH a load and a stkPush store; `compStkPush` selects the int write (= s1Va).
    val compWakes     = RegInit(False)
    // A STACK-PUSH store is a CRACK µop of BSR/JSR (the macro instruction's single
    // architectural commit is the trailing branch). The lock-step whitebox DROPS its
    // commit record (like DIVREM) but STILL folds its A7 write into the running A7.
    val compStkPush   = RegInit(False)
    // RTR CCR-restore load: also a CRACK µop (the RTR macro instruction's single commit
    // is the trailing ibranch) -> DROP its commit record (like stkPush) but still fold
    // its CCR (NZVC/X) into the running architectural CCR.
    val compCcrRestore = RegInit(False)
    // A mem-dest RMW / CLR TRAILING store (the macro instruction's single architectural
    // commit is the op µop, which carries the PC + flags). A trailing RMW store writes
    // NEITHER an int reg NOR flags (the op µop owns NZVCX) — unlike a MOVE-to-mem store
    // (writes NZVC) or a stack-push store (writes A7). DROP its commit record (it has no
    // architectural register/flag effect; the memory effect is checked via checkMem).
    val compRmwStore  = RegInit(False)
    val compDstArch   = Reg(UInt(5 bits))
    // NZVC writeback for a MOVE-to-memory store (N/Z of the moved value, V=C=0).
    val compNzvc      = Reg(Bits(4 bits))
    val compNzvcWrite = RegInit(False)
    val compNzvcDst   = Reg(UInt(nzvcW.address.getWidth bits))
    // RTR CCR-restore (X := loaded[4]); NZVC := loaded[3:0] reuses compNzvc.
    val compX         = RegInit(False)
    val compXWrite    = RegInit(False)
    val compXDst      = Reg(UInt(xW.address.getWidth bits))
    // MMU access-FAULT completion: when an access takes a DTLB rsp.fault, it still
    // COMPLETES (compValid -> the ROB marks the entry done so it can retire), but as
    // a FAULT: it writes NO register / allocs NO store / wakes nothing, and drives
    // faultCompletion {robId, faultAddr, write, sizeBits, supervisor} so the ROB
    // flags the entry (vector 2) for precise delivery at retire. RegInit(False) so an
    // unfaulted access never spuriously flags. Captured alongside the comp* stage.
    val compIsFault   = RegInit(False)
    val compFaultAddr = Reg(UInt(32 bits))
    val compFaultWr   = RegInit(False)
    val compFaultSize = Reg(UInt(2 bits))
    val compFaultSup  = RegInit(False)

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

    // ---- two-access (cross-line / cross-page) capture ----
    // The captured 128-bit line from slot A; slot B's line arrives in WAIT_B and
    // is merged with it. `lineOff` is the byte offset of the access within line A.
    // `aDone` records that slot A's line was captured (loadRsp is a 1-cycle pulse,
    // so we latch it and then keep driving slot B's cmd until the cache accepts it).
    val lineA    = Reg(Bits(128 bits))
    val aDone    = RegInit(False)
    val lineOff  = s1Va(3 downto 0)

    // ---- MOVE-to-memory NZVC (computed from the store data at the access size) ----
    // m68k MOVE sets N = sign bit of the moved value at the size, Z = (value==0 over
    // the size's bytes), V = 0, C = 0. The store data (s1Data) holds the source
    // register; only the low `size` bytes are written / observed. Computed off the
    // already-registered s1Data (no new long arc).
    val stN = u1.size.mux(
      m68k040.isa.Size.BYTE -> s1Data(7),
      m68k040.isa.Size.WORD -> s1Data(15),
      m68k040.isa.Size.LONG -> s1Data(31))
    val stZ = u1.size.mux(
      m68k040.isa.Size.BYTE -> (s1Data(7 downto 0)  === 0),
      m68k040.isa.Size.WORD -> (s1Data(15 downto 0) === 0),
      m68k040.isa.Size.LONG -> (s1Data === 0))
    val storeNzvc = stN ## stZ ## False ## False   // N Z V(0) C(0)

    // captured-decision -> register (called in the decision cycle). A STACK-PUSH store
    // writes its int dst (A7) with the PREDECREMENTED address (s1Va) — not the load
    // `result` — and wakes consumers of A7.
    def captureCompletion(result: Bits): Unit = {
      compValid     := True
      compRobId     := s1Ctx.robId
      compData      := Mux(u1.stkPush, s1Va.asBits, result)
      // A CCR-restore load writes NO int reg (it restores flags); a stack-push store's
      // int dst is A7 (handled via compData above); a plain load writes its int dst.
      compPdst      := u1.pdst
      compPdstValid := u1.pdstValid && !u1.ccrRestore
      compIsLoad    := isLoad
      // Wake an int-producing load / stkPush store. A CCR-restore load produces no int
      // reg, so it must NOT broadcast a (stale-pdst) wakeup.
      compWakes     := (isLoad && !u1.ccrRestore) || u1.stkPush
      compStkPush   := u1.stkPush
      compCcrRestore := u1.ccrRestore
      // Trailing RMW/CLR store: a STORE writing neither an int reg nor flags.
      compRmwStore  := isStore && !u1.pdstValid && !u1.writesNzvc && !u1.stkPush
      compDstArch   := u1.dstArch
      // CCR-restore (RTR): NZVC := loaded[3:0], X := loaded[4] (CCR bit layout
      // X=4,N=3,Z=2,V=1,C=0). Otherwise a MOVE-to-mem store's NZVC = N/Z of the stored
      // value (V=C=0). A plain load writes neither (u1.writesNzvc/X are False).
      compNzvc      := Mux(u1.ccrRestore, result(3 downto 0), storeNzvc)
      compNzvcWrite := u1.writesNzvc
      compNzvcDst   := u1.pNzvcDst
      compX         := result(4)
      compXWrite    := u1.writesX
      compXDst      := u1.pXDst
      compIsFault   := False
    }

    // captured-FAULT -> register (called in the decision cycle when an access takes a
    // DTLB rsp.fault). Completes (compValid) so the ROB marks done, but flagged as a
    // fault (no reg write / no store alloc / no wakeup); drives faultCompletion. The
    // SSW attrs: write = store, sizeBits = encoded access size, supervisor = the
    // access function-code supervisor bit (xlate.req.supervisor for this access).
    def captureFault(): Unit = {
      compValid     := True
      compRobId     := s1Ctx.robId
      compPdstValid := False
      compNzvcWrite := False
      compXWrite    := False
      compIsLoad    := False
      compWakes     := False
      compStkPush   := False
      compCcrRestore := False
      compIsFault   := True
      compFaultAddr := s1Va
      compFaultWr   := isStore
      compFaultSize := u1.size.mux(
        m68k040.isa.Size.BYTE -> U(0, 2 bits),
        m68k040.isa.Size.WORD -> U(1, 2 bits),
        m68k040.isa.Size.LONG -> U(2, 2 bits))
      compFaultSup  := xlate.req.supervisor
    }

    // ---- drive completion / writeback / wakeup from the registered stage ----
    // A FAULTED access still completes (marks the ROB entry done) but writes NO
    // register / wakes nothing — its result is replaced by the precise exception.
    completionPort.valid   := compValid
    completionPort.payload := compRobId
    intW.valid     := compValid && compPdstValid && !compIsFault
    intW.address   := compPdst
    intW.data      := compData
    intByp.valid   := compValid && compPdstValid && !compIsFault
    intByp.address := compPdst
    intByp.data    := compData
    // NZVC writeback + bypass for a MOVE-to-memory store (mirrors the int path; the
    // bypass forwards to a dependent flag-reader issuing the same cycle, exactly as
    // the ALU EU's NZVC bypass).
    nzvcW.valid     := compValid && compNzvcWrite && !compIsFault
    nzvcW.address   := compNzvcDst
    nzvcW.data      := compNzvc
    nzvcByp.valid   := nzvcW.valid
    nzvcByp.address := nzvcW.address
    nzvcByp.data    := nzvcW.data
    // X writeback + bypass for the RTR CCR-restore load (X := loaded[4]).
    xW.valid     := compValid && compXWrite && !compIsFault
    xW.address   := compXDst
    xW.data      := B(compX)
    xByp.valid   := xW.valid
    xByp.address := xW.address
    xByp.data    := xW.data
    // Dynamic load-wakeup: a completing LOAD or a STACK-PUSH store (both produce an
    // int physreg) broadcasts; a plain store completes too but writes no register.
    wakeupPort.valid   := compValid && compWakes && compPdstValid && !compIsFault
    wakeupPort.payload := compPdst
    // MMU access-fault completion (registered, alongside the comp* stage).
    faultCompletionPort.valid           := compValid && compIsFault
    faultCompletionPort.payload.robId   := compRobId
    faultCompletionPort.payload.faultAddr  := compFaultAddr
    faultCompletionPort.payload.write      := compFaultWr
    faultCompletionPort.payload.sizeBits   := compFaultSize
    faultCompletionPort.payload.supervisor := compFaultSup

    val busy = RegInit(False); busy.simPublic(); s1Valid.simPublic()
    // Single-outstanding: do not accept a new µop while a decision is pending
    // (s1Valid), a load is in flight (busy), or a registered completion is occupying
    // the writeback stage this cycle (compValid). compValid is a 1-cycle pulse, so
    // this only stalls issue for that one extra cycle.
    issuePort.ready := !busy && !s1Valid && !compValid

    // S0 -> S1 advance (only when not busy in a wait state). The bypassed BASE operand
    // (`base0`) is latched here — NOT the computed effective address — so the deep
    // ALU->base cone ends at the `s1Base` flop. The effective address `s1Va`, the
    // cross-detection, and the next-line base (`s1AddrB`/`s1CrossLine`/`s1CrossPage`/
    // `s1TwoAccess`) are all derived COMBINATIONALLY off `s1Base`/`s1Va` above (shallow
    // stages off the flop), so neither the AGU adder nor the +16/cross compares are
    // chained onto the ALU result.
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1Base  := base0
      s1Data  := data0
    } otherwise {
      when(!busy) { s1Valid := False }
    }

    // compValid is a single-cycle pulse: default-clear, re-set only by a capture.
    // compNzvcWrite likewise (so a stale store's NZVC write does not linger).
    // compIsFault likewise (so a non-faulting access never lingers a stale fault).
    compValid := False
    compNzvcWrite := False
    compXWrite := False
    compIsFault := False

    val fsm = new StateMachine {
      val IDLE    = new State with EntryPoint
      val XLATE   = new State  // registered translated paddr -> SQ-fwd query / store alloc
      val RESOLVE = new State  // registered SQ-fwd result -> completion / cache launch
      val WAIT    = new State   // aligned: cache load cmd accepted, awaiting loadRsp
      val WAIT_A  = new State    // cross: slot A accepted, awaiting line A
      val WAIT_B  = new State    // cross: slot B accepted, awaiting line B -> merge

      IDLE.whenIsActive {
        busy := False
        when(s1Valid) {
          when(isLoad || isStore) {
            // Translate-at-execute: REGISTER the translated paddr (+perm fault) in
            // this stage so the SQ overlap-compare / store-alloc consume a REGISTERED
            // s2Paddr next cycle (the DTLB lookup is no longer in series with the
            // SQ-compare). Only advance once translation is RESOLVED (TLB hit /
            // identity); on a DTLB miss `xlateReady` is False while the walker runs ->
            // hold S1 (busy) and retry next cycle (the existing single-outstanding
            // stall). `busy` alone is not enough on the stall path (the S0->S1 advance
            // reads the pre-update busy and would clear s1Valid this cycle), so
            // re-assert s1Valid explicitly (later write wins).
            busy := True
            when(xlateReady) {
              when(xlateFault) {
                // MMU access fault: the translation RESOLVED with a fault (non-
                // resident / write-protect / supervisor). The access does NOT
                // proceed (no SQ alloc / no cache launch); it completes as a FAULT
                // (vector 2) so the ROB flags the entry for precise delivery.
                captureFault()
                busy    := False
                s1Valid := False
                goto(IDLE)
              } otherwise {
                s2Paddr  := s1Paddr
                s2PaddrB := s1PaddrB
                s2Fault  := xlateFault
                goto(XLATE)
              }
            } otherwise {
              s1Valid := True
            }
          } otherwise {
            captureCompletion(B(0, 32 bits))   // non-memory (defensive)
          }
        }
      }

      // Translated-paddr resolve: s2Paddr is now REGISTERED, so the SQ overlap-compare
      // / store-alloc fed from it start fresh this cycle (off the long DTLB-lookup arc).
      // s1 is still held (busy) so the SQ query / store-split inputs are stable.
      XLATE.whenIsActive {
        busy := True
        when(isStore) {
          // allocate into the SQ; "executes" immediately (no int dst). Store
          // completion drives a CONSTANT compData (off the SQ-compare arc), so it
          // captures here directly without the extra resolve cycle.
          sq.io.alloc.valid := True
          captureCompletion(B(0, 32 bits))
          busy    := False
          s1Valid := False
          goto(IDLE)
        } otherwise {
          // load: capture the SQ-forward compare result (off the registered s2Paddr)
          // and resolve next cycle.
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
          goto(RESOLVE)
        }
      }

      // Decision-resolve from the REGISTERED SQ-forward result (off the long
      // s1Paddr -> SQ-compare arc). s1 is still held (busy), so a re-query for the
      // stall-retry path reads stable SQ content.
      //
      // A CROSS load (s1TwoAccess) never takes the single-slot full-forward fast
      // path: the SQ holds a split store as two slots and a same-addr/size forward
      // can't span the boundary, so a cross load with ANY overlap STALLS until the
      // older store drains to memory, then reads the merged value from the cache.
      RESOLVE.whenIsActive {
        busy := True
        when(fwdHit && !s1TwoAccess) {
          // full-overlap forward: skip the cache (aligned only).
          captureCompletion(fwdData)
          busy    := False
          s1Valid := False
          goto(IDLE)
        } elsewhen(fwdStall || (fwdHit && s1TwoAccess)) {
          // overlap with an older store: re-sample the SQ and retry. For a cross
          // load any overlap (hit or partial) holds until the store drains.
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
        } otherwise {
          // no forward: drive the cache load; back-pressure on dcache.loadBusy.
          loadVaddr := s1Va                  // slot A (also the aligned access)
          dcache.loadCmd.valid := True
          when(dcache.loadCmd.fire) {
            when(s1TwoAccess) { aDone := False; goto(WAIT_A) } otherwise { goto(WAIT) }
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

      // CROSS slot A: capture line A (latched via aDone since loadRsp is a 1-cycle
      // pulse), then launch slot B at s1AddrB (the cache re-translates addrB's VPN:
      // same page for a line-cross, next page for a page-cross). Each slot can
      // independently hit / miss-refill the L1D.
      WAIT_A.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid && !aDone) {
          lineA := dcache.loadRsp.payload.line
          aDone := True
        }
        when(dcache.loadRsp.valid || aDone) {
          // slot A done -> drive slot B's cmd until the cache accepts it.
          loadVaddr := s1AddrB
          loadPaddr := s2PaddrB     // REGISTERED slot-B physical address
          dcache.loadCmd.valid := True
          when(dcache.loadCmd.fire) { goto(WAIT_B) }
        }
      }

      // CROSS slot B: capture line B, merge sizeBytes spanning the boundary, done.
      WAIT_B.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid) {
          val merged = m68k040.cache.DcacheByteLane.extractCross(
            lineA, dcache.loadRsp.payload.line, lineOff, u1.size)
          captureCompletion(merged)
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
    wbObs.nzvc      := compNzvc          // MOVE-to-mem store flags OR RTR CCR-restore NZVC
    wbObs.nzvcWrite := compNzvcWrite     // store NZVC or RTR CCR-restore
    wbObs.x         := compX             // RTR CCR-restore X (loaded[4])
    wbObs.xWrite    := compXWrite
    // Reuse `divRem` as the generic "crack µop — DROP this commit record" marker: a
    // stack-push store is the leading crack µop of BSR/JSR (the trailing branch is the
    // macro instruction's single commit). Its A7 write is still folded into running A7.
    wbObs.divRem    := compStkPush || compCcrRestore || compRmwStore
    wbObs.simPublic()

    // ── Exception-unit cache arbitration MUX (LAST drivers — override the LS EU's
    // cache/TLB requests while the commit-side exception sequencer is ACTIVELY
    // accessing the cache). Placed at the end of `logic` (same scope) so the LS
    // EU's drives are the base and these override them. Gated on the exc's PER-PORT
    // request valids (NOT excActive) so the SQ drain / a quiescing LS access keeps
    // the port on cycles the exc isn't using it (the exception is serializing, so
    // any older LS store has already committed/drained by the time the exc stores).
    when(excActive && excLoadCmdValid) {
      dcache.loadCmd.valid         := True
      dcache.loadCmd.payload.vaddr := excLoadCmdVaddr
      // exception sequencer runs MMU-off (identity, slice-1): paddr == vaddr.
      dcache.loadCmd.payload.paddr := excLoadCmdVaddr
      dcache.loadCmd.payload.size  := excLoadCmdSize
    }
    when(excActive && excStoreValid) {
      dcache.store.valid          := True
      dcache.store.payload        := excStorePayload
    }
    when(excActive && excXlateValid) {
      xlate.req.valid      := True
      xlate.req.vpn        := excXlateVpn
      xlate.req.write      := excXlateWrite
      xlate.req.supervisor := excXlateSupervisor
    }
    excLoadCmdReady := dcache.loadCmd.ready
  }
}
