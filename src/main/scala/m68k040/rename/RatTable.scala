package m68k040.rename

import spinal.core._
import spinal.lib._

case class RatMapWrite(archDepth: Int, physIdWidth: Int) extends Bundle {
  val addr = UInt(log2Up(archDepth) bits)
  val data = UInt(physIdWidth bits)
}

/** Dual register-file Register Alias Table with O(1) rollback.
  *
  * Two register Vecs: specReg (written speculatively), commReg (written on
  * commit). A `location` register tracks, per arch register, whether the
  * speculative mapping is current (bit set) or the committed mapping should be
  * used (bit clear). Rollback simply zeros `location` in one cycle — O(1).
  *
  * Multi-write priority: each cell scans its write ports low->high, so the
  * highest-numbered port with a matching address wins on same-cycle write-address
  * conflicts. (Register Vecs are used INSTEAD of Mem because a multi-write
  * async-read Mem with several ports addressing the SAME cell — which the 1-entry
  * flag RATs always do — dropped all but the highest write port; a lone slot-0
  * flag write was silently lost, corrupting a later branch's NZVC source.)
  *
  * Reads are fully combinational — kept off the FMax critical path; the Vecs are
  * small (int=18×6b, flags=1×physIdWidth) so they map to LUTs/FFs cleanly.
  */
case class RatTable(
    physIdWidth: Int,
    archDepth:   Int,
    writePorts:  Int,
    commitPorts: Int,
    readPorts:   Int,
    prepared: Boolean = false
) extends Component {

  val io = new Bundle {
    val rollback = in Bool ()
    val writes = Vec.fill(writePorts)(
      slave(Flow(new Bundle {
        val addr = UInt(log2Up(archDepth) bits)
        val data = UInt(physIdWidth bits)
      }))
    )
    val commits = Vec.fill(commitPorts)(
      slave(Flow(new Bundle {
        val addr = UInt(log2Up(archDepth) bits)
        val data = UInt(physIdWidth bits)
      }))
    )
    val reads = Vec.fill(readPorts)(new Bundle {
      val addr = in UInt (log2Up(archDepth) bits)
      val data = out UInt (physIdWidth bits)
    })
    // Committed phys mapping of EVERY arch register (commit RAM only, ignoring the
    // speculative remap). Surfaced as an output so a parent (e.g. the exception unit's
    // live committed-A7 readback) can read an architectural mapping across the hierarchy.
    val committedPhys = out Vec (UInt(physIdWidth bits), archDepth)
    val prepareBegin = if (prepared) in(Bool()) else null
    val prepareAbort = if (prepared) in(Bool()) else null
    val publish = if (prepared) in(Bool()) else null
    val prepare = if (prepared) Vec.fill(2)(slave(Flow(RatMapWrite(archDepth, physIdWidth)))) else null
  }


  // ── Speculative / committed storage (REGISTER Vecs, NOT Mem) ────────────────
  // Previously these were 2-write-port + readAsync Mems. A multi-write async-read
  // Mem with BOTH write ports addressing the SAME cell (which the flag RATs always
  // do — archDepth=1, addr hardwired to 0) was not modelled correctly: write port 0
  // (slot 0) was silently DROPPED — only the highest-numbered write port ever
  // updated the cell. So a lone slot-0 flag writer (e.g. a `sub` that renames alone
  // in slot 0 the cycle after a cracked `move` consumed slot 1) never updated the
  // RAT; a later branch then read the STALE youngest-flag mapping and mis-resolved
  // its condition (the loop-with-load deadlock/divergence). Register Vecs with an
  // EXPLICIT priority loop (higher port wins on a same-cycle same-addr conflict)
  // model every write port faithfully and synthesise fine at these depths
  // (int=18×6b, flags=1×4b). This is the documented "register-file" fix.
  val specReg = Vec.fill(archDepth)(Reg(UInt(physIdWidth bits)))
  val commReg = Vec.fill(archDepth)(Reg(UInt(physIdWidth bits)))

  // Per-cell write update: scan write ports low->high so the HIGHEST-numbered port
  // with a matching address wins (matches the old multi-write-bypass intent).
  for (a <- 0 until archDepth) {
    for (i <- 0 until writePorts) {
      when(io.writes(i).valid && io.writes(i).addr === U(a, log2Up(archDepth) bits)) {
        specReg(a) := io.writes(i).data
      }
    }
    for (i <- 0 until commitPorts) {
      when(io.commits(i).valid && io.commits(i).addr === U(a, log2Up(archDepth) bits)) {
        commReg(a) := io.commits(i).data
      }
    }
  }

  // Preparation changes no architectural/speculative lookup and releases no
  // resource. Seed from the committed image, then fold two age-ordered records.
  val preparation = if (prepared) Some(new Area {
    val valid = RegInit(False)
    val shadow = Vec.fill(archDepth)(Reg(UInt(physIdWidth bits)))
    val publish = io.publish && valid && !io.prepareAbort && !io.rollback
    for (a <- 0 until archDepth) {
      val next = UInt(physIdWidth bits)
      next := Mux(io.prepareBegin, commReg(a), shadow(a))
      for (w <- io.prepare) when(w.valid && w.addr === a) { next := w.data }
      when(io.prepareBegin || io.prepare.map(_.valid).reduce(_ || _)) { shadow(a) := next }
      when(publish) { commReg(a) := shadow(a) }
    }
    when(io.prepareBegin) { valid := True }
    when(io.prepareAbort || io.rollback || io.publish) { valid := False }
    assert(!publish || !io.commits.map(_.valid).reduce(_ || _),
      "prepared RAT publication collided with an ordinary map write")
    assert(!(io.publish && !io.prepareAbort && !io.rollback) || valid,
      "RAT publication without a prepared image")
  }) else None

  // ── Location register (1 bit per arch register) ───────────────────────────
  // Bit a = 1  →  specReg holds current mapping for arch reg a
  // Bit a = 0  →  commReg holds current mapping for arch reg a
  val location = Reg(Bits(archDepth bits)) init 0

  // Set bits for any arch address written this cycle
  for (a <- 0 until archDepth) {
    when(io.writes.map(p => p.valid && p.addr === a).reduce(_ || _)) {
      location(a) := True
    }
  }

  // Rollback has priority — placed last so it overrides the per-bit sets above
  when(io.rollback) {
    location := 0
  }

  // ── Combinational reads ───────────────────────────────────────────────────
  for (r <- 0 until readPorts) {
    val written   = specReg(io.reads(r).addr)
    val committed = commReg(io.reads(r).addr)
    io.reads(r).data := Mux(location(io.reads(r).addr), written, committed)
  }

  // Surface the committed phys mappings (commit RAM only) as output ports.
  for (a <- 0 until archDepth) io.committedPhys(a) := commReg(a)

  /** COMMITTED phys mapping of a given arch register (the commit RAM only, ignoring
    * any speculative remap). Used to read the architectural (committed) value of a
    * register from the PRF — e.g. the exception unit's live committed A7 (arch 15). */
  def committedPhys(arch: Int): UInt = io.committedPhys(arch)
}
