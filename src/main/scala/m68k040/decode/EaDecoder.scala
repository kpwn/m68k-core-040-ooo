package m68k040.decode

import m68k040.isa.Size
import spinal.core._
import spinal.lib._

/** Opcode-agnostic effective-address decoder. Resolves register-direct + immediate
  * modes, and (memory-cracking slice) the IN-SCOPE memSimple modes' base/disp:
  * `(An)`, `(d16,An)`, `(xxx).W`, `(xxx).L`, `(d16,PC)`. The deferred side-effect /
  * indexed modes — `(An)+`, `-(An)`, `(d8,An,Xn)`, `(d8,PC,Xn)` — are classified
  * MEMCOMPLEX so the assembler keeps them `unimplemented`. The first extension word
  * is `words(1)` (PC/abs disp). PC-rel `disp` is the raw d16; the assembler folds
  * the PC (it owns `pkt.pc`). */
object EaDecoder {
  def decode(eaField: Bits, size: Size.C, words: Vec[Bits]): EaSpec = {
    val e    = EaSpec()
    val mode = eaField(5 downto 3)
    val reg  = eaField(2 downto 0)

    // defaults
    e.klass := EaClass.ILLEGAL
    e.reg   := 0
    e.imm   := 0
    e.baseValid := False
    e.base      := (U(8, 5 bits) + reg.asUInt).resized   // An base (used by mem modes)
    e.disp      := 0
    e.pcRel     := False

    switch(mode) {
      is(0) { e.klass := EaClass.DATAREG; e.reg := reg.asUInt.resized }                  // Dn
      is(1) { e.klass := EaClass.ADDRREG; e.reg := (U(8, 5 bits) + reg.asUInt).resized } // An
      is(2) {                                                                            // (An)
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True; e.disp := 0
      }
      is(3, 4) { e.klass := EaClass.MEMCOMPLEX }   // (An)+ / -(An): side-effects deferred
      is(5) {                                      // (d16,An)
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True
        e.disp  := words(1).asSInt.resize(32).asBits
      }
      is(6) { e.klass := EaClass.MEMCOMPLEX }      // (d8,An,Xn): indexed deferred
      is(7) {
        switch(reg) {
          is(0) {                                  // (xxx).W
            e.klass := EaClass.MEMSIMPLE; e.baseValid := False
            e.disp  := words(1).asSInt.resize(32).asBits
          }
          is(1) {                                  // (xxx).L
            e.klass := EaClass.MEMSIMPLE; e.baseValid := False
            e.disp  := words(1) ## words(2)
          }
          is(2) {                                  // (d16,PC) — assembler folds pc
            e.klass := EaClass.MEMSIMPLE; e.baseValid := False; e.pcRel := True
            e.disp  := words(1).asSInt.resize(32).asBits
          }
          is(3) { e.klass := EaClass.MEMCOMPLEX }  // (d8,PC,Xn): indexed deferred
          is(4) {                                  // #imm
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
