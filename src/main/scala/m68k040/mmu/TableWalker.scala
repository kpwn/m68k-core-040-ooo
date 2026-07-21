package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4ReadOnly}
import spinal.lib.fsm._

/** 68040 hardware 3-level table walker (4 KB pages, long-format descriptors).
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
  * AXI: a dedicated 128-bit read-only port (same geometry as the D-cache / behavioral
  * memory). A descriptor is a 32-bit longword; the read returns the 16-byte line and
  * the walker selects the 4-byte lane by descAddr[3:2]. Descriptor bytes are read
  * BIG-ENDIAN (task #194 fix — byte at the LOWEST address is the descriptor's MSB),
  * mirroring `DcacheByteLane.extract`'s LONG case EXACTLY: this walker has its own
  * dedicated AXI port straight to physical memory, so it must reconstruct a 32-bit
  * value from raw AXI bytes the SAME way the D-cache's own load path does, or a page
  * table built by REAL supervisor `move.l` stores (68k memory is big-endian
  * architecturally) reads back completely byte-reversed. Was previously little-endian
  * (byte at the lowest address = bit[7:0]) — silently correct only for whitebox tests
  * that poke descriptor bytes directly in that same (wrong, relative to a real
  * architected store) order via `pokeWordLE`; the first ported test to build a page
  * table with real `move.l` instructions (the m68k-ooo MMU cluster) exposed it.
  *
  * Bounded latency: three dependent reads. Single-outstanding (one walk at a time). */
class TableWalker extends Component {
  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val io = new Bundle {
    val start = in Bool ()
    val req   = in(WalkReq())
    val busy  = out Bool ()
    val done  = out Bool ()      // 1-cycle pulse when the walk resolves
    val rsp   = out(WalkRsp())
    val axi   = master(Axi4ReadOnly(axiCfg))
  }

  // ---- latched request ----
  val reqReg = Reg(WalkReq())

  // ---- descriptor read helpers ----
  // Present an AR for the 16-byte line containing `descAddr`; capture the 32-bit
  // lane on R. `arSent`/`rGot` sequence one read.
  val descAddr = Reg(UInt(32 bits))      // byte address of the current descriptor
  val arSent   = Reg(Bool()) init False

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
  val donePulse = RegInit(False)

  // ---- AXI defaults ----
  io.axi.ar.valid := False
  io.axi.ar.payload.assignDontCare()
  io.axi.r.ready  := False

  donePulse := False

  // ---- 128-bit line -> selected 32-bit descriptor (BIG-ENDIAN lane, task #194) ----
  // descAddr[3:2] selects the 4-byte word within the 16-byte line. Reconstructs the
  // descriptor the SAME way DcacheByteLane.extract's LONG case does: the byte at the
  // LOWEST address is the descriptor's MSB (real 68k memory is big-endian) — NOT a
  // naive `subdivideIn(32 bits)` lane select, which would treat the raw AXI byte
  // order as little-endian and byte-reverse every descriptor a real `move.l` wrote.
  def selectWord(line: Bits, addr: UInt): Bits = {
    val bytes = line.subdivideIn(8 bits)   // bytes(i) = the line's byte i (line-base + i)
    val base  = (addr(3 downto 2) ## U(0, 2 bits)).asUInt   // descriptor's first byte offset within the line
    val b0 = bytes(base)
    val b1 = bytes(base + 1)
    val b2 = bytes(base + 2)
    val b3 = bytes(base + 3)
    b0 ## b1 ## b2 ## b3
  }

  val fsm = new StateMachine {
    val IDLE     = new State with EntryPoint
    val RD_ROOT  = new State
    val RD_PTR   = new State
    val RD_PAGE  = new State
    val FINISH   = new State

    // shared read micro-sequence: issue AR for descAddr's line, capture word.
    def issueRead(): Unit = {
      when(!arSent) {
        io.axi.ar.valid        := True
        io.axi.ar.payload.addr := (descAddr(31 downto 4) ## U(0, 4 bits)).asUInt
        io.axi.ar.payload.id   := U(2, 4 bits)
        io.axi.ar.payload.len  := U(0, 8 bits)
        io.axi.ar.payload.size := U(4, 3 bits)   // 16 bytes
        io.axi.ar.payload.burst := Axi4.burst.INCR
        when(io.axi.ar.ready) { arSent := True }
      }
      io.axi.r.ready := True
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
        arSent   := False
        goto(RD_ROOT)
      }
    }

    // ---- ROOT level ----
    RD_ROOT.whenIsActive {
      issueRead()
      when(io.axi.r.valid) {
        val d = selectWord(io.axi.r.payload.data, descAddr)
        when(!MmuDesc.tblResident(d)) {
          rFault := True; rReason := MmuFaultReason.NON_RESIDENT
          goto(FINISH)
        } otherwise {
          accWriteProt := accWriteProt | MmuDesc.tblWriteProt(d)
          // pointer descriptor address = nextBase + ptrIdx*4
          val base = MmuDesc.tblNextBase(d)
          val off  = (reqReg.vpn(12 downto 6) ## U(0, 2 bits)).asUInt      // ptrIdx(7)*4
          descAddr := base + off.resize(32)
          arSent := False
          goto(RD_PTR)
        }
      }
    }

    // ---- POINTER level ----
    RD_PTR.whenIsActive {
      issueRead()
      when(io.axi.r.valid) {
        val d = selectWord(io.axi.r.payload.data, descAddr)
        when(!MmuDesc.tblResident(d)) {
          rFault := True; rReason := MmuFaultReason.NON_RESIDENT
          goto(FINISH)
        } otherwise {
          accWriteProt := accWriteProt | MmuDesc.tblWriteProt(d)
          val base = MmuDesc.tblNextBase(d)
          val off  = (reqReg.vpn(5 downto 0) ## U(0, 2 bits)).asUInt       // pageIdx(6)*4
          descAddr := base + off.resize(32)
          arSent := False
          goto(RD_PAGE)
        }
      }
    }

    // ---- PAGE (leaf) level ----
    RD_PAGE.whenIsActive {
      issueRead()
      when(io.axi.r.valid) {
        val d = selectWord(io.axi.r.payload.data, descAddr)
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
        rCmode := MmuDesc.pgInhibited(d) ? CacheMode.INHIBITED | CacheMode.CACHEABLE
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

  io.rsp.ppn         := rPpn
  io.rsp.writeProt   := rWp
  io.rsp.supervisor  := rSup
  io.rsp.cacheMode   := rCmode
  io.rsp.fault       := rFault
  io.rsp.faultReason := rReason
  io.rsp.umWrite.valid   := rUmValid
  io.rsp.umWrite.addr    := rUmAddr
  io.rsp.umWrite.newByte := rUmByte
}
