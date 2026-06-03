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
  var rdBase, rdData: RegFileReadPort = null
  var intW: RegFileWritePort = null
  var intByp: RegFileBypassPort = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def sqCommit: Flow[UInt]     = sqCommitPort
  override def sqFlush: Bool            = sqFlushSig

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    sqCommitPort   = Flow(UInt(6 bits))
    sqFlushSig     = Bool()
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

    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdBase.addr := u0.psrcA
    rdData.addr := u0.psrcB
    val base0 = rdBase.data.asUInt
    val disp0 = u0.imm.asSInt
    val va0   = (base0.asSInt + disp0).asUInt
    val data0 = rdData.data

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegInit(False)
    val s1Ctx   = Reg(IqContext())
    val s1Va    = Reg(UInt(32 bits))
    val s1Data  = Reg(Bits(32 bits))
    val u1 = s1Ctx.uop

    // Translation is driven by the D-cache from loadCmd.payload.vaddr (which we
    // set to s1Va unconditionally below), so we just consume xlate.rsp here for
    // the physical address used by the SQ query/alloc. (Single TranslationService:
    // the cache owns the request port; we read the response.)
    val s1Paddr = (xlate.rsp.ppn ## s1Va(11 downto 0)).asUInt

    // ---- completion / writeback defaults ----
    completionPort.valid   := False
    completionPort.payload := s1Ctx.robId
    intW.valid   := False
    intW.address := u1.pdst
    intW.data    := B(0, 32 bits)
    intByp.valid := False
    intByp.address := u1.pdst
    intByp.data    := B(0, 32 bits)

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

    // ---- wbObs (sim) ----
    val wbResult  = Bits(32 bits); wbResult := B(0, 32 bits)
    val wbFire    = Bool(); wbFire := False

    val busy = RegInit(False)
    issuePort.ready := !busy && !s1Valid   // single-outstanding

    // captured-load registers (for the wait state)
    val ldData = Reg(Bits(32 bits))

    val isLoad  = u1.memOp === MemOp.LOAD
    val isStore = u1.memOp === MemOp.STORE

    // S0 -> S1 advance (only when not busy in a wait state)
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1Va    := va0
      s1Data  := data0
    } otherwise {
      when(!busy) { s1Valid := False }
    }

    def doWriteback(result: Bits): Unit = {
      intW.valid     := u1.pdstValid
      intW.address   := u1.pdst
      intW.data      := result
      intByp.valid   := u1.pdstValid
      intByp.address := u1.pdst
      intByp.data    := result
      completionPort.valid   := True
      completionPort.payload := s1Ctx.robId
      wbResult := result
      wbFire   := True
    }

    val fsm = new StateMachine {
      val IDLE = new State with EntryPoint
      val WAIT = new State    // cache load cmd accepted, awaiting loadRsp

      IDLE.whenIsActive {
        busy := False
        when(s1Valid) {
          when(isStore) {
            // allocate into the SQ; "executes" immediately (no int dst).
            sq.io.alloc.valid := True
            doWriteback(B(0, 32 bits))
          } elsewhen(isLoad) {
            when(sq.io.fwd.rsp.hit) {
              // full-overlap forward: skip the cache.
              doWriteback(sq.io.fwd.rsp.data)
            } elsewhen(sq.io.fwd.rsp.stall) {
              // partial/ambiguous overlap: hold S1 and retry next cycle.
              busy := True
            } otherwise {
              // drive the cache load; back-pressure on dcache.loadBusy.
              dcache.loadCmd.valid := True
              when(dcache.loadCmd.fire) {
                busy := True
                goto(WAIT)
              } otherwise {
                busy := True   // cache occupied; retry next cycle
              }
            }
          } otherwise {
            doWriteback(B(0, 32 bits))   // non-memory (defensive)
          }
        }
      }

      // cmd accepted; the dcache delivers exactly one loadRsp (fixed for a hit,
      // late after a refill). Do NOT re-drive loadCmd here.
      WAIT.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid) {
          doWriteback(dcache.loadRsp.payload.data)
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }
    }

    // ---- wbObs (sim-only whitebox) ----
    val wbObs = WbObs()
    wbObs.valid     := RegNext(wbFire) init False
    wbObs.robId     := RegNext(s1Ctx.robId)
    wbObs.dstArch   := RegNext(u1.dstArch)
    wbObs.result    := RegNext(wbResult)
    wbObs.intWrite  := RegNext(u1.pdstValid)
    wbObs.nzvc      := RegNext(B(0, 4 bits))
    wbObs.nzvcWrite := RegNext(False)
    wbObs.x         := RegNext(False)
    wbObs.xWrite    := RegNext(False)
    wbObs.simPublic()
  }
}
