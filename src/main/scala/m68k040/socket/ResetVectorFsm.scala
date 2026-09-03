package m68k040.socket

import m68k040.cache.DcacheByteLane
import m68k040.isa.Size
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** The reset/boot vector reader (design spec D12-D15, section 6.2).
  *
  * ==What it replaces==
  * `FetchAlignPlugin.scala:186-189` initialises `decodePc`/`fetchPc` to plain 0 and holds
  * `started = False`; `:585` gates `ic.cmd.valid` on `started`, which only a redirect sets.
  * So the core natively fetches NOTHING until something external redirects it, and today
  * that something is the top-level `redirect` port driven by a test harness. This FSM is
  * the real 68040 behaviour instead: one 16-byte read at physical 0, SSP from bytes 0-3, PC
  * from bytes 4-7. Both vectors live in the same 16-byte line, so ONE transaction suffices
  * -- the same observation `if_stage.v:7-19` makes for v1.
  *
  * ==Why it rides `axi_d` and is not a third socket master (D13)==
  * `axi_xbar.v:1178-1192`'s `apply_cpu_overlay` aliases low addresses into the ROM mirror
  * ONLY for reads whose master index is `XBAR_M_CPU` or `XBAR_M_CPUI`. A vector-0 read from
  * any other master would read raw, uninitialised low DRAM. So this is a fourth READ OWNER
  * on the D-side merge arbiter, which puts it on `XBAR_M_CPU` where the overlay applies --
  * and it also keeps the socket's two-master contract intact.
  *
  * (Note the overlay does NOT arm the xbar's ROM-read auto-disable: `cpu_rom_read_seen`
  * tests `is_rom_addr` against the RAW address, and a read at 0x0 is aliased into ROM
  * rather than being a ROM-mirror address. That disarm happens later, when ROM code reads
  * the mirror directly. It changes nothing here, but it is why D13's constraint is
  * "must come from a CPU master index", not "must be first".)
  *
  * ==Why the two longwords go through `DcacheByteLane.extract` (D12)==
  * Deliberately, so this reader shares the core's SINGLE definition of big-endian assembly
  * and the two can never drift.
  *
  * ==D14 ordering==
  * SSP write in cycle N, fetch redirect in cycle N+1. Same-cycle would almost certainly be
  * fine -- the redirect only restarts FETCH, many cycles before any uop could read A7 at
  * issue -- but "almost certainly fine" is not a property worth having in the boot path, and
  * the cost is one cycle once per power-on.
  *
  * ==D15: a non-OKAY response halts; it does not stack a vector-2 frame==
  * v1 presents `pd_fault` at pc=0 so commit raises bus-error vector 2 (`if_stage.v:16-18`).
  * This diverges deliberately, in order of weight: (1) a bus fault taken during RESET
  * exception processing is a double bus fault on a real 68040 and the part halts -- vector 2
  * is v1's divergence, not ours; (2) there is nothing to build a frame ON, since SSP is
  * exactly the value that just failed to arrive and the vector table itself is unreadable,
  * so a vector-2 entry would write a frame through a garbage stack pointer and immediately
  * fault again; (3) it matches this project's established policy for un-actionable bus
  * errors with no architectural recipient (`DcachePlugin.scala:1680-1683`).
  *
  * ==SWEEP_WAIT: the `io.regInitDone` gate (docs/BUG_calibration_word_misplaced_0d00.md
  * Part 100/101)==
  * `RegFilePlugin`'s int register file runs a 50-cycle post-reset zero-sweep
  * (`RegFilePlugin.scala`'s `initCounter`/`initDone`) that unconditionally blocks every
  * external write issued before `initDone` -- INCLUDING this FSM's own SSP write, which
  * silently vanishes if `APPLY0` fires while the sweep is still running. Part 100
  * sim-proved this end to end (real hardware: A7/MSP/ISP reads 0x00000000 after reset,
  * 20-for-20). Fixed here, not in `RegFilePlugin`, per Part 100/101's own recommendation
  * (option 1): a new `SWEEP_WAIT` state sits between the AXI response landing and
  * `APPLY0`, and simply never lets `APPLY0` (hence `io.sspWriteValid`) fire until
  * `io.regInitDone` is observed True. This means the write is never ISSUED early rather
  * than issued-and-dropped -- no new state needs to survive across the sweep, no replay
  * logic, and `RegFilePlugin`'s shared init-sweep code (which every OTHER write port on
  * every regfile instance also depends on) is untouched. D14's "SSP write in cycle N,
  * redirect in cycle N+1" ordering is preserved unchanged, just both cycles are now
  * pushed out to wherever `regInitDone` allows; it also closes Part 100's Mechanism B
  * window for free, since by the time `initDone` (cycle 50) can be true, the RAT's own
  * identity walk (cycle 19) is long finished. In the ordinary case where the real AXI
  * round trip takes longer than the sweep (Part 98's "unverified premise" that a fast
  * AXI response could plausibly land inside the window), `regInitDone` is already True
  * when `SWEEP_WAIT` is entered and the FSM falls straight through to `APPLY0` the same
  * cycle -- zero-cost when the race was never live. */
class ResetVectorFsm(dataWidth: Int = 128) extends Component {
  require(dataWidth >= 64, "the reset vector line must carry at least 8 bytes")

  val io = new Bundle {
    val arValid = out Bool ()
    val arAddr  = out UInt (32 bits)
    val arReady = in  Bool ()
    val rValid  = in  Bool ()
    val rData   = in  Bits (dataWidth bits)
    val rResp   = in  Bits (2 bits)
    val rReady  = out Bool ()
    // Gate input (Part 100/101 fix): true once RegFilePlugin's post-reset zero-sweep has
    // finished. Held low, this FSM parks in SWEEP_WAIT after the AXI response lands and
    // never issues the SSP write -- see the class header for why this is the chosen fix
    // shape (gate the FSM, not the regfile).
    val regInitDone   = in  Bool ()
    val sspWriteValid = out Bool ()
    val sspData       = out UInt (32 bits)
    val redirectValid = out Bool ()
    val redirectPc    = out UInt (32 bits)
    val haltPulse     = out Bool ()
  }

  val sspReg = Reg(UInt(32 bits)) init 0
  val pcReg  = Reg(UInt(32 bits)) init 0

  io.arValid := False
  io.arAddr  := U(0, 32 bits)          // physical 0 -- the 68040 reset vector, always
  io.rReady  := False
  io.sspWriteValid := False
  io.sspData       := sspReg
  io.redirectValid := False
  io.redirectPc    := pcReg
  io.haltPulse     := False

  val fsm = new StateMachine {
    val REQ        = new State with EntryPoint
    val WAIT       = new State
    val SWEEP_WAIT = new State
    val APPLY0     = new State
    val APPLY1     = new State
    val DONE       = new State

    REQ.whenIsActive {
      io.arValid := True
      when(io.arReady) { goto(WAIT) }
    }

    WAIT.whenIsActive {
      io.rReady := True
      when(io.rValid) {
        when(io.rResp === B"00") {
          // Bytes 0-3 and 4-7 of the line, assembled big-endian by the core's own single
          // definition (DcacheByteLane.extract), never by hand-slicing.
          sspReg := DcacheByteLane.extract(io.rData.resize(128 bits), U(0, 4 bits), Size.LONG).asUInt
          pcReg  := DcacheByteLane.extract(io.rData.resize(128 bits), U(4, 4 bits), Size.LONG).asUInt
          goto(SWEEP_WAIT)
        } otherwise {
          // D15. No frame, no redirect, no SSP write -- the core stops.
          io.haltPulse := True
          goto(DONE)
        }
      }
    }

    // Part 100/101 fix: park here (SSP write NOT yet issued) until RegFilePlugin's
    // zero-sweep is done. sspReg/pcReg are already latched, so nothing is lost while
    // parked -- APPLY0 only fires once the write is guaranteed to land.
    SWEEP_WAIT.whenIsActive {
      when(io.regInitDone) { goto(APPLY0) }
    }

    APPLY0.whenIsActive { io.sspWriteValid := True; goto(APPLY1) }   // cycle N
    APPLY1.whenIsActive { io.redirectValid := True; goto(DONE)   }   // cycle N+1
    DONE.whenIsActive   { /* terminal: never re-arms without a core reset */ }
  }
}
