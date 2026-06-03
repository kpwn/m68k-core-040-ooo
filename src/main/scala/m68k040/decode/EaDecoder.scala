package m68k040.decode

import m68k040.isa.Size
import spinal.core._
import spinal.lib._

/** Opcode-agnostic effective-address decoder. This slice resolves the
  * register-direct and immediate modes fully; every memory mode is classified
  * (MEMSIMPLE/MEMCOMPLEX) but its fields are NOT produced here — memory cracking
  * is a later slice (see the decode-matrix design spec). */
object EaDecoder {
  def decode(eaField: Bits, size: Size.C, words: Vec[Bits]): EaSpec = {
    val e    = EaSpec()
    val mode = eaField(5 downto 3)
    val reg  = eaField(2 downto 0)

    // defaults
    e.klass := EaClass.ILLEGAL
    e.reg   := 0
    e.imm   := 0

    switch(mode) {
      is(0) { e.klass := EaClass.DATAREG; e.reg := reg.asUInt.resized }              // Dn
      is(1) { e.klass := EaClass.ADDRREG; e.reg := (U(8, 5 bits) + reg.asUInt).resized } // An
      is(2, 3, 4, 5, 6) { e.klass := EaClass.MEMSIMPLE }                              // (An)/(An)+/-(An)/(d16,An)/(d8,An,Xn)
      is(7) {
        switch(reg) {
          is(0, 1) { e.klass := EaClass.MEMSIMPLE }   // (xxx).W / (xxx).L
          is(2, 3) { e.klass := EaClass.MEMSIMPLE }   // (d16,PC) / (d8,PC,Xn)
          is(4) {                                      // #imm
            e.klass := EaClass.IMM
            when(size === Size.LONG) {
              e.imm := words(1) ## words(2)
            } otherwise {
              // byte uses the low 8 bits of the (sign-extended) word per m68k
              e.imm := words(1).asSInt.resize(32).asBits
            }
          }
          default { e.klass := EaClass.ILLEGAL }
        }
      }
    }
    e
  }
}
