// tools/musashi/m68k_ref_fp.cpp -- MusashiRef FP register accessors
//
// WHY THIS IS A SEPARATE TRANSLATION UNIT (do not "simplify" it back into
// m68k_ref.cpp): Musashi's PUBLIC API (m68k.h) exposes no FP registers at all.
// The only way to observe FP state is Musashi's PRIVATE core header, m68kcpu.h,
// which declares `extern m68ki_cpu_core m68ki_cpu;` (m68kcpu.h:1020) whose
// fpr[8]/fpiar/fpsr/fpcr fields (m68kcpu.h:953-956) hold exactly what we need.
//
// But m68kcpu.h ALSO defines object-like macros REG_PC / REG_SP / REG_USP /
// REG_ISP / REG_MSP / REG_SFC / REG_DFC / REG_VBR / REG_CACR / REG_CAAR
// (m68kcpu.h:331-352), each colliding BY NAME with a MusashiRef::Reg
// enumerator, and function-like macros m68k_read_immediate_16/32 +
// m68k_read_pcrelative_8/16/32 (m68kcpu.h:465-470) which collide with the real
// callback functions m68k_ref.cpp defines.  Including it in m68k_ref.cpp is a
// HARD compile error (see this task's Step 1, which reproduces it).
//
// Here the REG_* collisions are removed with a targeted #undef block before
// m68k_ref.h is pulled in, and the m68k_read_* collisions simply never arise
// because this TU defines none of those callbacks.  m68kcpu.h is C++-safe on
// its own (self-guards with `#ifdef __cplusplus extern "C" {`, m68kcpu.h:37-39),
// so no extern "C" wrapper is needed here.

#include "m68kcpu.h"

// Undo m68kcpu.h's REG_* convenience macros so m68k_ref.h's MusashiRef::Reg
// enumerators of the same names survive the preprocessor.  Keep this list in
// sync with m68kcpu.h:336-346 if Musashi is ever re-vendored.
#undef REG_PC
#undef REG_SP
#undef REG_USP
#undef REG_ISP
#undef REG_MSP
#undef REG_SFC
#undef REG_DFC
#undef REG_VBR
#undef REG_CACR
#undef REG_CAAR

#include "m68k_ref.h"

Fp80 MusashiRef::get_fp(int i) const {
    Fp80 r{0, 0};
    if (i < 0 || i > 7) return r;
    r.high = (uint16_t)(m68ki_cpu.fpr[i].high & 0xFFFFu);
    r.low  = (uint64_t)(m68ki_cpu.fpr[i].low);
    return r;
}

uint32_t MusashiRef::get_fpcr()  const { return (uint32_t)m68ki_cpu.fpcr;  }
uint32_t MusashiRef::get_fpsr()  const { return (uint32_t)m68ki_cpu.fpsr;  }
uint32_t MusashiRef::get_fpiar() const { return (uint32_t)m68ki_cpu.fpiar; }
