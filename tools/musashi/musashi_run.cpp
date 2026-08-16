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
  "[--initial-sr <hex>] [--initial-msp <hex>] "
  "[--sentinel <hex>] [--stop-pc <hex>] [--irq-level <0-7>] "
  "[--irq-at-pc <hex>] [--irq-event <pc>:<0-7>] [--ack-vector <2-255>|--ack-spurious] "
  "[--max-cycles <n>] --out <path> [--trace <path>]\n";

static uint32_t parse_u32(const char* s) {
    if (!s) return 0;
    int base = (std::strlen(s) > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X'))
                 ? 16 : 10;
    return (uint32_t)std::strtoul(s, nullptr, base);
}

int main(int argc, char** argv) {
    std::string bin_path;
    std::string out_path;
    std::string trace_path;
    uint32_t load_addr  = 0x40800000u;
    uint32_t initial_sp = 0x00800000u;
    uint32_t sentinel   = 0xFFFF0000u;
    uint32_t stop_pc    = 0;
    bool     use_stop_pc = false;
    unsigned int irq_level = 0;
    uint32_t irq_at_pc = 0;
    bool     use_irq_at_pc = false;
    uint32_t initial_sr = 0;
    bool     use_initial_sr = false;   // override the post-reset SR (e.g. lower the I-mask)
    uint32_t initial_msp = 0;
    bool     use_initial_msp = false;  // override the post-reset MSP (needed for M=1 tests)
    std::vector<std::pair<uint32_t, unsigned int>> irq_events;
    int      ack_response = -1;
    int      max_cycles = 200000;
    bool     use_mmu = false;
    uint32_t mmu_root = 0;
    uint32_t mmu_data_lo = 0;
    uint32_t mmu_data_hi = 0;
    uint32_t mmu_instr_lo = 0;
    uint32_t mmu_instr_hi = 0;
    // Page-table preload: little-endian longwords {addr, word} written before run
    // (the descriptor format is read LE). Lets the harness seed the resident
    // root/pointer descriptors the data-page walk needs (the handler writes the leaf).
    std::vector<std::pair<uint32_t, uint32_t>> pt_preload;

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
        else if (a == "--initial-sr"  && i + 1 < argc) { initial_sr  = parse_u32(argv[++i]) & 0xffffu; use_initial_sr  = true; }
        else if (a == "--initial-msp" && i + 1 < argc) { initial_msp = parse_u32(argv[++i]);           use_initial_msp = true; }
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
        else if (a == "--mmu-root"    && i + 1 < argc) { mmu_root = parse_u32(argv[++i]); use_mmu = true; }
        else if (a == "--mmu-data-lo" && i + 1 < argc) mmu_data_lo = parse_u32(argv[++i]);
        else if (a == "--mmu-data-hi" && i + 1 < argc) mmu_data_hi = parse_u32(argv[++i]);
        else if (a == "--mmu-instr-lo" && i + 1 < argc) mmu_instr_lo = parse_u32(argv[++i]);
        else if (a == "--mmu-instr-hi" && i + 1 < argc) mmu_instr_hi = parse_u32(argv[++i]);
        else if (a == "--mmu-pt" && i + 1 < argc) {
            const char* spec = argv[++i];
            const char* sep = std::strchr(spec, ':');
            if (!sep) { std::fprintf(stderr, "bad --mmu-pt, expected <addr>:<word>: %s\n", spec); return 2; }
            std::string addr(spec, sep - spec);
            pt_preload.push_back({parse_u32(addr.c_str()), parse_u32(sep + 1)});
        }
        else if (a == "--out"        && i + 1 < argc) out_path   = argv[++i];
        else if (a == "--trace"      && i + 1 < argc) trace_path = argv[++i];
        else if (a == "--help" || a == "-h") { std::fputs(USAGE, stdout); return 0; }
        else {
            std::fprintf(stderr, "unknown arg: %s\n%s", a.c_str(), USAGE);
            return 2;
        }
    }

    if (bin_path.empty() || (out_path.empty() && trace_path.empty())) {
        std::fputs(USAGE, stderr);
        return 2;
    }

    MusashiRef ref;
    ref.set_cpu_type(MusashiRef::CPU_68040);
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

    // Page-table preload (little-endian longwords).
    for (const auto& e : pt_preload) {
        uint32_t a = e.first, w = e.second;
        ref.write8(a + 0, (uint8_t)(w & 0xff));
        ref.write8(a + 1, (uint8_t)((w >> 8) & 0xff));
        ref.write8(a + 2, (uint8_t)((w >> 16) & 0xff));
        ref.write8(a + 3, (uint8_t)((w >> 24) & 0xff));
    }

    if (use_irq_at_pc) {
        irq_events.push_back({irq_at_pc, irq_level});
    }

    int cycles = 0;

    if (!trace_path.empty()) {
        // ── Per-instruction trace mode ────────────────────────────────
        // Drive the CPU one instruction at a time and emit a post-instruction
        // state record after each step.
        //
        // Use reset_direct so the Musashi reset-processing bookkeeping
        // cycle is drained internally before we start stepping user code.
        // This ensures the first step_one() executes the first user instruction,
        // not the reset exception handler.
        ref.reset_direct(load_addr, initial_sp);
        if (use_initial_sr)  ref.set_reg(MusashiRef::REG_SR,  initial_sr);
        if (use_initial_msp) ref.set_reg(MusashiRef::REG_MSP, initial_msp);
        if (irq_events.empty()) {
            ref.set_irq(irq_level);
        }
        if (use_mmu) ref.enable_mmu(mmu_root, mmu_data_lo, mmu_data_hi);
        if (use_mmu) ref.set_instr_window(mmu_instr_lo, mmu_instr_hi);

        FILE* tf = std::fopen(trace_path.c_str(), "w");
        if (!tf) {
            std::fprintf(stderr, "musashi_run: cannot open trace file %s\n", trace_path.c_str());
            return 2;
        }

        ref.begin_trace(sentinel);
        int cycles_left = max_cycles;
        size_t trace_irq_index = 0;
        bool   irq_asserted = false;   // one-shot edge bookkeeping

        while (cycles_left > 0 && !ref.hit_sentinel()) {
            if (use_stop_pc && ref.get_reg(MusashiRef::REG_PC) == stop_pc) break;

            // Apply PC-scheduled IRQ events (one-shot edge): when PC reaches an
            // event's PC, raise the line to that level for the NEXT step (which
            // takes the interrupt at this macro-instruction boundary), then drop it
            // so a level-triggered re-fire after RTE does not loop. This models a
            // SoC that asserts IPL for one boundary and de-asserts after IACK.
            if (irq_asserted) {
                // Drop the line once PC has left the event PC (the IRQ was taken /
                // the instruction advanced) so it is a single edge.
                ref.set_irq(0);
                irq_asserted = false;
            }
            while (trace_irq_index < irq_events.size() &&
                   ref.get_reg(MusashiRef::REG_PC) == irq_events[trace_irq_index].first) {
                ref.set_irq(irq_events[trace_irq_index].second);
                irq_asserted = true;
                trace_irq_index++;
            }

            int n = ref.step_one();
            if (n <= 0) n = 1;
            cycles      += n;
            cycles_left -= n;

            // Emit post-instruction state
            uint32_t pc = ref.get_reg(MusashiRef::REG_PC);
            uint32_t sr = ref.get_reg(MusashiRef::REG_SR) & 0xFFFFu;
            // FP state (68040 FPU).  Each FP register is emitted as two fields:
            // fpNh = floatx80.high (sign + 15-bit biased exponent), fpNl =
            // floatx80.low (64-bit significand WITH the explicit integer bit).
            // The consumer recombines them as (fpNh << 64) | fpNl.  Emitted
            // unconditionally: OracleStep.scala is the sole consumer and is
            // key/value based, so unknown keys are ignored; for a non-FP program
            // every field is 0.  Cost measured during plan-writing: 283 -> 620
            // bytes per step line (2.19x).
            Fp80 fp[8];
            for (int i = 0; i < 8; i++) fp[i] = ref.get_fp(i);
            std::fprintf(tf,
                "step pc=0x%08x sr=0x%04x"
                " d0=0x%08x d1=0x%08x d2=0x%08x d3=0x%08x"
                " d4=0x%08x d5=0x%08x d6=0x%08x d7=0x%08x"
                " a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x"
                " a4=0x%08x a5=0x%08x a6=0x%08x a7=0x%08x"
                " msp=0x%08x isp=0x%08x"
                " fp0h=0x%04x fp0l=0x%016llx fp1h=0x%04x fp1l=0x%016llx"
                " fp2h=0x%04x fp2l=0x%016llx fp3h=0x%04x fp3l=0x%016llx"
                " fp4h=0x%04x fp4l=0x%016llx fp5h=0x%04x fp5l=0x%016llx"
                " fp6h=0x%04x fp6l=0x%016llx fp7h=0x%04x fp7l=0x%016llx"
                " fpcr=0x%08x fpsr=0x%08x fpiar=0x%08x\n",
                pc, sr,
                ref.get_reg(MusashiRef::REG_D0), ref.get_reg(MusashiRef::REG_D1),
                ref.get_reg(MusashiRef::REG_D2), ref.get_reg(MusashiRef::REG_D3),
                ref.get_reg(MusashiRef::REG_D4), ref.get_reg(MusashiRef::REG_D5),
                ref.get_reg(MusashiRef::REG_D6), ref.get_reg(MusashiRef::REG_D7),
                ref.get_reg(MusashiRef::REG_A0), ref.get_reg(MusashiRef::REG_A1),
                ref.get_reg(MusashiRef::REG_A2), ref.get_reg(MusashiRef::REG_A3),
                ref.get_reg(MusashiRef::REG_A4), ref.get_reg(MusashiRef::REG_A5),
                ref.get_reg(MusashiRef::REG_A6), ref.get_reg(MusashiRef::REG_A7),
                ref.get_reg(MusashiRef::REG_MSP), ref.get_reg(MusashiRef::REG_ISP),
                fp[0].high, (unsigned long long)fp[0].low,
                fp[1].high, (unsigned long long)fp[1].low,
                fp[2].high, (unsigned long long)fp[2].low,
                fp[3].high, (unsigned long long)fp[3].low,
                fp[4].high, (unsigned long long)fp[4].low,
                fp[5].high, (unsigned long long)fp[5].low,
                fp[6].high, (unsigned long long)fp[6].low,
                fp[7].high, (unsigned long long)fp[7].low,
                ref.get_fpcr(), ref.get_fpsr(), ref.get_fpiar());
        }
        std::fclose(tf);
    } else {
        // ── Final-state mode (original behaviour) ─────────────────────
        ref.reset(load_addr, initial_sp);
        if (use_initial_sr) ref.set_reg(MusashiRef::REG_SR, initial_sr);
        if (irq_events.empty()) {
            ref.set_irq(irq_level);
        }
        if (use_mmu) ref.enable_mmu(mmu_root, mmu_data_lo, mmu_data_hi);
        if (use_mmu) ref.set_instr_window(mmu_instr_lo, mmu_instr_hi);
        cycles = ref.run_until_sentinel_or_pc_with_irq_events(
            sentinel, use_stop_pc, stop_pc, irq_events, max_cycles);
    }

    // Emit the state in the same format tb_top.cpp's dump_final_state()
    // produces, so fuzz.py can diff them line-for-line.
    if (out_path.empty()) {
        bool pass = use_stop_pc
                    ? ref.hit_stop_pc()
                    : (ref.hit_sentinel() && ref.last_sentinel_size() == 4
                       && ref.last_sentinel_value() == 0xC0FFEE00u);
        return pass ? 0 : 1;
    }
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
    // FP state.  Same two-field-per-register encoding as the --trace records.
    // Appended AFTER the a0..a7 block and BEFORE mem_writes= so the existing key
    // order Musashi.scala's parseOutput reads is untouched -- that parser is
    // key/value based and ignores keys it does not ask for.
    for (int i = 0; i < 8; i++) {
        Fp80 v = ref.get_fp(i);
        std::fprintf(f, "fp%dh=0x%04x\n", i, v.high);
        std::fprintf(f, "fp%dl=0x%016llx\n", i, (unsigned long long)v.low);
    }
    std::fprintf(f, "fpcr=0x%08x\n",  ref.get_fpcr());
    std::fprintf(f, "fpsr=0x%08x\n",  ref.get_fpsr());
    std::fprintf(f, "fpiar=0x%08x\n", ref.get_fpiar());
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
