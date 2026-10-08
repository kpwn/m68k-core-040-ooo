package m68k040.frontend

import m68k040.services.{BtbUpdateService, FetchPlanToken, FtbLookupCmd, FtbLookupRsp, FtbLookupService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** One direct-mapped fetch-window entry. Validity is held separately so a complete
  * invalidation remains a one-cycle register clear. */
case class FtbEntry(tagBits: Int, withCounter: Boolean = true) extends Bundle {
  val tag       = UInt(tagBits bits)
  val brWordOff = UInt(2 bits)
  val brLen     = UInt(4 bits)
  val target    = UInt(32 bits)
  val brType    = UInt(2 bits)
  // No reader anywhere (written by the update RMW only). CPU_BTB_NO_COUNTER omits it.
  val counter   = if (withCounter) UInt(2 bits) else null
}

/** Registered, window-indexed fetch target buffer.
  *
  * A command is accepted as a Flow event every cycle and produces exactly one result
  * on the following cycle. The token is echoed for a hard protocol assertion;
  * FetchAlign functionally associates the fixed-C+1 response with its locally delayed
  * issued slot, never with a changing live fetch PC. Only branches wholly contained in
  * their eight-byte window are installed; decode-time BTB/RAS prediction remains the
  * fallback.
  */
class FtbPlugin(entries: Int = 128,
                noCounter: Boolean = m68k040.top.ShippingCoreConfig.btbNoCounter) extends FiberPlugin with FtbLookupService {
  require(entries > 0 && (entries & (entries - 1)) == 0,
    "FTB entries must be a positive power of two")

  private var cmdPort: Flow[FtbLookupCmd] = null
  private var rspPort: Flow[FtbLookupRsp] = null
  private var clearPort: Flow[UInt] = null
  override def lookupCmd: Flow[FtbLookupCmd] = cmdPort
  override def lookupRsp: Flow[FtbLookupRsp] = rspPort
  override def clearOne: Flow[UInt] = clearPort

  val logic = during build new Area {
    val idxBits = log2Up(entries)
    val idxLo   = 3 // one entry per aligned eight-byte fetch window
    val idxHi   = idxLo + idxBits - 1
    val tagLo   = idxHi + 1
    val tagBits = 32 - tagLo

    def idxOf(pc: UInt): UInt = pc(idxHi downto idxLo)
    def tagOf(pc: UInt): UInt = pc(31 downto tagLo)

    val valids = Vec.fill(entries)(RegInit(False))
    val mem    = Mem(FtbEntry(tagBits, !noCounter), entries)

    val cmd = Flow(FtbLookupCmd())
    cmd.valid.allowOverride; cmd.valid := False
    cmd.payload.windowPc.allowOverride; cmd.payload.windowPc := U(0, 32 bits)
    cmd.payload.drop.allowOverride; cmd.payload.drop := U(0, 2 bits)
    cmd.payload.token.ringSlot.allowOverride; cmd.payload.token.ringSlot := U(0, 2 bits)
    cmd.payload.token.seq.allowOverride; cmd.payload.token.seq := U(0, 8 bits)
    cmdPort = cmd

    val clear = Flow(UInt(32 bits))
    clear.valid.allowOverride; clear.valid := False
    clear.payload.allowOverride; clear.payload := U(0, 32 bits)
    clearPort = clear

    val invalidateAll = Bool()
    invalidateAll.allowOverride; invalidateAll := False

    val qIdx   = idxOf(cmd.payload.windowPc)
    val qEntry = mem.readAsync(qIdx)
    val qHit   = valids(qIdx) && (qEntry.tag === tagOf(cmd.payload.windowPc))

    // Amendment §2.1.1: the complete framing verdict is registered HERE, at command
    // time, alongside the tag hit it already had to compute. `cmd.payload.drop` is the
    // same value the issuing cycle writes into `ringDrop(ringTail)`, and the fetch ring
    // has exactly one writer advancing one slot per fire, so the drop this command
    // carries IS the drop the C+1 application would have read back out of the ring.
    // Doing the compare here instead of there removes the measured
    // `ringDrop -> resultAfterDrop -> applyNow -> fetch-PC mux` head of the routed
    // critical path (six levels / 1.536 ns ahead of the ITLB CAM at `28ec738`).
    // The in-window sum is the identical `resultEnd <= 4` test FetchAlign used to run
    // one cycle later; `brLen` stays four bits end to end per §2.1.
    val qEnd     = qEntry.brWordOff.resize(5) + qEntry.brLen.resize(5)
    val qFramed  = qHit && (qEntry.brLen =/= 0) && (qEnd <= U(4, 5 bits)) &&
                   (qEntry.brWordOff >= cmd.payload.drop)

    val rspValid = RegNext(cmd.valid) init False
    // FMAX 200 MHz (t200-dec): captured UNCONDITIONALLY. `cmd.valid` is FetchAlign's
    // `planLookupFire = ic.cmd.fire && ...`, the end of the fetch-PC -> ITLB CAM ->
    // I-cache-ready cone, and as this register's CE it put ~60 flops on that cone. The
    // payload is a Flow's: every consumer qualifies it by `rsp.valid` (= RegNext of
    // cmd.valid), so its value on a non-response cycle is unobservable.
    val rspPayload = Reg(FtbLookupRsp())
    rspPayload.windowPc  := cmd.payload.windowPc
    rspPayload.token     := cmd.payload.token
    rspPayload.hit       := qHit
    rspPayload.framedOk  := qFramed
    rspPayload.brWordOff := qEntry.brWordOff
    rspPayload.brLen     := qEntry.brLen
    rspPayload.target    := qEntry.target
    rspPayload.brType    := qEntry.brType
    rspPayload.framedUncond := qFramed && (qEntry.brType =/= 0)
    val rsp = Flow(FtbLookupRsp())
    rsp.valid := rspValid
    rsp.payload := rspPayload
    rspPort = rsp
    rsp.valid.simPublic(); rsp.payload.flatten.foreach(_.simPublic())

    val upd = host[BtbUpdateService].btbUpdate
    val uIdx   = idxOf(upd.payload.pc)
    val uTag   = tagOf(upd.payload.pc)
    // The counter's only reader is its own read-modify-write; without it (CPU_BTB_NO_COUNTER)
    // the update needs no read of the table at all (one async read port fewer).
    val uOld   = if (!noCounter) mem.readAsync(uIdx) else null
    val uHit   = if (!noCounter) valids(uIdx) && (uOld.tag === uTag) &&
                 (uOld.brWordOff === upd.payload.pc(2 downto 1)) else null
    val uOff   = upd.payload.pc(2 downto 1)
    val uEnd   = uOff.resize(5) + upd.payload.len.resize(5)
    val installable = upd.payload.len =/= 0 &&
                      upd.payload.len <= U(5, 4 bits) &&
                      uEnd <= U(4, 5 bits)

    val cInc = if (!noCounter) Mux(uOld.counter === U(3, 2 bits), U(3, 2 bits), (uOld.counter + 1).resized) else null
    val cDec = if (!noCounter) Mux(uOld.counter === U(0, 2 bits), U(0, 2 bits), (uOld.counter - 1).resized) else null
    val ctrUpdated = if (!noCounter) Mux(upd.payload.taken, cInc, cDec) else null
    val ctrAlloc = if (!noCounter) Mux(upd.payload.brType === U(1, 2 bits), U(3, 2 bits),
                   Mux(upd.payload.taken, U(2, 2 bits), U(1, 2 bits))) else null
    val uNew = FtbEntry(tagBits, !noCounter)
    uNew.tag       := uTag
    uNew.brWordOff := uOff
    uNew.brLen     := upd.payload.len
    uNew.target    := upd.payload.target
    uNew.brType    := upd.payload.brType
    if (!noCounter) uNew.counter := Mux(uHit, ctrUpdated, ctrAlloc)

    val memWr = upd.valid && installable && !invalidateAll
    mem.write(uIdx, uNew, enable = memWr)
    when(memWr) {
      valids(uIdx) := True
    }

    // A mismatch clear names the exact branch PC. Do not erase a colliding window
    // unless both its window tag and learned word offset match that PC.
    val cIdx = idxOf(clear.payload)
    val cEntry = mem.readAsync(cIdx)
    when(clear.valid && valids(cIdx) && cEntry.tag === tagOf(clear.payload) &&
         cEntry.brWordOff === clear.payload(2 downto 1)) {
      valids(cIdx) := False
    }

    // Architectural/maintenance invalidation wins over update and clear.
    when(invalidateAll) {
      for (i <- 0 until entries) valids(i) := False
    }

    valids.foreach(_.simPublic())
  }
}
