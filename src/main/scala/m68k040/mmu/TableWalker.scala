package m68k040.mmu

import m68k040.cache.{CacheMode, DLoadCmd, DLoadRsp}
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** 68040 hardware 3-level table walker (4KB or 8KB pages per TCR.P, task #195;
  * long-format descriptors).
  *
  * On `start`, walks: root[VAroot] -> pointer[VAptr] -> page[VApage], issuing three
  * dependent single-beat AXI reads from the root pointer + VA index slices, then
  * decodes the descriptors (resident/type bits, PPN, write-protect, U/M, supervisor,
  * cache mode) and produces a `WalkRsp` on `done`. Permission faults (non-resident /
  * write-protect / supervisor) are FLAGGED (no exception delivery this slice).
  *
  * The walk sets the page descriptor's U bit (and M on a write access); those are
  * architectural memory writes, so they are NOT written here — they are returned in
  * `rsp.umWrite` for the owning plugin to QUEUE and drain at commit.
  *
  * MEMORY PORT: the three descriptor reads are ordinary `DcacheService` LOAD commands,
  * arbitrated onto the D-cache's single load port by `LsEuPlugin` alongside the LS pipe
  * and the exception sequencer. They are NOT a private AXI master any more.
  *
  * WHY (the bug this closes). A page-table entry the supervisor has just written with an
  * ordinary `move.l` lands DIRTY in the copyback L1D and is invisible in backing memory
  * until that line is evicted. A walker reading physical memory behind the cache's back
  * therefore reads the STALE descriptor and installs a wrong translation; and the U/M
  * writeback in the other direction lands in memory only, to be overwritten by the later
  * eviction of the same (stale-U/M) dirty line. Both directions are closed by routing
  * the traffic through the cache that owns the line. A real 68040 performs its table
  * searches through the data cache for exactly this reason.
  *
  * A descriptor is a 32-bit longword at a naturally aligned address (every level is
  * `base + idx*4` off an aligned table base), so the read is a plain `Size.LONG` load and
  * the D-cache's own `DcacheByteLane.extract` does the big-endian lane select. That is
  * why this component no longer carries its own `selectWord`: it was a hand copy of that
  * exact function's LONG case, and having two copies of a big-endian lane select already
  * produced one silent-corruption bug (task #194 — descriptors read byte-reversed
  * relative to what a real supervisor `move.l` wrote).
  *
  * `loadCmd.cacheMode` and `loadCmd.token` are DON'T-CARE here and are overwritten by the
  * arbiter: the descriptor fetch's own cache mode is a fixed architectural policy
  * (`CACR.DE ? WRITETHROUGH : INHIBITED`) which this component cannot see, and the token
  * is a reserved constant per walker (`DLoadToken.WALK_ITLB`/`WALK_DTLB`).
  *
  * A `loadRsp.fault` (the D-cache's physical bus-error response) TERMINATES the walk with
  * a fault rather than decoding whatever data came back — the fail-closed replacement for
  * the AXI-id guard the dedicated port used to carry.
  *
  * Bounded latency: three dependent reads. Single-outstanding (one walk at a time). */
class TableWalker extends Component {
  val io = new Bundle {
    val start = in Bool ()
    val req   = in(WalkReq())
    val busy  = out Bool ()
    val done  = out Bool ()      // 1-cycle pulse when the walk resolves
    val rsp   = out(WalkRsp())
    // D-cache client port pair (see the class comment). `loadRsp` is a Flow: the
    // D-cache's load response has no back-pressure and must be consumed the cycle it
    // is presented.
    val loadCmd = master(Stream(DLoadCmd()))
    val loadRsp = slave(Flow(DLoadRsp()))
    /** 2026-09-05 walker-stall ILA tap (p141). Pure observation -- no new state,
      * no consumer inside this Component, nothing feeds back into the datapath.
      *
      * This walker is a CHILD Component of `M68kCore` (constructed inside
      * `DtlbPlugin`/`ItlbPlugin`'s `logic` Area, which is itself an Area of
      * M68kCore, not a Component), so its INTERNALS are not legally readable
      * from M68kCore's scope -- only its PORTS are. `simPublic()` would not
      * help: it affects simulation visibility only, never synthesis-time
      * cross-component readability. Hence a real `out` port rather than a
      * reach-in, matching the structural pattern `M68kCore.dbg040` already
      * established for every other ILA tap in this design.
      *
      * Layout (see `M68kCore.dbg040.stallWalkPack` for where these bits land):
      *   [4:0] fsm state, ONE-HOT: IDLE, RD_ROOT, RD_PTR, RD_PAGE, FINISH
      *   [5]   cmdSent   -- a descriptor read is outstanding
      *   [6]   loadCmd.valid, [7] loadCmd.ready  -- the D-cache request
      *                       handshake; valid && !ready is the walker BLOCKED
      *                       on the port, which is the shape this capture is
      *                       looking for
      *   [8]   loadRsp.valid
      *   [9]   io.start
      *   [10]  donePulse */
    val dbgPack = out Bits (16 bits)
  }

  // ---- latched request ----
  val reqReg = Reg(WalkReq())

  // ---- descriptor read helpers ----
  // Present a LONG load command for `descAddr`; capture the extracted 32-bit
  // descriptor on the response. `cmdSent` sequences one read.
  val descAddr = Reg(UInt(32 bits))      // byte address of the current descriptor
  val cmdSent  = Reg(Bool()) init False

  // ---- accumulated walk state ----
  val accWriteProt = Reg(Bool())
  val pageDescAddr = Reg(UInt(32 bits))  // address of the leaf page descriptor (for U/M write)

  // ---- result registers ----
  val rPpn    = Reg(UInt(20 bits))
  val rWp     = Reg(Bool())
  val rSup    = Reg(Bool())
  val rCmode  = Reg(CacheMode())
  val rFault  = Reg(Bool())
  val rReason = Reg(MmuFaultReason())
  val rUmValid = Reg(Bool())
  val rUmAddr  = Reg(UInt(32 bits))
  val rUmByte  = Reg(Bits(8 bits))
  // Task #210: the leaf descriptor's M bit as it stands after this walk's own
  // U/M update (see WalkRsp.modified doc).
  val rModified = Reg(Bool())
  val donePulse = RegInit(False)

  // ---- D-cache client defaults ----
  // A table search is PHYSICAL by architecture, so the virtual and physical addresses
  // presented to the (VIPT) cache are the same value. That trivially satisfies
  // `DLoadCmd`'s stated `paddr[11:0] == vaddr[11:0]` construction requirement.
  io.loadCmd.valid           := False
  io.loadCmd.payload.vaddr   := descAddr
  io.loadCmd.payload.paddr   := descAddr
  io.loadCmd.payload.size    := m68k040.isa.Size.LONG
  // Overwritten by the arbiter (W1/W2): the descriptor fetch's own cache mode is a
  // fixed architectural policy this component cannot see. WRITETHROUGH is the inert
  // default so a standalone DUT with no arbiter still sees a well-defined value.
  io.loadCmd.payload.cacheMode := CacheMode.WRITETHROUGH
  // Likewise overwritten by the arbiter with this walker's RESERVED token
  // (`DLoadToken.WALK_ITLB` / `WALK_DTLB`). Never a don't-care: a don't-care could
  // alias a live early-VIPT probe entry on token AND vaddr and be answered with that
  // probe's data.
  io.loadCmd.payload.token   := U(m68k040.cache.DLoadToken.WALK_DTLB,
                                  m68k040.cache.DLoadToken.Width bits)

  donePulse := False

  val fsm = new StateMachine {
    val IDLE     = new State with EntryPoint
    val RD_ROOT  = new State
    val RD_PTR   = new State
    val RD_PAGE  = new State
    val FINISH   = new State

    // Shared read micro-sequence: present ONE `DLoadCmd` for `descAddr` and hold it
    // until it is accepted. `cmdSent` makes the command single-shot, so a level-held
    // `valid` cannot be double-accepted while the response is outstanding.
    def issueRead(): Unit = {
      when(!cmdSent) {
        io.loadCmd.valid := True
        when(io.loadCmd.ready) { cmdSent := True }
      }
    }
    // FAIL-CLOSED replacement for the retired AXI-id guard (D19). The D-cache reports a
    // physical bus error on the descriptor read as `loadRsp.fault`; the walk must NOT
    // decode whatever data rode along with it. Terminate the walk as a fault instead.
    // `NON_RESIDENT` is reused rather than adding a `BUS_ERROR` enumerant: it is the
    // one reason the whole MMU path already handles end-to-end, and the alternative
    // today is strictly worse (the old dedicated port never checked `rresp` at all and
    // would install a translation decoded from error data).
    def descFault(): Unit = {
      rFault  := True
      rReason := MmuFaultReason.NON_RESIDENT
      rUmValid := False
    }

    IDLE.whenIsActive {
      when(io.start) {
        reqReg := io.req
        accWriteProt := False
        rFault  := False
        rReason := MmuFaultReason.NONE
        rUmValid := False
        // root descriptor address = rootPtr + rootIdx*4
        val rootBase = io.req.rootPtr
        val rootOff  = (io.req.vpn(19 downto 13) ## U(0, 2 bits)).asUInt   // rootIdx(7)*4
        descAddr := rootBase + rootOff.resize(32)
        cmdSent  := False
        goto(RD_ROOT)
      }
    }

    // ---- ROOT level ----
    RD_ROOT.whenIsActive {
      issueRead()
      when(io.loadRsp.valid) {
        val d = io.loadRsp.payload.data
        when(io.loadRsp.payload.fault) {
          descFault()
          goto(FINISH)
        } elsewhen(!MmuDesc.tblResident(d)) {
          rFault := True; rReason := MmuFaultReason.NON_RESIDENT
          goto(FINISH)
        } otherwise {
          accWriteProt := accWriteProt | MmuDesc.tblWriteProt(d)
          // pointer descriptor address = nextBase + ptrIdx*4
          val base = MmuDesc.tblNextBase(d)
          val off  = (reqReg.vpn(12 downto 6) ## U(0, 2 bits)).asUInt      // ptrIdx(7)*4
          descAddr := base + off.resize(32)
          cmdSent := False
          goto(RD_PTR)
        }
      }
    }

    // ---- POINTER level ----
    RD_PTR.whenIsActive {
      issueRead()
      when(io.loadRsp.valid) {
        val d = io.loadRsp.payload.data
        when(io.loadRsp.payload.fault) {
          descFault()
          goto(FINISH)
        } elsewhen(!MmuDesc.tblResident(d)) {
          rFault := True; rReason := MmuFaultReason.NON_RESIDENT
          goto(FINISH)
        } otherwise {
          accWriteProt := accWriteProt | MmuDesc.tblWriteProt(d)
          val base = MmuDesc.tblNextBase(d)
          // Task #195: pointer->page-table offset = PGI*4, but PGI itself is 6 bits
          // (VA[17:12]) for 4K pages vs 5 bits (VA[17:13]) for 8K pages — VA[12]
          // (vpn(0)) moves from "top bit of the page index" to "top bit of the page
          // offset" when TCR.P=1. Both branches are computed to the SAME 8-bit width
          // (zero-extended) so the Mux/resize below is unaffected by page size.
          val off4k = (reqReg.vpn(5 downto 0) ## U(0, 2 bits)).asUInt         // pageIdx(6)*4
          val off8k = (U(0, 1 bits) ## reqReg.vpn(5 downto 1) ## U(0, 2 bits)).asUInt // pageIdx(5)*4
          val off   = Mux(reqReg.is8K, off8k, off4k)
          descAddr := base + off.resize(32)
          cmdSent := False
          goto(RD_PAGE)
        }
      }
    }

    // ---- PAGE (leaf) level ----
    RD_PAGE.whenIsActive {
      issueRead()
      when(io.loadRsp.valid && io.loadRsp.payload.fault) {
        descFault()
        goto(FINISH)
      }
      when(io.loadRsp.valid && !io.loadRsp.payload.fault) {
        val d = io.loadRsp.payload.data
        pageDescAddr := descAddr
        val wp  = accWriteProt | MmuDesc.pgWriteProt(d)
        val sup = MmuDesc.pgSupervisor(d)
        when(MmuDesc.pgInvalid(d) || MmuDesc.pgIndirect(d)) {
          // indirect not supported this slice -> treat as non-resident fault.
          rFault := True; rReason := MmuFaultReason.NON_RESIDENT
        } elsewhen(reqReg.isWrite && wp) {
          rFault := True; rReason := MmuFaultReason.WRITE_PROTECT
        } elsewhen(sup && !reqReg.isSuper) {
          rFault := True; rReason := MmuFaultReason.SUPERVISOR
        } otherwise {
          rFault := False; rReason := MmuFaultReason.NONE
        }
        rPpn   := MmuDesc.pgPpn(d)
        rWp    := wp
        rSup   := sup
        rCmode := CacheMode.decode(MmuDesc.pgCacheMode(d))
        // Deferred U/M descriptor write: set U (and M on a write). Only when the
        // page is resident & not faulting on perms (a faulting access sets no bits).
        val noFault = MmuDesc.pgResident(d) &&
                      !(reqReg.isWrite && wp) && !(sup && !reqReg.isSuper)
        val curByte = d(7 downto 0)
        val setM    = reqReg.isWrite
        val newByte = curByte | B"00001000" | (setM ? B"00010000" | B"00000000")
        // only queue if a bit actually changes
        val changes = (newByte =/= curByte)
        rUmValid := noFault && changes
        // Task #210: newByte(4) is the M bit as it stands after this walk's own
        // update (curByte's M, OR'd with setM on a write) -- exactly what the
        // owning TLB should cache for this entry regardless of whether `changes`
        // is true (a resident hit that's already M=1 must not re-walk either).
        rModified := newByte(4)
        // Task #194 (big-endian fix): `d(7 downto 0)` is the descriptor's numeric LOW
        // byte (holds PDT/W/U/M per the 68k page-descriptor format) — but real 68k
        // memory is big-endian, so that numeric LSB physically lives at the HIGHEST
        // byte of the 4-byte descriptor (descAddr+3), not descAddr+0. Previously (the
        // little-endian `d` composition) descAddr+0 WAS that byte, so this was
        // correct only relative to the old (wrong) read convention.
        rUmAddr  := descAddr + 3
        rUmByte  := newByte
        goto(FINISH)
      }
    }

    FINISH.whenIsActive {
      donePulse := True
      goto(IDLE)
    }
  }

  io.busy := !fsm.isActive(fsm.IDLE)
  io.done := donePulse

  // 2026-09-05 walker-stall tap (p141) -- see `io.dbgPack`'s declaration comment.
  // `stateReg` is the StateMachine's own registered state; `IDLE` is the EntryPoint
  // so state 0 reads as "no walk in progress" and any other value with
  // `loadCmd.valid && !loadCmd.ready` is a walker BLOCKED on the D-cache port.
  // The state is emitted ONE-HOT via `isActive`, not as `fsm.stateReg`. Two
  // reasons, the first fatal: `StateMachine.stateReg` does not exist yet at this
  // point in elaboration (it is created when the FSM builds, so reading it here
  // is a null dereference -- measured), whereas `isActive` registers a deferred
  // post-build task and is therefore legal at class scope. This is exactly why
  // `ExceptionUnit.quiesceHoldOut` is written with `isActive` too. Second, a
  // one-hot needs no enum-encoding table to interpret from a raw CSR read.
  io.dbgPack := B(0, 16 bits)
  io.dbgPack(0) := fsm.isActive(fsm.IDLE)
  io.dbgPack(1) := fsm.isActive(fsm.RD_ROOT)
  io.dbgPack(2) := fsm.isActive(fsm.RD_PTR)
  io.dbgPack(3) := fsm.isActive(fsm.RD_PAGE)
  io.dbgPack(4) := fsm.isActive(fsm.FINISH)
  io.dbgPack(5) := cmdSent
  io.dbgPack(6) := io.loadCmd.valid
  io.dbgPack(7) := io.loadCmd.ready
  io.dbgPack(8) := io.loadRsp.valid
  io.dbgPack(9) := io.start
  io.dbgPack(10) := donePulse

  io.rsp.ppn         := rPpn
  io.rsp.writeProt   := rWp
  io.rsp.supervisor  := rSup
  io.rsp.cacheMode   := rCmode
  io.rsp.fault       := rFault
  io.rsp.faultReason := rReason
  io.rsp.umWrite.valid   := rUmValid
  io.rsp.umWrite.addr    := rUmAddr
  io.rsp.umWrite.newByte := rUmByte
  io.rsp.modified        := rModified
}
