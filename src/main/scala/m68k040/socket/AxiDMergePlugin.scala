package m68k040.socket

import m68k040.cache.DcachePlugin
import m68k040.mmu.{DtlbPlugin, ItlbPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.plugin.FiberPlugin

/** Hosts `AxiDMerge` and connects it to the three D-side plugins' now-directionless AXI
  * bundles, presenting ONE merged `master(Axi4)` that becomes core-level IO.
  *
  * ==Ordering requirement, load-bearing==
  * In any plugin list, this plugin must appear AFTER `DcachePlugin`/`ItlbPlugin`/
  * `DtlbPlugin` (it reads their `logic` Areas, which are null until they have built) and
  * BEFORE `ResetVectorPlugin` (which drives the `rv*` wires below) and BEFORE
  * `BackendWiringPlugin` (which folds `wedge` into the halt drive). This mirrors the
  * `MmuControlPlugin`/`FpuControlPlugin`-before-`RobPlugin` constraint that
  * `FullCoreSynth.scala:496-499` already documents for the same reason.
  *
  * ==Why the three plugins need a flag at all==
  * `master(Axi4(...))` declared inside a plugin makes `ar.ready`/`r.valid`/`b.valid`
  * INPUTS of the enclosing `M68kCore`, and an input cannot be driven from inside it. This
  * repository states the constraint in its own words at `FetchAlignPlugin.scala:64-66`.
  * `socketMerged = true` therefore declares the identical bundle DIRECTIONLESS; it changes
  * no logic. Default false keeps `M68kFullCoreSynth`'s port surface byte-identical, which
  * `tools/socket/check_socket_netlist.py` enforces. */
class AxiDMergePlugin(val grantTimeout: BigInt = AxiDMerge.V1_TIMEOUT_CYCLES)
    extends FiberPlugin {

  val logic = during build new Area {
    val dc = host[DcachePlugin]
    val it = host[ItlbPlugin]
    val dt = host[DtlbPlugin]
    require(dc.socketMerged && it.socketMerged && dt.socketMerged,
      "AxiDMergePlugin requires DcachePlugin/ItlbPlugin/DtlbPlugin to be constructed with " +
      "socketMerged = true, otherwise their AXI bundles are top-level master ports the " +
      "arbiter cannot drive the response side of")

    val merge = new AxiDMerge(dc.axiCfg, grantTimeout)

    merge.io.dc   <> dc.logic.axi
    // it.walkerAxi/dt.walkerAxi are plugin-level `var`s assigned inside their own
    // `during build` Area (not declared as members of that Area's structural type -- see
    // ItlbPlugin.scala:64/DtlbPlugin.scala:68), so they are read off the plugin host
    // directly rather than through `.logic`, matching this codebase's existing precedent
    // for the same class of field (FullCoreSynth.scala:322's `dtlb.umAccessRobId`). This
    // relies on the load-bearing ordering requirement above: by the time THIS plugin's
    // `during build` runs, ItlbPlugin/DtlbPlugin have already built and assigned it.
    merge.io.itlb <> it.walkerAxi
    merge.io.dtlb <> dt.walkerAxi

    /** The single merged D-side master. This is the bundle `M68kSocketTop` permutes and
      * presents as `axi_d`. */
    val axi = master(Axi4(dc.axiCfg)).setName("axiDMerged")
    axi <> merge.io.out

    // Reset-vector read owner (D13). Idle-defaulted with CONCRETE values and
    // `allowOverride` -- NOT `assignDontCare`, which would hide `ResetVectorPlugin`'s drive
    // from the consumer (a documented SpinalHDL trap this project has hit before). With
    // no ResetVectorPlugin in the list these stay idle and the fourth owner never asks.
    val rvArValid = Bool();  rvArValid.allowOverride; rvArValid := False
    val rvArAddr  = UInt(dc.axiCfg.addressWidth bits)
    rvArAddr.allowOverride;  rvArAddr := U(0, dc.axiCfg.addressWidth bits)
    val rvRReady  = Bool();  rvRReady.allowOverride;  rvRReady := False
    merge.io.rvArValid := rvArValid
    merge.io.rvArAddr  := rvArAddr
    merge.io.rvRReady  := rvRReady
    val rvArReady = merge.io.rvArReady
    val rvRValid  = merge.io.rvRValid
    val rvRData   = merge.io.rvRData
    val rvRResp   = merge.io.rvRResp

    /** D20: raised when either direction held a grant for its whole bounded window with no
      * progress. `BackendWiringPlugin` folds this into the D28 halt drive; this plugin
      * deliberately does NOT reach into `RobPlugin` itself, so the core keeps exactly one
      * driver for `coreHaltedIn`. */
    val wedge = merge.io.wedge
    val wedgeIsRead = merge.io.wedgeIsRead
    wedge.simPublic(); wedgeIsRead.simPublic()
  }
}
