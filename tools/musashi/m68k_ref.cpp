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
