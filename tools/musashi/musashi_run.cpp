// musashi_run.cpp — standalone Musashi runner for the fuzz harness
//
// Mirrors the +dump_final_state protocol used by tb_top.cpp, so the
// fuzz.py driver can diff two files that look the same.
//
// Usage:
//   musashi_run --bin <path> --load-addr 0x40800000 \
//               --sentinel 0xFFFF0000 --max-cycles 200000 \
//               --out /tmp/musashi_state.txt
//
// Exit code: 0 on sentinel hit, 1 on cycle-budget exhaustion.

#include "m68k_ref.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <utility>
#include <vector>

static const char* USAGE =
  "musashi_run --bin <path> [--load-addr <hex>] [--initial-sp <hex>] "
  "[--sentinel <hex>] [--stop-pc <hex>] [--irq-level <0-7>] "
  "[--irq-at-pc <hex>] [--irq-event <pc>:<0-7>] [--ack-vector <2-255>|--ack-spurious] "
  "[--max-cycles <n>] --out <path>\n";

static uint32_t parse_u32(const char* s) {
    if (!s) return 0;
    int base = (std::strlen(s) > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X'))
                 ? 16 : 10;
    return (uint32_t)std::strtoul(s, nullptr, base);
}

int main(int argc, char** argv) {
    std::string bin_path;
    std::string out_path;
    uint32_t load_addr  = 0x40800000u;
    uint32_t initial_sp = 0x00800000u;
    uint32_t sentinel   = 0xFFFF0000u;
    uint32_t stop_pc    = 0;
    bool     use_stop_pc = false;
    unsigned int irq_level = 0;
    uint32_t irq_at_pc = 0;
    bool     use_irq_at_pc = false;
    std::vector<std::pair<uint32_t, unsigned int>> irq_events;
    int      ack_response = -1;
    int      max_cycles = 200000;

    for (int i = 1; i < argc; i++) {
        std::string a(argv[i]);
        if (a == "--bin"        && i + 1 < argc) bin_path   = argv[++i];
        else if (a == "--load-addr"  && i + 1 < argc) load_addr  = parse_u32(argv[++i]);
        else if (a == "--initial-sp" && i + 1 < argc) initial_sp = parse_u32(argv[++i]);
        else if (a == "--sentinel"   && i + 1 < argc) sentinel   = parse_u32(argv[++i]);
        else if (a == "--stop-pc"    && i + 1 < argc) {
            stop_pc = parse_u32(argv[++i]);
            use_stop_pc = true;
        }
        else if (a == "--irq-level"  && i + 1 < argc) irq_level = parse_u32(argv[++i]) & 7u;
        else if (a == "--irq-at-pc"  && i + 1 < argc) {
            irq_at_pc = parse_u32(argv[++i]);
            use_irq_at_pc = true;
        }
        else if (a == "--irq-event" && i + 1 < argc) {
            const char* spec = argv[++i];
            const char* sep = std::strchr(spec, ':');
            if (!sep) {
                std::fprintf(stderr, "bad --irq-event, expected <pc>:<level>: %s\n", spec);
                return 2;
            }
            std::string pc(spec, sep - spec);
            irq_events.push_back({parse_u32(pc.c_str()), parse_u32(sep + 1) & 7u});
        }
        else if (a == "--ack-vector" && i + 1 < argc) {
            ack_response = (int)(parse_u32(argv[++i]) & 0xffu);
        }
        else if (a == "--ack-spurious") ack_response = -2;
        else if (a == "--max-cycles" && i + 1 < argc) max_cycles = (int)parse_u32(argv[++i]);
        else if (a == "--out"        && i + 1 < argc) out_path   = argv[++i];
        else if (a == "--help" || a == "-h") { std::fputs(USAGE, stdout); return 0; }
        else {
            std::fprintf(stderr, "unknown arg: %s\n%s", a.c_str(), USAGE);
            return 2;
        }
    }

    if (bin_path.empty() || out_path.empty()) {
        std::fputs(USAGE, stderr);
        return 2;
    }

    MusashiRef ref;
    ref.set_cpu_type(MusashiRef::CPU_68030);
    ref.set_interrupt_ack_response(ack_response);

    int64_t loaded = ref.load_binary(bin_path, load_addr);
    if (loaded < 0) {
        std::fprintf(stderr, "musashi_run: failed to load %s\n", bin_path.c_str());
        return 2;
    }

    // Don't record writes into the binary's own region or the sentinel
    // region as "user-visible" mem diffs — the sentinel write is
    // already reported via `pass=` and ROM-region writes don't happen
    // in normal programs but shouldn't cause false fails if they do.
    ref.add_readonly_range(sentinel, sentinel + 0x10);
    ref.add_readonly_range(0, 8);   // reset vector we wrote manually

    ref.reset(load_addr, initial_sp);
    if (use_irq_at_pc) {
        irq_events.push_back({irq_at_pc, irq_level});
    }
    if (irq_events.empty()) {
        ref.set_irq(irq_level);
    }

    int cycles = ref.run_until_sentinel_or_pc_with_irq_events(
        sentinel, use_stop_pc, stop_pc, irq_events, max_cycles);

    // Emit the state in the same format tb_top.cpp's dump_final_state()
    // produces, so fuzz.py can diff them line-for-line.
    FILE* f = std::fopen(out_path.c_str(), "w");
    if (!f) {
        std::fprintf(stderr, "musashi_run: cannot open %s\n", out_path.c_str());
        return 2;
    }
    bool pass = use_stop_pc
                ? ref.hit_stop_pc()
                : (ref.hit_sentinel() && ref.last_sentinel_size() == 4
                   && ref.last_sentinel_value() == 0xC0FFEE00u);
    std::fprintf(f, "pass=%d\n",      pass ? 1 : 0);
    std::fprintf(f, "sentinel_hit=%d\n", ref.hit_sentinel() ? 1 : 0);
    std::fprintf(f, "sentinel_value=0x%08x\n", ref.last_sentinel_value());
    std::fprintf(f, "sentinel_size=%d\n", ref.last_sentinel_size());
    std::fprintf(f, "cycles=%d\n",    cycles);
    std::fprintf(f, "committed=0\n"); // not meaningful for ISS; keep key stable
    std::fprintf(f, "last_pc=0x%08x\n", ref.get_reg(MusashiRef::REG_PC));
    std::fprintf(f, "pc=0x%08x\n", use_stop_pc && ref.hit_stop_pc()
                                      ? stop_pc
                                      : ref.get_reg(MusashiRef::REG_PC));
    std::fprintf(f, "sr=0x%04x\n",  ref.get_reg(MusashiRef::REG_SR) & 0xFFFF);
    std::fprintf(f, "ccr=0x%02x\n",  ref.get_reg(MusashiRef::REG_SR) & 0x1F);
    for (int i = 0; i < 8; i++) {
        uint32_t v = ref.get_reg((MusashiRef::Reg)(MusashiRef::REG_D0 + i));
        std::fprintf(f, "d%d=0x%08x\n", i, v);
    }
    for (int i = 0; i < 8; i++) {
        uint32_t v = ref.get_reg((MusashiRef::Reg)(MusashiRef::REG_A0 + i));
        std::fprintf(f, "a%d=0x%08x\n", i, v);
    }
    auto writes = ref.final_mem_writes();
    std::fprintf(f, "mem_writes=%zu\n", writes.size());
    for (const auto& w : writes) {
        std::fprintf(f, "mem[0x%08x]=0x%02x\n", w.addr, w.val);
    }
    auto write_events = ref.final_mem_write_events();
    std::fprintf(f, "mem_write_events=%zu\n", write_events.size());
    for (const auto& w : write_events) {
        std::fprintf(f, "mem_event[0x%08x]=0x%02x\n", w.addr, w.val);
    }
    auto ram = ref.final_memory_snapshot();
    std::fprintf(f, "ram_bytes=%zu\n", ram.size());
    for (const auto& b : ram) {
        std::fprintf(f, "ram[0x%08x]=0x%02x\n", b.addr, b.val);
    }
    std::fclose(f);
    return pass ? 0 : 1;
}
