// m68k_ref.h — thin C++ wrapper around the Musashi 68k ISS
//
// Musashi has global state (one CPU per process).  This wrapper gives
// it a cleaner C++ interface and a built-in flat memory backing, so
// the fuzzer / co-sim harness can drive it without touching Musashi's
// globals directly.
//
// Usage:
//   MusashiRef ref;
//   ref.set_cpu_type(MusashiRef::CPU_68040);
//   ref.load_binary("prog.bin", 0x40800000);
//   ref.reset(0x40800000, 0x00800000);   // PC, initial SSP
//   int n = ref.run_until_sentinel(0xFFFF0000, 200000);
//   uint32_t d0 = ref.get_reg(MusashiRef::REG_D0);
//
// Sentinel convention: the harness calls `run_until_sentinel(addr,
// max_cycles)`, which executes until a write to `addr` is observed,
// the cycle budget runs out, or an exception loop is detected.  The
// sentinel value captured on the matching write is returned via
// `last_sentinel_value()`.

#pragma once
#include <cstdint>
#include <string>
#include <utility>
#include <unordered_map>
#include <vector>

#include "m68k_bus.h"

class MusashiRef {
public:
    enum CpuType {
        CPU_68000 = 0,
        CPU_68010,
        CPU_EC020,
        CPU_68020,
        CPU_68030,
        CPU_68040,
    };

    // Register indices — deliberately match Musashi's enum values so
    // we can forward directly.
    enum Reg {
        REG_D0 = 0, REG_D1, REG_D2, REG_D3,
        REG_D4,     REG_D5, REG_D6, REG_D7,
        REG_A0,     REG_A1, REG_A2, REG_A3,
        REG_A4,     REG_A5, REG_A6, REG_A7,
        REG_PC,
        REG_SR,
        REG_SP,     // currently-active stack pointer
        REG_USP,
        REG_ISP,
        REG_MSP,
        REG_SFC,
        REG_DFC,
        REG_VBR,
        REG_CACR,
        REG_CAAR,
    };

    MusashiRef();
    ~MusashiRef();

    // ── Configuration ────────────────────────────────────────────────
    void set_cpu_type(CpuType t = CPU_68040);

    // Optional external bus.  When set, Musashi memory callbacks use this bus
    // instead of the built-in sparse memory.  The existing fuzz runner leaves
    // it null and keeps the old behavior.
    void set_bus(M68kBus* bus);

    // ── Memory ───────────────────────────────────────────────────────
    // Writes are tracked; final_mem_writes() returns all unique byte
    // addresses the CPU has written to, sorted ascending.
    uint8_t  read8(uint32_t addr) const;
    uint16_t read16(uint32_t addr) const;
    uint32_t read32(uint32_t addr) const;
    void     write8(uint32_t addr, uint8_t v);
    void     write16(uint32_t addr, uint16_t v);
    void     write32(uint32_t addr, uint32_t v);

    // Load a flat binary from disk at `base_addr`.  Returns bytes
    // loaded or -1 on failure.
    int64_t  load_binary(const std::string& path, uint32_t base_addr);

    // Record-only ranges don't log modifications (useful for ROM / the
    // magic sentinel region so we don't pollute mem-diff output).
    void     add_readonly_range(uint32_t lo, uint32_t hi_exclusive);

    // ── CPU control ──────────────────────────────────────────────────
    // Writes initial PC + SSP at the reset vector (addrs 0, 4), then
    // pulses reset so Musashi picks them up.
    void     reset(uint32_t initial_pc, uint32_t initial_ssp);
    // Pulse reset, then force PC/SP directly.  This is useful for harnesses
    // whose reset PC is supplied out-of-band rather than through vector 1.
    void     reset_direct(uint32_t initial_pc, uint32_t initial_ssp);
    void     set_irq(unsigned int level);
    void     set_interrupt_ack_response(int vector);

    uint32_t get_reg(Reg r) const;
    void     set_reg(Reg r, uint32_t v);

    // Execute until one of:
    //   - a 32-bit (or 16-bit or 8-bit) write lands at `sentinel_addr`
    //   - `max_cycles` Musashi cycles have elapsed
    //   - a double-fault or tight exception loop is detected
    // Returns cycles actually executed.
    int      run_until_sentinel(uint32_t sentinel_addr, int max_cycles);
    int      run_until_sentinel_or_pc(uint32_t sentinel_addr,
                                      bool stop_pc_enabled,
                                      uint32_t stop_pc,
                                      int max_cycles);
    int      run_until_sentinel_or_pc_with_irq(uint32_t sentinel_addr,
                                               bool stop_pc_enabled,
                                               uint32_t stop_pc,
                                               bool irq_pc_enabled,
                                               uint32_t irq_pc,
                                               unsigned int irq_level,
                                               int max_cycles);
    int      run_until_sentinel_or_pc_with_irq_events(uint32_t sentinel_addr,
                                                      bool stop_pc_enabled,
                                                      uint32_t stop_pc,
                                                      const std::vector<std::pair<uint32_t, unsigned int>>& irq_events,
                                                      int max_cycles);
    int      step_one();
    // Initialise sentinel tracking for use with step_one() loops.
    // Must be called before the first step_one() when not using
    // run_until_sentinel*; mirrors the init that run_until_sentinel_or_pc_with_irq_events does.
    void     begin_trace(uint32_t sentinel_addr);
    bool     hit_sentinel() const { return hit_sentinel_; }
    bool     hit_stop_pc() const { return hit_stop_pc_; }
    uint32_t last_sentinel_value() const { return last_sentinel_value_; }
    int      last_sentinel_size() const { return last_sentinel_size_; }

    // ── Mem-write log ────────────────────────────────────────────────
    // Unique (addr, byte-value) pairs touched by CPU writes.  Sorted
    // by address.
    struct MemByte { uint32_t addr; uint8_t val; };
    std::vector<MemByte> final_mem_writes() const;
    std::vector<MemByte> final_mem_write_events() const;
    std::vector<MemByte> final_memory_snapshot() const;

    // ── Musashi callback plumbing ────────────────────────────────────
    // Musashi is a C library with global functions — we install
    // ourselves as the "active" instance; only one MusashiRef can
    // exist at a time.  This is enforced in the ctor.
    static MusashiRef* active() { return active_; }

    uint32_t cb_read8(uint32_t a);
    uint32_t cb_read16(uint32_t a);
    uint32_t cb_read32(uint32_t a);
    void     cb_write8 (uint32_t a, uint32_t v);
    void     cb_write16(uint32_t a, uint32_t v);
    void     cb_write32(uint32_t a, uint32_t v);
    int      cb_int_ack(int int_level);

private:
    // Flat byte store — hash map to keep memory small for sparse
    // programs.  Address → byte value.  Missing addresses read 0xFF,
    // matching the Verilator MemModel default so co-sim results stay
    // in sync for "uninitialised read" cases.
    std::unordered_map<uint32_t, uint8_t>  mem_;
    std::unordered_map<uint32_t, uint8_t>  writes_;        // tracks CPU writes
    std::vector<MemByte>                   write_events_;  // ordered CPU write bytes
    std::vector<std::pair<uint32_t,uint32_t>> ro_ranges_;  // [lo, hi)
    M68kBus* bus_ = nullptr;
    int       int_ack_response_ = -1;

    bool      hit_sentinel_       = false;
    bool      hit_stop_pc_        = false;
    uint32_t  sentinel_addr_      = 0;
    uint32_t  last_sentinel_value_= 0;
    int       last_sentinel_size_ = 0;   // 1 / 2 / 4 bytes

    static MusashiRef* active_;
};
