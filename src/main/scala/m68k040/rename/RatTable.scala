package m68k040.rename

import spinal.core._
import spinal.lib._

/** Dual-RAM Register Alias Table with O(1) rollback.
  *
  * Two Mems: specRam (written speculatively), commitRam (written on commit).
  * A `location` register tracks, per arch register, whether the speculative
  * mapping is current (bit set) or the committed mapping should be used (bit
  * clear).  Rollback simply zeros `location` in one cycle — O(1).
  *
  * Multi-write-bypass: for each write port i, a later port j (j > i) with
  * the same address suppresses port i's write, so the highest-numbered port
  * wins on same-cycle write-address conflicts.
  *
  * Reads are fully combinational (readAsync) — kept off the FMax critical
  * path and are FPGA-friendly (no high-fanout enables on the read side).
  */
case class RatTable(
    physIdWidth: Int,
    archDepth:   Int,
    writePorts:  Int,
    commitPorts: Int,
    readPorts:   Int
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
  }

  // ── Speculative RAM ─────────────────────────────────────────────────────────
  val specRam = Mem(UInt(physIdWidth bits), archDepth)
  for (i <- 0 until writePorts) {
    val hit: Bool =
      if (i + 1 < writePorts)
        (i + 1 until writePorts)
          .map(j => io.writes(j).valid && io.writes(j).addr === io.writes(i).addr)
          .reduce(_ || _)
      else
        False
    specRam.write(
      address = io.writes(i).addr,
      data    = io.writes(i).data,
      enable  = io.writes(i).valid && !hit
    )
  }

  // ── Committed RAM ────────────────────────────────────────────────────────────
  val commitRam = Mem(UInt(physIdWidth bits), archDepth)
  for (i <- 0 until commitPorts) {
    val hit: Bool =
      if (i + 1 < commitPorts)
        (i + 1 until commitPorts)
          .map(j => io.commits(j).valid && io.commits(j).addr === io.commits(i).addr)
          .reduce(_ || _)
      else
        False
    commitRam.write(
      address = io.commits(i).addr,
      data    = io.commits(i).data,
      enable  = io.commits(i).valid && !hit
    )
  }

  // ── Location register (1 bit per arch register) ───────────────────────────
  // Bit a = 1  →  specRam holds current mapping for arch reg a
  // Bit a = 0  →  commitRam holds current mapping for arch reg a
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
    val written  = specRam.readAsync(io.reads(r).addr)
    val committed = commitRam.readAsync(io.reads(r).addr)
    io.reads(r).data := Mux(location(io.reads(r).addr), written, committed)
  }
}
