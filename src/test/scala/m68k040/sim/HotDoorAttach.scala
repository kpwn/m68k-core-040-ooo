package m68k040.sim

import m68k040.cache.DcachePlugin
import spinal.core.ClockDomain

/** Attach the non-blocking L1D's P6 HOT DOOR (`DcachePlugin.axiDh`, read-only) to the SAME
  * memory image -- and the SAME model L2 -- as the cold `axi` model, when the DUT was built
  * with `hotDoor`. A no-op otherwise, so every harness can call it unconditionally.
  *
  * The hot door is multi-outstanding by construction (4 IDs), so the crossbar's
  * one-outstanding rule and the ID-busy block of the cold model are dropped for it. */
object HotDoorAttach {
  def model(dc: DcachePlugin, cd: ClockDomain, cold: AxiMemModel): AxiMemModel =
    if (!dc.hotDoor) null
    else AxiMemModel.attachReadOnly(dc.logic.axiDh, cd,
      cold.cfg.copy(crossbarSingleOutstanding = false, idBusyBlock = false),
      sharedMem = cold.mem, sharedL2From = cold)

  def apply(dc: DcachePlugin, cd: ClockDomain, agent: m68k040.ls.BehavioralMemAgent): m68k040.ls.BehavioralMemAgent = {
    model(dc, cd, agent.model); agent
  }
}
