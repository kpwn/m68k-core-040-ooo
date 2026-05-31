// m68k_bus.h -- Optional external bus interface for MusashiRef.
//
// The fuzz runner keeps using MusashiRef's built-in sparse memory.  ROM and
// platform runners can install an M68kBus so Musashi callbacks route through a
// harness-owned memory/peripheral model instead.

#pragma once

#include <cstdint>

class M68kBus {
public:
    virtual ~M68kBus() = default;

    virtual uint32_t read8(uint32_t addr) = 0;
    virtual uint32_t read16(uint32_t addr) {
        return ((read8(addr) & 0xffu) << 8) | (read8(addr + 1) & 0xffu);
    }
    virtual uint32_t read32(uint32_t addr) {
        return ((read16(addr) & 0xffffu) << 16) |
               (read16(addr + 2) & 0xffffu);
    }

    virtual void write8(uint32_t addr, uint32_t value) = 0;
    virtual void write16(uint32_t addr, uint32_t value) {
        write8(addr,     (value >> 8) & 0xffu);
        write8(addr + 1,  value       & 0xffu);
    }
    virtual void write32(uint32_t addr, uint32_t value) {
        write16(addr,     (value >> 16) & 0xffffu);
        write16(addr + 2,  value        & 0xffffu);
    }
};
