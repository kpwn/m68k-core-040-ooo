// m68k_ref.cpp — MusashiRef implementation
//
// See m68k_ref.h for the API.  Musashi exposes memory via free
// functions (m68k_read_memory_8 etc.); we install ourselves as the
// active singleton and route those back through cb_*().

#include "m68k_ref.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <algorithm>

extern "C" {
#include "m68k.h"
}

MusashiRef* MusashiRef::active_ = nullptr;

MusashiRef::MusashiRef() {
    if (active_) {
        std::fprintf(stderr,
            "[m68k_ref] FATAL: only one MusashiRef allowed — Musashi "
            "uses global state.\n");
        std::abort();
    }
    active_ = this;
    m68k_init();
    m68k_set_cpu_type(M68K_CPU_TYPE_68040);
    m68k_set_int_ack_callback([](int level) -> int {
        auto* r = MusashiRef::active();
        return r ? r->cb_int_ack(level) : M68K_INT_ACK_AUTOVECTOR;
    });
}

MusashiRef::~MusashiRef() {
    if (active_ == this) active_ = nullptr;
}

void MusashiRef::set_cpu_type(CpuType t) {
    unsigned int mt = M68K_CPU_TYPE_68040;
    switch (t) {
        case CPU_68000: mt = M68K_CPU_TYPE_68000; break;
        case CPU_68010: mt = M68K_CPU_TYPE_68010; break;
        case CPU_EC020: mt = M68K_CPU_TYPE_68EC020; break;
        case CPU_68020: mt = M68K_CPU_TYPE_68020; break;
        case CPU_68030: mt = M68K_CPU_TYPE_68030; break;
        case CPU_68040: mt = M68K_CPU_TYPE_68040; break;
    }
    m68k_set_cpu_type(mt);
}

void MusashiRef::set_bus(M68kBus* bus) {
    bus_ = bus;
}

// ── Memory helpers ──────────────────────────────────────────────────

uint8_t MusashiRef::read8(uint32_t addr) const {
    if (bus_) return (uint8_t)(bus_->read8(addr) & 0xFFu);
    auto it = mem_.find(addr);
    return (it == mem_.end()) ? 0xFF : it->second;
}
uint16_t MusashiRef::read16(uint32_t addr) const {
    return ((uint16_t)read8(addr) << 8) | read8(addr + 1);
}
uint32_t MusashiRef::read32(uint32_t addr) const {
    return ((uint32_t)read16(addr) << 16) | read16(addr + 2);
}

void MusashiRef::write8(uint32_t addr, uint8_t v) {
    if (bus_) {
        bus_->write8(addr, v);
        return;
    }
    mem_[addr] = v;
}
void MusashiRef::write16(uint32_t addr, uint16_t v) {
    write8(addr,     (v >> 8) & 0xFF);
    write8(addr + 1,  v       & 0xFF);
}
void MusashiRef::write32(uint32_t addr, uint32_t v) {
    write16(addr,     (v >> 16) & 0xFFFF);
    write16(addr + 2,  v        & 0xFFFF);
}

int64_t MusashiRef::load_binary(const std::string& path, uint32_t base_addr) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return -1;
    f.seekg(0, std::ios::end);
    size_t fsz = f.tellg();
    f.seekg(0, std::ios::beg);
    std::vector<uint8_t> buf(fsz);
    if (fsz) f.read(reinterpret_cast<char*>(buf.data()), fsz);
    for (size_t i = 0; i < fsz; i++) write8(base_addr + (uint32_t)i, buf[i]);
    return (int64_t)fsz;
}

void MusashiRef::add_readonly_range(uint32_t lo, uint32_t hi_exclusive) {
    ro_ranges_.emplace_back(lo, hi_exclusive);
}

// ── CPU control ─────────────────────────────────────────────────────

void MusashiRef::reset(uint32_t initial_pc, uint32_t initial_ssp) {
    // Seed the reset vector so m68k_pulse_reset() picks the right PC/SSP.
    write32(0, initial_ssp);
    write32(4, initial_pc);
    m68k_pulse_reset();
    // m68k_pulse_reset() does NOT zero the CCR bits of SR — it leaves them
    // at whatever Musashi's static init happened to give them (in practice
    // Z=1, because Musashi stores !Z in `not_z_flag` which starts at 0).
    // Our RTL resets SR to supervisor mode with IPL=7 (per 68040 reset
    // spec) and CCR clear, so match that here.  This keeps cold-start
    // Bcc's and the stop-PC parity checks aligned with the committed RTL
    // state.  Earlier SR mask was 0x2000 (IPL=0); changed to 0x2700
    // (IPL=7) when commit.v's reset SR was corrected — leaving IPL=0
    // at reset masked off all peripheral IRQs in sim but on real HW
    // unmasked them, leading to an exception storm during init when a
    // spurious peripheral IRQ fired before VBR was set.
    uint32_t sr = m68k_get_reg(nullptr, M68K_REG_SR);
    m68k_set_reg(M68K_REG_SR, (sr & ~0x071Fu) | 0x2700u);
}

void MusashiRef::reset_direct(uint32_t initial_pc, uint32_t initial_ssp) {
    m68k_pulse_reset();
    // Musashi accounts reset as deferred cycles on the first execute call.
    // Drain that bookkeeping before runners start treating step_one() as one
    // committed macro-instruction.
    (void)m68k_execute(1);
    uint32_t sr = m68k_get_reg(nullptr, M68K_REG_SR);
    m68k_set_reg(M68K_REG_SR, (sr & ~0x071Fu) | 0x2700u);
    m68k_set_reg(M68K_REG_SP, initial_ssp);
    m68k_set_reg(M68K_REG_PC, initial_pc);
}

void MusashiRef::set_irq(unsigned int level) {
    m68k_set_irq(level & 7u);
}

void MusashiRef::set_interrupt_ack_response(int vector) {
    int_ack_response_ = vector;
}

// ── 68040 software MMU ──────────────────────────────────────────────
// Mirrors our RTL TableWalker + the MAME 68040 access-fault model.

extern "C" {
extern unsigned int m68ki_aerr_address;
extern unsigned int m68ki_aerr_write_mode;
extern unsigned int m68ki_aerr_fc;
// Set by Musashi's opcode-prefetch path (m68ki_read_imm_*) so the oracle MMU knows a
// bus read is an INSTRUCTION fetch (program space) vs a data access. 0 = data.
volatile unsigned int g_mmu_instr_fetch = 0;
}

void MusashiRef::enable_mmu(uint32_t root_ptr, uint32_t data_lo, uint32_t data_hi) {
    mmu_enabled_ = true;
    mmu_root_    = root_ptr;
    mmu_lo_      = data_lo;
    mmu_hi_      = data_hi;
}

void MusashiRef::set_instr_window(uint32_t instr_lo, uint32_t instr_hi) {
    mmu_instr_lo_ = instr_lo;
    mmu_instr_hi_ = instr_hi;
}

uint32_t MusashiRef::mmu_translate(uint32_t va, bool rw) {
    // Dispatch on the current access class: an opcode prefetch is an INSTRUCTION
    // fetch (program space); everything else is data. (g_mmu_instr_fetch is set by
    // m68ki_read_imm_* around the prefetch read.)
    return mmu_translate_ex(va, rw, g_mmu_instr_fetch != 0);
}

uint32_t MusashiRef::mmu_translate_ex(uint32_t va, bool rw, bool instr) {
    // Window selection: data accesses use [mmu_lo_, mmu_hi_); instruction fetches use
    // [mmu_instr_lo_, mmu_instr_hi_). Outside the relevant window (or MMU off) ->
    // identity (code/vectors/stack/PT pass through, matching the RTL where only the
    // mapped pages translate).
    if (!mmu_enabled_) return va;
    if (instr) { if (va < mmu_instr_lo_ || va >= mmu_instr_hi_) return va; }
    else       { if (va < mmu_lo_       || va >= mmu_hi_)       return va; }

    // Page-table descriptors are read LITTLE-ENDIAN — matching the RTL TableWalker
    // (byte at the lowest address is bits[7:0]). A descriptor written by a normal
    // m68k `move.l` (big-endian) is therefore byte-swapped from the walker's view;
    // the handler writes the byte-swapped value in BOTH oracle and RTL, keeping the
    // program identical. read8 is untranslated (descriptors live at physical PT addrs).
    auto rd32le = [&](uint32_t a) -> uint32_t {
        return  (uint32_t)read8(a)
              | ((uint32_t)read8(a + 1) << 8)
              | ((uint32_t)read8(a + 2) << 16)
              | ((uint32_t)read8(a + 3) << 24);
    };

    const bool supervisor = (get_reg(REG_SR) & 0x2000u) != 0;

    auto fault = [&](void) -> uint32_t {
        // 68040 access-fault inputs for the format-$7 SSW + frame:
        //  - aerr_address  : the faulting logical (effective) address (the VA)
        //  - aerr_write_mode: SSW R/W bit, 1=read 0=write (MAME orig_rw)
        //  - aerr_fc       : 3-bit function code (supervisor=bit2; space: data=1,
        //                    program/instruction=2). An instruction fetch is a READ.
        const uint32_t space = instr ? 0x2u : 0x1u;
        m68ki_aerr_address    = va;
        m68ki_aerr_write_mode = instr ? 1u : (rw ? 0u : 1u);   // fetch=read; else write->0/read->1
        m68ki_aerr_fc         = (supervisor ? 0x4u : 0x0u) | space;
        // The bus error longjmps out of the in-flight prefetch, SKIPPING the
        // g_mmu_instr_fetch=0 reset in m68ki_read_imm_*. Clear it here so the frame
        // stacking + vector fetch run as DATA (identity), not instruction fetches.
        g_mmu_instr_fetch = 0;
        m68k_pulse_bus_error();   // stacks the format-$7 frame + longjmps out
        return va;                // not reached
    };

    // Root level: descriptor at root_ptr + rootIdx*4, rootIdx = VA[31:25].
    uint32_t rootIdx = (va >> 25) & 0x7f;
    uint32_t rootDesc = rd32le((mmu_root_ & 0xfffffffcu) + rootIdx * 4);
    if (!(rootDesc & 0x2u)) return fault();   // UDT high bit (bit1) == resident
    uint32_t ptrBase = rootDesc & 0xfffffff0u;

    // Pointer level: descriptor at ptrBase + ptrIdx*4, ptrIdx = VA[24:18].
    uint32_t ptrIdx = (va >> 18) & 0x7f;
    uint32_t ptrDesc = rd32le(ptrBase + ptrIdx * 4);
    if (!(ptrDesc & 0x2u)) return fault();
    uint32_t pageBase = ptrDesc & 0xfffffff0u;

    // Page (leaf) level: descriptor at pageBase + pageIdx*4, pageIdx = VA[17:12].
    uint32_t pageIdx = (va >> 12) & 0x3f;
    uint32_t pgDesc = rd32le(pageBase + pageIdx * 4);
    uint32_t pdt = pgDesc & 0x3u;
    bool resident = (pdt == 0x1u) || (pdt == 0x3u);
    bool indirect = (pdt == 0x2u);
    bool wp  = (pgDesc & 0x4u) != 0;     // bit2 write-protect
    bool sup = (pgDesc & 0x80u) != 0;    // bit7 supervisor-only
    if (!resident || indirect) return fault();   // non-resident / unsupported indirect
    if (rw && wp) return fault();                // write to write-protected page
    if (sup && !supervisor) return fault();      // user access to supervisor page

    uint32_t ppn = (pgDesc >> 12) & 0xfffffu;
    return (ppn << 12) | (va & 0xfffu);
}

uint32_t MusashiRef::get_reg(Reg r) const {
    m68k_register_t mr;
    switch (r) {
        case REG_D0:  mr = M68K_REG_D0;  break;
        case REG_D1:  mr = M68K_REG_D1;  break;
        case REG_D2:  mr = M68K_REG_D2;  break;
        case REG_D3:  mr = M68K_REG_D3;  break;
        case REG_D4:  mr = M68K_REG_D4;  break;
        case REG_D5:  mr = M68K_REG_D5;  break;
        case REG_D6:  mr = M68K_REG_D6;  break;
        case REG_D7:  mr = M68K_REG_D7;  break;
        case REG_A0:  mr = M68K_REG_A0;  break;
        case REG_A1:  mr = M68K_REG_A1;  break;
        case REG_A2:  mr = M68K_REG_A2;  break;
        case REG_A3:  mr = M68K_REG_A3;  break;
        case REG_A4:  mr = M68K_REG_A4;  break;
        case REG_A5:  mr = M68K_REG_A5;  break;
        case REG_A6:  mr = M68K_REG_A6;  break;
        case REG_A7:  mr = M68K_REG_A7;  break;
        case REG_PC:  mr = M68K_REG_PC;  break;
        case REG_SR:  mr = M68K_REG_SR;  break;
        case REG_SP:  mr = M68K_REG_SP;  break;
        case REG_USP: mr = M68K_REG_USP; break;
        case REG_ISP: mr = M68K_REG_ISP; break;
        case REG_MSP: mr = M68K_REG_MSP; break;
        case REG_SFC: mr = M68K_REG_SFC; break;
        case REG_DFC: mr = M68K_REG_DFC; break;
        case REG_VBR: mr = M68K_REG_VBR; break;
        case REG_CACR: mr = M68K_REG_CACR; break;
        case REG_CAAR: mr = M68K_REG_CAAR; break;
        default:      return 0;
    }
    return m68k_get_reg(nullptr, mr);
}

void MusashiRef::set_reg(Reg r, uint32_t v) {
    m68k_register_t mr;
    switch (r) {
        case REG_D0:  mr = M68K_REG_D0;  break;
        case REG_D1:  mr = M68K_REG_D1;  break;
        case REG_D2:  mr = M68K_REG_D2;  break;
        case REG_D3:  mr = M68K_REG_D3;  break;
        case REG_D4:  mr = M68K_REG_D4;  break;
        case REG_D5:  mr = M68K_REG_D5;  break;
        case REG_D6:  mr = M68K_REG_D6;  break;
        case REG_D7:  mr = M68K_REG_D7;  break;
        case REG_A0:  mr = M68K_REG_A0;  break;
        case REG_A1:  mr = M68K_REG_A1;  break;
        case REG_A2:  mr = M68K_REG_A2;  break;
        case REG_A3:  mr = M68K_REG_A3;  break;
        case REG_A4:  mr = M68K_REG_A4;  break;
        case REG_A5:  mr = M68K_REG_A5;  break;
        case REG_A6:  mr = M68K_REG_A6;  break;
        case REG_A7:  mr = M68K_REG_A7;  break;
        case REG_PC:  mr = M68K_REG_PC;  break;
        case REG_SR:  mr = M68K_REG_SR;  break;
        case REG_SP:  mr = M68K_REG_SP;  break;
        case REG_USP: mr = M68K_REG_USP; break;
        case REG_ISP: mr = M68K_REG_ISP; break;
        case REG_MSP: mr = M68K_REG_MSP; break;
        case REG_SFC: mr = M68K_REG_SFC; break;
        case REG_DFC: mr = M68K_REG_DFC; break;
        case REG_VBR: mr = M68K_REG_VBR; break;
        case REG_CACR: mr = M68K_REG_CACR; break;
        case REG_CAAR: mr = M68K_REG_CAAR; break;
        default: return;
    }
    m68k_set_reg(mr, v);
}

int MusashiRef::run_until_sentinel(uint32_t sentinel_addr, int max_cycles) {
    return run_until_sentinel_or_pc(sentinel_addr, false, 0, max_cycles);
}

int MusashiRef::run_until_sentinel_or_pc(uint32_t sentinel_addr,
                                         bool stop_pc_enabled,
                                         uint32_t stop_pc,
                                         int max_cycles) {
    return run_until_sentinel_or_pc_with_irq(
        sentinel_addr, stop_pc_enabled, stop_pc, false, 0, 0, max_cycles);
}

int MusashiRef::run_until_sentinel_or_pc_with_irq(uint32_t sentinel_addr,
                                                  bool stop_pc_enabled,
                                                  uint32_t stop_pc,
                                                  bool irq_pc_enabled,
                                                  uint32_t irq_pc,
                                                  unsigned int irq_level,
                                                  int max_cycles) {
    std::vector<std::pair<uint32_t, unsigned int>> events;
    if (irq_pc_enabled) events.push_back({irq_pc, irq_level});
    return run_until_sentinel_or_pc_with_irq_events(sentinel_addr, stop_pc_enabled, stop_pc, events, max_cycles);
}

int MusashiRef::run_until_sentinel_or_pc_with_irq_events(uint32_t sentinel_addr,
                                                         bool stop_pc_enabled,
                                                         uint32_t stop_pc,
                                                         const std::vector<std::pair<uint32_t, unsigned int>>& irq_events,
                                                         int max_cycles) {
    hit_sentinel_       = false;
    hit_stop_pc_        = false;
    sentinel_addr_      = sentinel_addr;
    last_sentinel_value_= 0;
    last_sentinel_size_ = 0;
    size_t irq_index     = 0;

    // Stop-PC mode is used by final-state parity tests that must preserve
    // CCR/SR; it halts before executing the branch parked at stop_pc.
    int cycles_left = max_cycles;
    int executed    = 0;
    const int chunk = 1;
    while (cycles_left > 0 && !hit_sentinel_ && !hit_stop_pc_) {
        if (stop_pc_enabled && get_reg(REG_PC) == stop_pc) {
            hit_stop_pc_ = true;
            break;
        }
        while (irq_index < irq_events.size() && get_reg(REG_PC) == irq_events[irq_index].first) {
            set_irq(irq_events[irq_index].second);
            irq_index++;
        }
        int step = (cycles_left > chunk) ? chunk : cycles_left;
        int n = m68k_execute(step);
        if (n <= 0) n = step;   // Musashi returns cycles consumed
        executed    += n;
        cycles_left -= n;
    }
    return executed;
}

int MusashiRef::step_one() {
    return m68k_execute(1);
}

void MusashiRef::begin_trace(uint32_t sentinel_addr) {
    hit_sentinel_        = false;
    hit_stop_pc_         = false;
    sentinel_addr_       = sentinel_addr;
    last_sentinel_value_ = 0;
    last_sentinel_size_  = 0;
}

std::vector<MusashiRef::MemByte> MusashiRef::final_mem_writes() const {
    std::vector<MemByte> out;
    out.reserve(writes_.size());
    for (const auto& kv : writes_) {
        // Skip writes that landed in a declared read-only / sentinel
        // range — they don't count as user-visible state divergence.
        bool skip = false;
        for (const auto& r : ro_ranges_) {
            if (kv.first >= r.first && kv.first < r.second) { skip = true; break; }
        }
        if (!skip) out.push_back({kv.first, kv.second});
    }
    std::sort(out.begin(), out.end(),
              [](const MemByte& a, const MemByte& b){ return a.addr < b.addr; });
    return out;
}

std::vector<MusashiRef::MemByte> MusashiRef::final_mem_write_events() const {
    std::vector<MemByte> out;
    out.reserve(write_events_.size());
    for (const auto& event : write_events_) {
        bool skip = false;
        for (const auto& r : ro_ranges_) {
            if (event.addr >= r.first && event.addr < r.second) { skip = true; break; }
        }
        if (!skip) out.push_back(event);
    }
    return out;
}

std::vector<MusashiRef::MemByte> MusashiRef::final_memory_snapshot() const {
    std::vector<MemByte> out;
    out.reserve(mem_.size());
    for (const auto& kv : mem_) {
        out.push_back({kv.first, kv.second});
    }
    std::sort(out.begin(), out.end(),
              [](const MemByte& a, const MemByte& b){ return a.addr < b.addr; });
    return out;
}

// ── Sentinel helper ─────────────────────────────────────────────────

static inline bool straddles(uint32_t a, int size, uint32_t tgt) {
    // True if [a, a+size) contains tgt (and we also flag a full-word
    // write at exactly tgt).
    return a == tgt || (a < tgt && (a + (uint32_t)size) > tgt);
}

// ── Musashi C-level callbacks ───────────────────────────────────────

uint32_t MusashiRef::cb_read8(uint32_t a) {
    a = mmu_translate(a, /*rw=*/false);
    if (bus_) return bus_->read8(a) & 0xFFu;
    auto it = mem_.find(a);
    return (it == mem_.end()) ? 0xFF : it->second;
}
uint32_t MusashiRef::cb_read16(uint32_t a) {
    if (bus_) return bus_->read16(a) & 0xFFFFu;
    return ((uint32_t)cb_read8(a) << 8) | cb_read8(a + 1);
}
uint32_t MusashiRef::cb_read32(uint32_t a) {
    if (bus_) return bus_->read32(a);
    return ((uint32_t)cb_read16(a) << 16) | cb_read16(a + 2);
}

void MusashiRef::cb_write8(uint32_t a, uint32_t v) {
    a = mmu_translate(a, /*rw=*/true);
    uint8_t b = (uint8_t)v;
    if (bus_) {
        bus_->write8(a, b);
    } else {
        mem_[a]    = b;
        writes_[a] = b;
        write_events_.push_back({a, b});
    }
    if (a == sentinel_addr_) {
        hit_sentinel_        = true;
        last_sentinel_value_ = b;
        last_sentinel_size_  = 1;
    }
}
void MusashiRef::cb_write16(uint32_t a, uint32_t v) {
    // Ordered high-byte then low-byte (big-endian, m68k native).
    cb_write8(a,     (v >> 8) & 0xFF);
    cb_write8(a + 1,  v       & 0xFF);
    if (a == sentinel_addr_) {
        hit_sentinel_        = true;
        last_sentinel_value_ = v & 0xFFFF;
        last_sentinel_size_  = 2;
    }
}
void MusashiRef::cb_write32(uint32_t a, uint32_t v) {
    cb_write16(a,     (v >> 16) & 0xFFFF);
    cb_write16(a + 2,  v        & 0xFFFF);
    if (a == sentinel_addr_) {
        hit_sentinel_        = true;
        last_sentinel_value_ = v;
        last_sentinel_size_  = 4;
    }
}

int MusashiRef::cb_int_ack(int int_level) {
    m68k_set_irq(0);
    (void)int_level;
    return int_ack_response_;
}

// ── Musashi C callbacks (global) ────────────────────────────────────

extern "C" {

unsigned int m68k_read_memory_8(unsigned int address) {
    auto* r = MusashiRef::active();
    return r ? r->cb_read8(address) : 0xFF;
}
unsigned int m68k_read_memory_16(unsigned int address) {
    auto* r = MusashiRef::active();
    return r ? r->cb_read16(address) : 0xFFFF;
}
unsigned int m68k_read_memory_32(unsigned int address) {
    auto* r = MusashiRef::active();
    return r ? r->cb_read32(address) : 0xFFFFFFFFu;
}

// With M68K_SEPARATE_READS off, these route into the main reads.  We
// still define them in case a future build flips the flag.
unsigned int m68k_read_immediate_16(unsigned int address) {
    return m68k_read_memory_16(address);
}
unsigned int m68k_read_immediate_32(unsigned int address) {
    return m68k_read_memory_32(address);
}
unsigned int m68k_read_pcrelative_8(unsigned int address) {
    return m68k_read_memory_8(address);
}
unsigned int m68k_read_pcrelative_16(unsigned int address) {
    return m68k_read_memory_16(address);
}
unsigned int m68k_read_pcrelative_32(unsigned int address) {
    return m68k_read_memory_32(address);
}
unsigned int m68k_read_disassembler_8(unsigned int address) {
    return m68k_read_memory_8(address);
}
unsigned int m68k_read_disassembler_16(unsigned int address) {
    return m68k_read_memory_16(address);
}
unsigned int m68k_read_disassembler_32(unsigned int address) {
    return m68k_read_memory_32(address);
}

void m68k_write_memory_8(unsigned int address, unsigned int value) {
    auto* r = MusashiRef::active(); if (r) r->cb_write8(address, value);
}
void m68k_write_memory_16(unsigned int address, unsigned int value) {
    auto* r = MusashiRef::active(); if (r) r->cb_write16(address, value);
}
void m68k_write_memory_32(unsigned int address, unsigned int value) {
    auto* r = MusashiRef::active(); if (r) r->cb_write32(address, value);
}
void m68k_write_memory_32_pd(unsigned int address, unsigned int value) {
    // Default behavior same as 32 — callers enable M68K_SIMULATE_PD_WRITES
    // only if they want the split-word MOVE.L -(An) quirk; we don't.
    m68k_write_memory_32(address, value);
}

} // extern "C"
