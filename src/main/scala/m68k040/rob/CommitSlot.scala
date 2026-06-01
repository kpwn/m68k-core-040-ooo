package m68k040.rob

import spinal.core._

/** One retired instruction's commit + free info: rename writes committed RAT, frees old pdsts. */
case class CommitSlot() extends Bundle {
  val intArch  = UInt(4 bits); val intNew  = UInt(6 bits); val intOld  = UInt(6 bits); val intWrite  = Bool()
  val nzvcNew  = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
  val xNew     = UInt(4 bits); val xOld    = UInt(4 bits); val xWrite    = Bool()
}
