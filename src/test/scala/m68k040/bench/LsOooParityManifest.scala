package m68k040.bench

/** Enumerate the current lock-step suite without executing it, so the LS-OoO
  * parity runner cannot silently omit newly registered cases. */
object LsOooParityManifest {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "LsOooParityManifest takes no arguments")
    new m68k040.lockstep.ExecuteLockStepSpec().testNames.toVector.sorted.foreach { name =>
      println(s"LS_OOO_LOCKSTEP_NAME\t$name")
    }
  }
}
