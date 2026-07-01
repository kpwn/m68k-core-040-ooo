package m68k040.fuzz

import scala.util.Random

/** Constrained-random 68k program generator for Musashi lock-step fuzzing.
  *
  * Emits GNU-as (m68k-linux-gnu-as) source built from self-contained TEMPLATE
  * BLOCKS. Every block sets up its own registers/addresses, so blocks can be
  * removed independently by the minimizer without breaking the program.
  * Fully deterministic from a single seed.
  *
  * ── Program shape ──────────────────────────────────────────────────────────
  *   [prologue]  seed stores: one `move.l #rand32,ABS` per sandbox long, so
  *               every sandbox byte is architecturally WRITTEN before any load
  *               (the DUT's behavioral D-mem defaults unwritten bytes to a
  *               non-zero pattern while Musashi's RAM defaults to 0 — loads
  *               may only touch seeded bytes). NOT removable by the minimizer.
  *   [body]      N random template blocks (weighted), each optionally prefixed
  *               with a NOP so every template lands in BOTH fetch-group slots
  *               (slot-1 is where the decode-race bug class lives).
  *   [end]       `Lend: bra.s Lend` — the FINAL 2 bytes of the image, so the
  *               harness computes stopPc = loadAddr + imageLen - 2.
  *
  * ── Termination by construction ────────────────────────────────────────────
  *   - conditional/unconditional branches only jump FORWARD within their block
  *   - DBcc loops use a counter <= 4 (terminate regardless of the condition)
  *   - BSR/JSR call a block-local subroutine that returns (RTS/RTD)
  *   - TRAP/TRAPV/TRAPcc/CHK install their own handler (plain RTE) first
  *   plus the harness cycle cap.
  *
  * ── Memory safety ──────────────────────────────────────────────────────────
  *   All data accesses land in the seeded sandbox window; each memory template
  *   re-seeds its own An/Xn immediately before use, and displacement/index
  *   values are chosen so the full access span stays in-window. Accesses do
  *   NOT cross a 16-byte cache line (see exclusion list). A7 is reserved for
  *   the stack (boot SSP 0x00100000); stack pushes are always balanced.
  *
  * ── TEMPLATE INVENTORY (the implemented-ISA whitelist) ─────────────────────
  *   MOVEQ; ALU reg/imm (ADD/SUB/AND/OR/CMP/EOR + ADDI/SUBI/ANDI/ORI/EORI/CMPI,
  *   .B/.W/.L); ADDQ/SUBQ (Dn/An/mem); ADDA/SUBA/CMPA; MOVE/MOVEA all EA modes
  *   ((An), (An)+, -(An), (d16,An), (d8,An,Xn.w/.l*1/2/4/8), abs.W/.L,
  *   full-format no-mem-indirect (word/long bd, BS/IS suppress), mem-indirect
  *   pre/post-index) incl mem->mem; ALU mem-src + mem-dest RMW (+imm RMW);
  *   CLR/NEG/NEGX/NOT/TST (Dn + mem); shifts/rotates ASL/ASR/LSL/LSR/ROXL/
  *   ROXR/ROL/ROR .B/.W/.L imm+reg counts (register form); BTST/BCHG/BCLR/
  *   BSET static+dynamic (Dn + mem byte incl (An)+/-(An)); bit-field REGISTER
  *   forms static+dynamic (all 8 ops) + MEMORY STATIC forms ((An)/(d16,An));
  *   EXT/EXTB/SWAP/TAS-Dn; ADDX/SUBX (reg + -(An) mem); ABCD/SBCD (reg +
  *   -(An) mem); PACK/UNPK (reg); EXG; MULU/MULS .W/.L32/.L64; DIVU/DIVS
  *   .W/.L32/.L64 (divisor nonzero by construction); CMP2/CHK2 (.B/.W/.L,
  *   (An)/(d16,An)/(d8,An,Xn)); CAS .B/.W/.L ((An)/(An)+/-(An)) + CAS2 .W/.L;
  *   MOVEM .W/.L ((d16,An) store/load, (An)+ load, -(A7)/(A7)+ push-pop);
  *   MOVEP .W/.L both directions; LEA/PEA (control EAs); LINK/UNLK;
  *   Scc Dn; DBcc (small counts); Bcc/BRA forward; BSR/JSR/RTS/RTD/RTR;
  *   JMP (fwd, abs/(An)); MOVE to/from CCR + ANDI/ORI/EORI-to-CCR;
  *   MOVE from SR (supervisor boot); MOVE USP (write-then-read);
  *   MOVEC SFC/DFC round-trip; MOVES all sizes/dirs (supervisor);
  *   TRAP #n / TRAPV / TRAPcc / CHK (sparingly, self-installed handler).
  *
  * ── EXCLUSION LIST (documented-by-design gaps ONLY — not real divergences) ─
  *   - line-A / line-F (unimplemented traps, not fuzz-worthy yet)
  *   - .L-IMMEDIATE source with a full-format/mem-indirect (indexed) DEST:
  *     DUT traps by design this slice, Musashi executes (MOVE.L #imm,(bd,An,Xn)
  *     and ADDI.L-class #imm,(full-format)). .B/.W imm + full-format IS in.
  *   - bit-field MEMORY forms with DYNAMIC offset/width (Do/Dw=1): in-flight
  *     slice 3c — currently silently MIS-CRACKS as static (known gap)
  *   - SR writes / STOP / RESET (v1 keeps the SR system byte stable; RESET is
  *     a serializing privileged nop, excluded with the other sysops)
  *   - CMPM (An)+,(An)+ : not decoded (line-B mode-001 -> illegal in the DUT)
  *   - MOVE #imm,<mem> (immediate-SOURCE store): the MOVE store crack requires
  *     a REGISTER source (MicroOpAssembler crackStore srcIsReg) -> DUT illegal;
  *     ADDI/…-class #imm,<mem> RMW IS in scope (separate crack)
  *   - NOP (0x4E71): decodes ILLEGAL in the DUT (no line-4 arm) — FUZZ FINDING
  *     (benign completeness gap, never executed by the directed suite); the
  *     slot-parity filler uses moveq instead until NOP ships
  *   - memory Scc / memory TAS / memory shifts (1-bit <ea> form): deferred in
  *     the decode suite -> DUT illegal
  *   - PC-RELATIVE DATA references ((d16,PC)/(d8,PC,Xn) loads, LEA/PEA (d16,PC)):
  *     HARNESS limitation, not a DUT gap — the DUT's D-memory agent does not
  *     hold the program image, so a PC-relative data load reads different bytes
  *     than Musashi. JMP/JSR pc-rel (pure fetch) would be fine but are covered
  *     via abs/(An) here.
  *   - accesses CROSSING a 16-byte cache line: known PRE-EXISTING LS-EU
  *     cross-line-store-after-load drain bug (documented at the quarantined
  *     directed tests, ExecuteLockStepSpec ~line 1425). Excluded so the sweep
  *     doesn't rediscover it 100 times; set FUZZ_ALLOW_CROSSLINE=1 to re-enable.
  *   - vector-table addresses 0x000-0x0FF are reserved for the trap templates'
  *     handler installs (outside the compared sandbox)
  */
object ProgGen {

  // ── Sandbox geometry ────────────────────────────────────────────────────────
  val SandboxBase: Long = 0x00004000L
  val SandboxLongs      = 16                    // 64 bytes, all seeded by the prologue
  val SandboxSize       = SandboxLongs * 4
  // Template EAs constrained to [base+WinLo, base+WinHi); the margins absorb
  // predec/postinc drift, MOVEM bursts (<=16B), MOVEP spans (7B), BF spans (5B).
  val WinLo = 16
  val WinHi = SandboxSize - 16

  val allowCrossLine: Boolean = sys.env.get("FUZZ_ALLOW_CROSSLINE").contains("1")

  final case class Block(lines: Vector[String], removable: Boolean = true)

  final case class Prog(seed: Long, prologue: Vector[Block], body: Vector[Block]) {
    def blocks: Vector[Block] = prologue ++ body
    def source: String        = render(blocks)
  }

  /** Render blocks + the terminal spin. The end block is ALWAYS the final 2
    * image bytes (bra.s = 2 bytes), so stopPc = loadAddr + imageLen - 2. */
  def render(blocks: Vector[Block]): String = {
    val sb = new StringBuilder
    blocks.foreach(_.lines.foreach { l => sb.append(l).append('\n') })
    sb.append("Lend:\n\tbra.s Lend\n")
    sb.toString
  }

  def generate(seed: Long, nBodyBlocks: Int): Prog = {
    val r   = new Random(seed)
    val gen = new Gen(r)
    // Sandbox seeding is register-mediated: MOVE #imm,<mem> is a documented
    // decode gap (crackStore requires a REGISTER source), so each seed store is
    // [move.l #rand,%dK ; move.l %dK,ABS]. NOT removable (loads depend on it).
    val memSeeds = (0 until SandboxLongs).map { i =>
      val dr = s"%d${i % 8}"
      Block(Vector(f"\tmove.l #0x${r.nextLong() & 0xffffffffL}%x,$dr",
                   f"\tmove.l $dr,0x${SandboxBase + i * 4}%x"),
            removable = false)
    }.toVector
    // Register entropy: leave every D/A reg (A7 excluded) with a random value.
    // Removable — memory templates re-seed their own An/Xn before use.
    val regSeeds = (0 until 8).map { i =>
      Block(Vector(f"\tmove.l #0x${r.nextLong() & 0xffffffffL}%x,%%d$i"))
    }.toVector ++ (0 until 7).map { i =>
      Block(Vector(f"\tmove.l #0x${r.nextLong() & 0xffffffffL}%x,%%a$i"))
    }.toVector
    val body = Vector.fill(nBodyBlocks)(gen.nextBlock())
    Prog(seed, memSeeds ++ regSeeds, body)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  private final class Gen(r: Random) {
    private var labelId = 0
    private def lbl(tag: String): String = { labelId += 1; s"L${tag}_$labelId" }

    // registers: D0-D7 free; A0-A6 free; A7 = stack ONLY (never a random target)
    private def d(): String  = s"%d${r.nextInt(8)}"
    private def a(): String  = s"%a${r.nextInt(7)}"
    private def dn(): Int    = r.nextInt(8)
    private def pick[T](xs: Seq[T]): T = xs(r.nextInt(xs.size))

    private def rand32(): Long = r.nextInt(4) match {
      case 0 => pick(Seq(0L, 1L, 2L, 0xffffffffL, 0x80000000L, 0x7fffffffL,
                         0xff00ff00L, 0x0000ffffL, 0x00008000L, 0x00000080L, 0xfffffffeL))
      case _ => r.nextLong() & 0xffffffffL
    }
    private def rand16(): Long = rand32() & 0xffffL
    private def rand8(): Long  = rand32() & 0xffL
    private def imm32(): String = f"#0x${rand32()}%x"
    private def imm16(): String = f"#0x${rand16()}%x"
    private def imm8(): String  = f"#0x${rand8()}%x"
    private def sized(sz: Char): String = imm(sz)
    private def imm(sz: Char): String = sz match {
      case 'b' => imm8(); case 'w' => imm16(); case _ => imm32()
    }
    private def size(): Char = pick(Seq('b', 'w', 'l'))
    private def sizeWL(): Char = pick(Seq('w', 'l'))

    /** an in-window sandbox byte address for an access of `span` bytes that
      * does not cross a 16-byte line (unless FUZZ_ALLOW_CROSSLINE=1), with
      * optional alignment. */
    private def sbAddr(span: Int, align: Int = 1): Long = {
      var tries = 0
      while (true) {
        tries += 1
        val off  = WinLo + r.nextInt(WinHi - WinLo - span + 1)
        val addr = SandboxBase + (off / align) * align
        val ok   = allowCrossLine || ((addr & 15) + span <= 16)
        if (ok && addr + span <= SandboxBase + WinHi) return addr
        if (tries > 64) return SandboxBase + WinLo // aligned-16, always fits
      }
      0L // unreachable
    }
    private def spanOf(sz: Char): Int = sz match { case 'b' => 1; case 'w' => 2; case _ => 4 }
    private def alignOf(sz: Char): Int = spanOf(sz) // keep sized accesses natural-aligned by default

    private def hex(v: Long): String = f"0x$v%x"

    /** A memory EA over the sandbox for one sized access.
      * Returns (setup lines, EA text, effective address). `wide` allows the
      * complex modes (indexed/full-format/mem-indirect); postIncDec allows
      * (An)+/-(An). The final address is exact and in-window by construction. */
    private def memEa(sz: Char, wide: Boolean = true, incDec: Boolean = true,
                      misalign: Boolean = false): (Vector[String], String, Long) = {
      val span  = spanOf(sz)
      val align = if (misalign && sz != 'b') 1 else alignOf(sz)
      val addr  = sbAddr(span, align)
      val an    = a()
      val modes = Seq.newBuilder[Int]
      modes ++= Seq(0, 0, 1, 1, 2) // (An), (d16,An), abs
      if (incDec) modes ++= Seq(3, 4)
      if (wide)   modes ++= Seq(5, 6, 7)
      pick(modes.result()) match {
        case 0 => // (An)
          (Vector(s"\tmove.l #${hex(addr)},$an"), s"($an)", addr)
        case 1 => // (d16,An)
          val d16 = r.nextInt(33) - 16
          (Vector(s"\tmove.l #${hex(addr - d16)},$an"), s"$d16($an)", addr)
        case 2 => // (xxx).L / .W absolute (sandbox fits abs.W positive)
          if (r.nextBoolean()) (Vector.empty, s"(${hex(addr)}).l", addr)
          else                 (Vector.empty, s"(${hex(addr)}).w", addr)
        case 3 => // (An)+
          (Vector(s"\tmove.l #${hex(addr)},$an"), s"($an)+", addr)
        case 4 => // -(An)
          (Vector(s"\tmove.l #${hex(addr + span)},$an"), s"-($an)", addr)
        case 5 => // (d8,An,Xn.w/.l*scale) brief indexed
          val scale = pick(Seq(1, 2, 4, 8))
          val useAn = r.nextBoolean()
          val xr    = if (useAn) a() else d()
          val wl    = if (r.nextBoolean() || useAn) "l" else "w"
          val xv    = r.nextInt(9) - 4          // small index, sign-exercised
          val d8    = r.nextInt(65) - 32
          val base  = addr - d8 - xv.toLong * scale
          val setX  = if (xr == an) Vector.empty // avoid clobber ordering issues: distinct regs only
                      else Vector(s"\tmove.l #${hex(xv.toLong & 0xffffffffL)},$xr")
          if (xr == an) // fall back to (An) if the rng collided
            (Vector(s"\tmove.l #${hex(addr)},$an"), s"($an)", addr)
          else
            (setX :+ s"\tmove.l #${hex(base & 0xffffffffL)},$an",
             s"($d8,$an,$xr.$wl*$scale)", addr)
        case 6 => // full-format, no memory indirect: word/long bd, optional BS/IS
          val scale = pick(Seq(1, 2, 4))
          val xr    = d()
          val xv    = r.nextInt(9) - 4
          r.nextInt(4) match {
            case 0 => // word bd (>=0x80 forces full format)
              val bd = 0x100 + r.nextInt(0x40)
              val base = addr - bd - xv.toLong * scale
              (Vector(s"\tmove.l #${hex(xv.toLong & 0xffffffffL)},$xr",
                      s"\tmove.l #${hex(base & 0xffffffffL)},$an"),
               s"(${hex(bd.toLong)},$an,$xr.l*$scale)", addr)
            case 1 => // long bd
              val bd = 0x10000L + r.nextInt(0x40)
              val base = addr - bd - xv.toLong * scale
              (Vector(s"\tmove.l #${hex(xv.toLong & 0xffffffffL)},$xr",
                      s"\tmove.l #${hex(base & 0xffffffffL)},$an"),
               s"(${hex(bd)},$an,$xr.l*$scale)", addr)
            case 2 => // base suppressed: ea = bd + Xn*scale
              val bd = addr - xv.toLong * scale
              (Vector(s"\tmove.l #${hex(xv.toLong & 0xffffffffL)},$xr"),
               s"(${hex(bd)},%za1,$xr.l*$scale)", addr)
            case _ => // index suppressed: ea = An + bd
              val bd = 0x100 + r.nextInt(0x40)
              (Vector(s"\tmove.l #${hex(addr - bd)},$an"),
               s"(${hex(bd.toLong)},$an,%zd1.l)", addr)
          }
        case _ => // memory-indirect pre/post-index
          val ptrSlot = sbAddr(4, 4)
          val od      = pick(Seq(0, 4, 8))
          val pre     = r.nextBoolean()
          val xr0     = dn(); val tmp0 = (xr0 + 1 + r.nextInt(7)) % 8   // tmp != xr (setup ordering)
          val xr      = s"%d$xr0"; val tmp = s"%d$tmp0"
          val xv      = r.nextInt(5) - 2
          val scale   = pick(Seq(1, 2, 4))
          if (pre) {
            // ea = mem[bd + An + Xn*s] + od
            val bd   = 0x10 + r.nextInt(0x10)
            val base = ptrSlot - bd - xv.toLong * scale
            val ptr  = addr - od
            (Vector(s"\tmove.l #${hex(xv.toLong & 0xffffffffL)},$xr",
                    s"\tmove.l #${hex(base & 0xffffffffL)},$an",
                    s"\tmove.l #${hex(ptr & 0xffffffffL)},$tmp",
                    s"\tmove.l $tmp,(${hex(ptrSlot)}).l"),
             s"([${hex(bd.toLong)},$an,$xr.l*$scale],${hex(od.toLong)})", addr)
          } else {
            // ea = mem[bd + An] + Xn*s + od
            val bd   = 0x10 + r.nextInt(0x10)
            val base = ptrSlot - bd
            val ptr  = addr - od - xv.toLong * scale
            (Vector(s"\tmove.l #${hex(xv.toLong & 0xffffffffL)},$xr",
                    s"\tmove.l #${hex(base & 0xffffffffL)},$an",
                    s"\tmove.l #${hex(ptr & 0xffffffffL)},$tmp",
                    s"\tmove.l $tmp,(${hex(ptrSlot)}).l"),
             s"([${hex(bd.toLong)},$an],$xr.l*$scale,${hex(od.toLong)})", addr)
          }
      }
    }

    /** simple (non-complex) memory EA: (An)/(d16,An)/abs — for op families whose
      * directed coverage is MEMSIMPLE-only. */
    private def memEaSimple(sz: Char): (Vector[String], String, Long) =
      memEa(sz, wide = false, incDec = false)

    private val ccList = Seq("hi", "ls", "cc", "cs", "ne", "eq", "vc", "vs",
                             "pl", "mi", "ge", "lt", "gt", "le")

    // ── templates ────────────────────────────────────────────────────────────
    private def tMoveq(): Vector[String] =
      Vector(s"\tmoveq #${r.nextInt(256) - 128},${d()}")

    private def tAluReg(): Vector[String] = {
      val sz = size()
      pick(Seq("add", "sub", "and", "or", "cmp")) match {
        case op => Vector(s"\t$op.$sz ${d()},${d()}")
      }
    }

    private def tEorReg(): Vector[String] =
      Vector(s"\teor.${size()} ${d()},${d()}")

    private def tAluImm(): Vector[String] = {
      val sz = size()
      val op = pick(Seq("addi", "subi", "andi", "ori", "eori", "cmpi"))
      Vector(s"\t$op.$sz ${imm(sz)},${d()}")
    }

    private def tAluSrcImm(): Vector[String] = {
      val sz = size()
      Vector(s"\t${pick(Seq("add", "sub", "and", "or", "cmp"))}.$sz ${imm(sz)},${d()}")
    }

    private def tAddqSubq(): Vector[String] = {
      val q  = 1 + r.nextInt(8)
      val op = pick(Seq("addq", "subq"))
      if (r.nextInt(4) == 0) Vector(s"\t$op.${sizeWL()} #$q,${a()}")
      else                   Vector(s"\t$op.${size()} #$q,${d()}")
    }

    private def tAdda(): Vector[String] = {
      val sz = sizeWL()
      val op = pick(Seq("adda", "suba", "cmpa"))
      val src = r.nextInt(3) match {
        case 0 => d()
        case 1 => a()
        case _ => imm(sz)
      }
      Vector(s"\t$op.$sz $src,${a()}")
    }

    private def tAdaMemSrc(): Vector[String] = {
      val sz = sizeWL()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\t${pick(Seq("adda", "suba", "cmpa"))}.$sz $ea,${a()}"
    }

    private def tMoveRegReg(): Vector[String] = {
      val sz = size()
      val src = if (sz == 'b') d() else pick(Seq(d(), a()))
      val dst = if (sz == 'b') d() else pick(Seq(d(), d(), a()))
      Vector(s"\tmove.$sz $src,$dst")
    }

    private def tMoveImm(): Vector[String] = {
      val sz = size()
      val dst = if (sz == 'b') d() else pick(Seq(d(), d(), a()))
      Vector(s"\tmove.$sz ${imm(sz)},$dst")
    }

    private def tMoveLoad(): Vector[String] = {
      val sz = size()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\tmove.$sz $ea,${d()}"
    }

    private def tMoveaLoad(): Vector[String] = {
      val sz = sizeWL()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\tmove.$sz $ea,${a()}"
    }

    private def tMoveStore(): Vector[String] = {
      val sz = size()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\tmove.$sz ${d()},$ea"
    }

    // (NO tMoveImmStore: MOVE #imm,<mem> is a documented decode gap — the MOVE
    // store crack requires a REGISTER source; see the exclusion list.)

    private def tMoveMemMem(): Vector[String] = {
      val sz = size()
      val (s1, ea1, _) = memEa(sz, wide = false)
      val (s2, ea2, _) = memEa(sz, wide = false)
      s1 ++ s2 :+ s"\tmove.$sz $ea1,$ea2"
    }

    private def tAluMemSrc(): Vector[String] = {
      val sz = size()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\t${pick(Seq("add", "sub", "and", "or", "cmp"))}.$sz $ea,${d()}"
    }

    private def tAluMemRmw(): Vector[String] = {
      val sz = size()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\t${pick(Seq("add", "sub", "and", "or", "eor"))}.$sz ${d()},$ea"
    }

    private def tAluImmRmw(): Vector[String] = {
      val sz = size()
      val op = pick(Seq("addi", "subi", "andi", "ori", "eori", "cmpi"))
      // .L-imm + full-format dst excluded by design
      val (setup, ea, _) = if (sz == 'l') memEa(sz, wide = false) else memEa(sz)
      setup :+ s"\t$op.$sz ${imm(sz)},$ea"
    }

    private def tQuickMemRmw(): Vector[String] = {
      val sz = size()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\t${pick(Seq("addq", "subq"))}.$sz #${1 + r.nextInt(8)},$ea"
    }

    private def tUnaryReg(): Vector[String] =
      Vector(s"\t${pick(Seq("clr", "neg", "negx", "not", "tst"))}.${size()} ${d()}")

    private def tUnaryMem(): Vector[String] = {
      val sz = size()
      val (setup, ea, _) = memEa(sz)
      setup :+ s"\t${pick(Seq("clr", "neg", "negx", "not", "tst"))}.$sz $ea"
    }

    private def tShiftImm(): Vector[String] = {
      val op = pick(Seq("asl", "asr", "lsl", "lsr", "roxl", "roxr", "rol", "ror"))
      Vector(s"\t$op.${size()} #${1 + r.nextInt(8)},${d()}")
    }

    private def tShiftReg(): Vector[String] = {
      val op  = pick(Seq("asl", "asr", "lsl", "lsr", "roxl", "roxr", "rol", "ror"))
      val cnt = d(); val dst = d()
      Vector(s"\tmoveq #${r.nextInt(67)},$cnt",          // 0..66 exercises 0/mod-64/>=size
             s"\t$op.${size()} $cnt,$dst")
    }

    private def tBitopReg(): Vector[String] = {
      val op = pick(Seq("btst", "bchg", "bclr", "bset"))
      if (r.nextBoolean()) Vector(s"\t$op #${r.nextInt(36)},${d()}")
      else {
        val c = d(); val dst = d()
        Vector(s"\tmoveq #${r.nextInt(67)},$c", s"\t$op $c,$dst")
      }
    }

    private def tBitopMem(): Vector[String] = {
      val op = pick(Seq("btst", "bchg", "bclr", "bset"))
      val (setup, ea, _) = memEa('b', wide = false)
      if (r.nextBoolean()) setup :+ s"\t$op #${r.nextInt(10)},$ea"   // mod 8
      else {
        val c = d()
        setup ++ Vector(s"\tmoveq #${r.nextInt(16)},$c", s"\t$op $c,$ea")
      }
    }

    private def tBitfieldReg(): Vector[String] = {
      val off = r.nextInt(32); val wid = 1 + r.nextInt(32)
      val dy  = d()
      val dyn = r.nextBoolean() // register-form dynamic offset/width IS implemented
      val (setup, spec) =
        if (!dyn) (Vector.empty[String], s"{#$off:#$wid}")
        else {
          val or = d(); val wr = d()
          r.nextInt(3) match {
            case 0 => (Vector(s"\tmoveq #$off,$or"), s"{$or:#$wid}")
            case 1 => (Vector(s"\tmoveq #$wid,$wr"), s"{#$off:$wr}")
            case _ =>
              if (or == wr) (Vector(s"\tmoveq #$off,$or"), s"{$or:#$wid}")
              else (Vector(s"\tmoveq #$off,$or", s"\tmoveq #$wid,$wr"), s"{$or:$wr}")
          }
        }
      pick(Seq("bftst", "bfchg", "bfclr", "bfset", "bfextu", "bfexts", "bfffo", "bfins")) match {
        case op @ ("bfextu" | "bfexts" | "bfffo") => setup :+ s"\t$op $dy$spec,${d()}"
        case "bfins"                              => setup :+ s"\tbfins ${d()},$dy$spec"
        case op                                   => setup :+ s"\t$op $dy$spec"
      }
    }

    private def tBitfieldMem(): Vector[String] = {
      // STATIC offset/width only (mem-dynamic = in-flight slice 3c, excluded).
      // Span <= 5 bytes; keep the whole span inside one 16-byte line (the store
      // is an unaligned long at base+off/8 -> cross-line excluded).
      val off  = r.nextInt(32)
      val wid  = 1 + r.nextInt(32)
      val span = (off % 8 + wid + 7) / 8
      val base = { // long-aligned base whose [byteAddr, byteAddr+span) stays in-line
        var b = sbAddr(8, 4)
        var tries = 0
        while (!allowCrossLine && (((b + off / 8) & 15) + math.max(span.toLong, 4L) > 16) && tries < 64) {
          b = sbAddr(8, 4); tries += 1
        }
        if (!allowCrossLine && (((b + off / 8) & 15) + math.max(span.toLong, 4L) > 16)) SandboxBase + WinLo
        else b
      }
      val an = a()
      val (setup, ea) =
        if (r.nextBoolean()) (Vector(s"\tmove.l #${hex(base)},$an"), s"($an)")
        else {
          val d16 = 4 * (r.nextInt(5) - 2)
          (Vector(s"\tmove.l #${hex(base - d16)},$an"), s"$d16($an)")
        }
      val spec = s"{#$off:#$wid}"
      pick(Seq("bftst", "bfchg", "bfclr", "bfset", "bfextu", "bfexts", "bfffo", "bfins")) match {
        case op @ ("bfextu" | "bfexts" | "bfffo") => setup :+ s"\t$op $ea$spec,${d()}"
        case "bfins"                              => setup :+ s"\tbfins ${d()},$ea$spec"
        case op                                   => setup :+ s"\t$op $ea$spec"
      }
    }

    private def tExtSwapTas(): Vector[String] =
      Vector(pick(Seq(s"\text.w ${d()}", s"\text.l ${d()}", s"\textb.l ${d()}",
                      s"\tswap ${d()}", s"\ttas ${d()}")))

    private def tAddxSubxReg(): Vector[String] =
      Vector(s"\t${pick(Seq("addx", "subx"))}.${size()} ${d()},${d()}")

    private def tAddxSubxMem(): Vector[String] = {
      val sz = size(); val span = spanOf(sz)
      val a1 = sbAddr(span, spanOf(sz)); val a2 = sbAddr(span, spanOf(sz))
      val ay = a(); val ax0 = a()
      val ax = if (ax0 == ay) s"%a${(ay.drop(2).toInt + 1) % 7}" else ax0
      Vector(s"\tmove.l #${hex(a1 + span)},$ay",
             s"\tmove.l #${hex(a2 + span)},$ax",
             s"\t${pick(Seq("addx", "subx"))}.$sz -($ay),-($ax)")
    }

    private def tBcdReg(): Vector[String] =
      Vector(s"\t${pick(Seq("abcd", "sbcd"))} ${d()},${d()}")

    private def tBcdMem(): Vector[String] = {
      val a1 = sbAddr(1); val a2 = sbAddr(1)
      val ay = a(); val ax0 = a()
      val ax = if (ax0 == ay) s"%a${(ay.drop(2).toInt + 1) % 7}" else ax0
      Vector(s"\tmove.l #${hex(a1 + 1)},$ay",
             s"\tmove.l #${hex(a2 + 1)},$ax",
             s"\t${pick(Seq("abcd", "sbcd"))} -($ay),-($ax)")
    }

    private def tPackUnpk(): Vector[String] =
      Vector(s"\t${pick(Seq("pack", "unpk"))} ${d()},${d()},#${hex(rand16())}")

    private def tExg(): Vector[String] =
      Vector(r.nextInt(3) match {
        case 0 => s"\texg ${d()},${d()}"
        case 1 => s"\texg ${a()},${a()}"
        case _ => s"\texg ${d()},${a()}"
      })

    private def tCcrOps(): Vector[String] =
      Vector(r.nextInt(5) match {
        case 0 => s"\tandi #${hex(r.nextInt(32).toLong)},%ccr"
        case 1 => s"\tori #${hex(r.nextInt(32).toLong)},%ccr"
        case 2 => s"\teori #${hex(r.nextInt(32).toLong)},%ccr"
        case 3 => s"\tmove #${hex(r.nextInt(32).toLong)},%ccr"
        case _ => s"\tmove %ccr,${d()}"
      })

    private def tMoveCcrDn(): Vector[String] = {
      val dr = d()
      Vector(s"\tmoveq #${r.nextInt(32)},$dr", s"\tmove $dr,%ccr")
    }

    private def tMoveFromSr(): Vector[String] =
      if (r.nextBoolean()) Vector(s"\tmove %sr,${d()}")
      else {
        val (setup, ea, _) = memEaSimple('w')
        setup :+ s"\tmove %sr,$ea"
      }

    private def tMoveCcrStore(): Vector[String] = {
      val (setup, ea, _) = memEaSimple('w')
      setup :+ s"\tmove %ccr,$ea"
    }

    private def tMul(): Vector[String] = {
      r.nextInt(3) match {
        case 0 =>
          val op = pick(Seq("mulu", "muls"))
          if (r.nextBoolean()) Vector(s"\t$op.w ${d()},${d()}")
          else Vector(s"\t$op.w ${imm16()},${d()}")
        case 1 =>
          Vector(s"\t${pick(Seq("mulu", "muls"))}.l ${d()},${d()}")
        case _ =>
          val dh = dn(); val dl = (dh + 1 + r.nextInt(7)) % 8
          Vector(s"\t${pick(Seq("mulu", "muls"))}.l ${d()},%d$dh:%d$dl")
      }
    }

    private def tDiv(): Vector[String] = {
      val dv = d()
      val nz = { val v = rand32(); if ((v & 0xffffL) == 0) v | 3 else v }
      r.nextInt(3) match {
        case 0 =>
          Vector(s"\tmove.l #${hex(nz)},$dv",
                 s"\t${pick(Seq("divu", "divs"))}.w $dv,${d()}")
        case 1 =>
          Vector(s"\tmove.l #${hex(if (nz == 0) 3 else nz)},$dv",
                 s"\t${pick(Seq("divu", "divs"))}.l $dv,${d()}")
        case _ =>
          val dr0 = dn(); val dq = (dr0 + 1 + r.nextInt(7)) % 8
          val op  = pick(Seq("divu", "divs"))
          if (r.nextBoolean())
            Vector(s"\tmove.l #${hex(if (nz == 0) 3 else nz)},$dv",
                   s"\t$op.l $dv,%d$dr0:%d$dq")                          // 64/32 (Dr:Dq)
          else
            Vector(s"\tmove.l #${hex(if (nz == 0) 3 else nz)},$dv",
                   s"\t${op}ll $dv,%d$dr0,%d$dq")                        // 32/32 rem:quot (divull/divsll)
      }
    }

    private def tCmp2Chk2(): Vector[String] = {
      val sz   = size()
      val span = spanOf(sz)
      val addr = sbAddr(2 * span, span)
      val an   = a()
      val posMax = if (sz == 'b') 0x7fL else if (sz == 'w') 0x7fffL else 0x7fffffffL
      val (lo, hi) = { val x = rand32() & posMax
                       val l = x / 2
                       (l, math.min(l + 1 + r.nextInt(64), posMax)) }    // hi stays positive (signed lo<=hi)
      val rn = if (r.nextBoolean()) d() else a()
      val chk2 = r.nextInt(4) == 0
      val rv = if (chk2) lo + (hi - lo) / 2 else rand32() & 0xffffL  // CHK2 in-bounds by construction
      val (st, mv) = sz match {
        case 'b' => ("b", 1L); case 'w' => ("w", 2L); case _ => ("l", 4L)
      }
      val t = d()
      Vector(s"\tmove.l #${hex(lo)},$t",
             s"\tmove.$st $t,(${hex(addr)}).l",
             s"\tmove.l #${hex(hi)},$t",
             s"\tmove.$st $t,(${hex(addr + mv)}).l",
             s"\tmove.l #${hex(rv)},$rn",
             s"\tmove.l #${hex(addr)},$an",
             s"\t${if (chk2) "chk2" else "cmp2"}.$sz ($an),$rn")
    }

    private def tCas(): Vector[String] = {
      val sz   = size()
      val span = spanOf(sz)
      val addr = sbAddr(span + 4, span)
      val an   = a()
      val memV = rand32()
      val dc0  = dn(); var du0 = dn(); var t0 = dn()
      while (du0 == dc0) du0 = (du0 + 1) % 8
      while (t0 == dc0 || t0 == du0) t0 = (t0 + 1) % 8
      val (dc, du, t) = (s"%d$dc0", s"%d$du0", s"%d$t0")
      val matchIt = r.nextBoolean()
      val mask = sz match { case 'b' => 0xffL; case 'w' => 0xffffL; case _ => 0xffffffffL }
      val dcV  = if (matchIt) memV & mask else rand32()
      val ea = r.nextInt(3) match {
        case 0 => s"($an)"
        case 1 => s"($an)+"
        case _ => s"-($an)"
      }
      val anInit = if (ea == s"-($an)") addr + span else addr
      Vector(s"\tmove.l #${hex(memV)},$t",
             s"\tmove.$sz $t,(${hex(addr)}).l",
             s"\tmove.l #${hex(dcV)},$dc",
             s"\tmove.l #${hex(rand32())},$du",
             s"\tmove.l #${hex(anInit)},$an",
             s"\tcas.$sz $dc,$du,$ea")
    }

    private def tCas2(): Vector[String] = {
      val sz   = sizeWL()
      val span = spanOf(sz)
      val a1v  = sbAddr(span, span); val a2v = sbAddr(span, span)
      val an1 = "%a0"; val an2 = "%a1"
      val m1 = rand32(); val m2 = rand32()
      val mask = if (sz == 'w') 0xffffL else 0xffffffffL
      val match1 = r.nextBoolean(); val match2 = r.nextBoolean()
      val c1 = if (match1) m1 & mask else rand32()
      val c2 = if (match2 && a1v != a2v) m2 & mask else rand32()
      Vector(s"\tmove.l #${hex(m1)},%d0", s"\tmove.$sz %d0,(${hex(a1v)}).l",
             s"\tmove.l #${hex(m2)},%d1", s"\tmove.$sz %d1,(${hex(a2v)}).l",
             s"\tmove.l #${hex(c1)},%d2", s"\tmove.l #${hex(c2)},%d3",
             s"\tmove.l #${hex(rand32())},%d4", s"\tmove.l #${hex(rand32())},%d5",
             s"\tmove.l #${hex(a1v)},$an1", s"\tmove.l #${hex(a2v)},$an2",
             s"\tcas2.$sz %d2:%d3,%d4:%d5,($an1):($an2)")
    }

    private def tMovem(): Vector[String] = {
      val sz   = sizeWL()
      val span = spanOf(sz)
      // reg mask: 1..4 regs among d0-d7/a0-a6
      def mask(): Seq[String] = {
        val n = 1 + r.nextInt(4)
        val all = (0 to 7).map(i => s"%d$i") ++ (0 to 6).map(i => s"%a$i")
        r.shuffle(all).take(n).sortBy(x => (x(1), x(2)))
      }
      val regs = mask()
      r.nextInt(3) match {
        case 0 => // (d16,An) store then load back (possibly different regs)
          val addr = sbAddr(16, span)              // 16-byte window covers any 1..4-reg burst
          val an = a()
          val d16 = 4 * r.nextInt(3)
          val regs2 = mask()
          Vector(s"\tmove.l #${hex(addr - d16)},$an",
                 s"\tmovem.$sz ${regs.mkString("/")},$d16($an)",
                 s"\tmovem.$sz $d16($an),${regs2.mkString("/")}")
        case 1 => // (An)+ load from the seeded window
          val addr = sbAddr(16, span)
          val an = a()
          Vector(s"\tmove.l #${hex(addr)},$an",
                 s"\tmovem.$sz ($an)+,${regs.mkString("/")}")
        case _ => // balanced push/pop on the real stack
          Vector(s"\tmovem.$sz ${regs.mkString("/")},-(%sp)",
                 s"\tmovem.$sz (%sp)+,${regs.mkString("/")}")
      }
    }

    private def tMovep(): Vector[String] = {
      val sz   = sizeWL()
      val span = if (sz == 'w') 3 else 7
      val addr = sbAddr(span)
      val an = a(); val dr = d()
      val d16 = r.nextInt(9) - 4
      val setup = Vector(s"\tmove.l #${hex(addr - d16)},$an")
      if (r.nextBoolean()) setup :+ s"\tmovep.$sz $dr,$d16($an)"
      else                 setup :+ s"\tmovep.$sz $d16($an),$dr"
    }

    private def tLea(): Vector[String] = {
      val an = a()
      r.nextInt(4) match {
        case 0 =>
          val base = a()
          Vector(s"\tmove.l #${hex(rand32())},$base", s"\tlea ($base),$an")
        case 1 =>
          val base = a()
          Vector(s"\tmove.l #${hex(rand32())},$base", s"\tlea (${r.nextInt(65) - 32},$base),$an")
        case 2 =>
          val base = a(); val xr = d()
          Vector(s"\tmove.l #${hex(rand32())},$base",
                 s"\tmoveq #${r.nextInt(16)},$xr",
                 s"\tlea (${r.nextInt(17) - 8},$base,$xr.w*${pick(Seq(1, 2, 4, 8))}),$an")
        case _ =>
          Vector(s"\tlea (${hex(rand16())}).l,$an")
      }
    }

    private def tPea(): Vector[String] = {
      val base = a(); val popTo = d()
      Vector(s"\tmove.l #${hex(rand32())},$base",
             s"\tpea (${r.nextInt(33) - 16},$base)",
             s"\tmove.l (%sp)+,$popTo")           // balance + verify the pushed EA
    }

    private def tLinkUnlk(): Vector[String] = {
      val an = a()
      val disp = if (r.nextInt(4) == 0) 2 * r.nextInt(9) else -2 * (1 + r.nextInt(16))
      Vector(s"\tlink $an,#$disp", s"\tunlk $an")
    }

    private def tScc(): Vector[String] =
      Vector(s"\ts${pick(ccList :+ "t" :+ "f")} ${d()}")

    private def tBccForward(): Vector[String] = {
      val skip = lbl("skip")
      val cc   = pick(ccList)
      val fill = Vector.fill(1 + r.nextInt(2))(s"\tmoveq #${r.nextInt(256) - 128},${d()}")
      Vector(s"\tcmp.l ${imm32()},${d()}", s"\tb$cc.s $skip") ++ fill :+ s"$skip:"
    }

    private def tBraForward(): Vector[String] = {
      val skip = lbl("skip")
      val fill = Vector.fill(1 + r.nextInt(2))(s"\tmoveq #${r.nextInt(256) - 128},${d()}")
      Vector(s"\tbra.s $skip") ++ fill :+ s"$skip:"
    }

    private def tJmpForward(): Vector[String] = {
      val skip = lbl("skip")
      val fill = Vector(s"\tmoveq #${r.nextInt(256) - 128},${d()}")
      if (r.nextBoolean()) {
        val an = a()
        Vector(s"\tmove.l #$skip,$an", s"\tjmp ($an)") ++ fill :+ s"$skip:"
      } else Vector(s"\tjmp $skip") ++ fill :+ s"$skip:"
    }

    private def tDbcc(): Vector[String] = {
      val loop = lbl("loop")
      val cnt0 = dn(); val acc0 = (cnt0 + 1 + r.nextInt(7)) % 8         // acc != cnt (else +1/-1 never terminates)
      val cnt  = s"%d$cnt0"; val acc = s"%d$acc0"
      val cc   = pick(Seq("f", "f", "f", "eq", "ne", "cc", "cs", "mi", "pl")) // mostly dbf; cond exits early at most
      Vector(s"\tmove.w #${r.nextInt(5)},$cnt",
             s"$loop:",
             s"\taddq.l #1,$acc",
             s"\tdb$cc $cnt,$loop")
    }

    private def tBsrRts(): Vector[String] = {
      val sub = lbl("sub"); val end = lbl("end")
      Vector(s"\tbsr $sub",
             s"\tbra.s $end",
             s"$sub:",
             s"\tmoveq #${r.nextInt(256) - 128},${d()}",
             s"\trts",
             s"$end:")
    }

    private def tJsrRts(): Vector[String] = {
      val sub = lbl("sub"); val end = lbl("end")
      if (r.nextBoolean()) {
        val an = a()
        Vector(s"\tmove.l #$sub,$an",
               s"\tjsr ($an)",
               s"\tbra.s $end",
               s"$sub:", s"\taddq.l #1,${d()}", s"\trts", s"$end:")
      } else
        Vector(s"\tjsr $sub",
               s"\tbra.s $end",
               s"$sub:", s"\taddq.l #1,${d()}", s"\trts", s"$end:")
    }

    private def tBsrRtd(): Vector[String] = {
      val sub = lbl("sub"); val end = lbl("end")
      Vector(s"\tmove.l ${imm32()},-(%sp)",
             s"\tbsr $sub",
             s"\tbra.s $end",
             s"$sub:", s"\trtd #4", s"$end:")
    }

    private def tRtr(): Vector[String] = {
      // hand-built frame in the sandbox: [CCR word][return PC], A7 repointed at
      // it, restored after. Randomizes the full CCR incl X via the popped word.
      val ret = lbl("ret")
      val frame = sbAddr(8, 4)
      val save = a(); val t = d(); val c = d()
      Vector(s"\tmove.l %sp,$save",
             s"\tmove.l #$ret,$t",
             s"\tmove.l $t,(${hex(frame + 2)}).l",
             s"\tmoveq #${r.nextInt(32)},$c",
             s"\tmove.w $c,(${hex(frame)}).l",
             s"\tmove.l #${hex(frame)},%sp",
             s"\trtr",
             s"$ret:",
             s"\tmove.l $save,%sp")
    }

    private def tMoveUsp(): Vector[String] = {
      val src = a(); val dst = a()
      Vector(s"\tmove.l #${hex(rand32())},$src",
             s"\tmove.l $src,%usp",
             s"\tmove.l %usp,$dst")
    }

    private def tMovec(): Vector[String] = {
      val rc = pick(Seq("%sfc", "%dfc"))
      val src = d(); val dst = d()
      Vector(s"\tmoveq #${r.nextInt(8)},$src",
             s"\tmovec $src,$rc",
             s"\tmovec $rc,$dst")
    }

    private def tMoves(): Vector[String] = {
      val sz = size()
      // memory-alterable EAs, per the MOVES directed coverage
      val (setup, ea, _) = memEa(sz, wide = false)
      val rn = if (r.nextBoolean()) d() else a()
      if (r.nextBoolean()) setup :+ s"\tmoves.$sz $rn,$ea"
      else                 setup :+ s"\tmoves.$sz $ea,$rn"
    }

    private def tChk(): Vector[String] = {
      val h = lbl("chkh"); val e = lbl("chke")
      val t = d(); val bound = d(); val v = d()
      val sz = sizeWL()
      val bnd = 1 + r.nextInt(1000)
      val value = r.nextInt(3) match {
        case 0 => r.nextInt(bnd + 1).toLong                 // in-bounds
        case 1 => (bnd + 1 + r.nextInt(100)).toLong          // above -> trap
        case _ => (-(1 + r.nextInt(100))).toLong & 0xffffffffL // negative -> trap
      }
      Vector(s"\tmove.l #$h,$t",
             s"\tmove.l $t,0x18",                            // vector 6
             s"\tmove.l #${hex(bnd.toLong)},$bound",
             s"\tmove.l #${hex(value)},$v",
             s"\tchk.$sz $bound,$v",
             s"\tbra.s $e",
             s"$h:", s"\trte",
             s"$e:")
    }

    private def tTrap(): Vector[String] = {
      val h = lbl("traph"); val e = lbl("trape")
      val n = r.nextInt(16)
      val t = d()
      Vector(s"\tmove.l #$h,$t",
             s"\tmove.l $t,${hex(0x80L + 4 * n)}",           // vector 32+n
             s"\ttrap #$n",
             s"\tmoveq #${r.nextInt(128)},${d()}",
             s"\tbra.s $e",
             s"$h:", s"\trte",
             s"$e:")
    }

    private def tTrapv(): Vector[String] = {
      val h = lbl("tvh"); val e = lbl("tve")
      val t = d()
      Vector(s"\tmove.l #$h,$t",
             s"\tmove.l $t,0x1c",                            // vector 7
             s"\tmove #${hex(r.nextInt(32).toLong)},%ccr",   // random V
             s"\ttrapv",
             s"\tbra.s $e",
             s"$h:", s"\trte",
             s"$e:")
    }

    private def tTrapcc(): Vector[String] = {
      val h = lbl("tch"); val e = lbl("tce")
      val cc = pick(ccList :+ "f")
      val t = d()
      val form = r.nextInt(3) match {
        case 0 => s"\ttrap$cc"
        case 1 => s"\ttrap$cc.w ${imm16()}"
        case _ => s"\ttrap$cc.l ${imm32()}"
      }
      Vector(s"\tmove.l #$h,$t",
             s"\tmove.l $t,0x1c",                            // vector 7 (TRAPcc)
             s"\tmove #${hex(r.nextInt(32).toLong)},%ccr",
             form,
             s"\tbra.s $e",
             s"$h:", s"\trte",
             s"$e:")
    }

    // weighted template table
    private val templates: Vector[(Int, () => Vector[String])] = Vector(
      6 -> tMoveq _,   8 -> tAluReg _,   3 -> tEorReg _,    6 -> tAluImm _,
      3 -> tAluSrcImm _, 4 -> tAddqSubq _, 3 -> tAdda _,    2 -> tAdaMemSrc _,
      5 -> tMoveRegReg _, 5 -> tMoveImm _, 8 -> tMoveLoad _, 3 -> tMoveaLoad _,
      9 -> tMoveStore _, 2 -> tMoveMemMem _,
      5 -> tAluMemSrc _, 5 -> tAluMemRmw _, 3 -> tAluImmRmw _, 2 -> tQuickMemRmw _,
      4 -> tUnaryReg _, 3 -> tUnaryMem _,
      5 -> tShiftImm _, 4 -> tShiftReg _,
      3 -> tBitopReg _, 3 -> tBitopMem _,
      3 -> tBitfieldReg _, 3 -> tBitfieldMem _,
      3 -> tExtSwapTas _,
      3 -> tAddxSubxReg _, 2 -> tAddxSubxMem _,
      2 -> tBcdReg _, 1 -> tBcdMem _,
      2 -> tPackUnpk _, 2 -> tExg _,
      4 -> tCcrOps _, 2 -> tMoveCcrDn _, 1 -> tMoveFromSr _, 1 -> tMoveCcrStore _,
      3 -> tMul _, 3 -> tDiv _,
      2 -> tCmp2Chk2 _, 2 -> tCas _, 1 -> tCas2 _,
      3 -> tMovem _, 2 -> tMovep _,
      3 -> tLea _, 2 -> tPea _, 2 -> tLinkUnlk _,
      3 -> tScc _,
      5 -> tBccForward _, 2 -> tBraForward _, 1 -> tJmpForward _,
      3 -> tDbcc _,
      3 -> tBsrRts _, 2 -> tJsrRts _, 1 -> tBsrRtd _, 1 -> tRtr _,
      1 -> tMoveUsp _, 1 -> tMovec _, 2 -> tMoves _,
      1 -> tChk _, 1 -> tTrap _, 1 -> tTrapv _, 1 -> tTrapcc _
    )
    private val totalWeight = templates.map(_._1).sum

    def nextBlock(): Block = {
      var w = r.nextInt(totalWeight)
      var i = 0
      while (w >= templates(i)._1) { w -= templates(i)._1; i += 1 }
      val body = templates(i)._2()
      // 0/1 two-byte filler prefix: shifts the template across BOTH fetch-group
      // slots (slot-1 is where the decode-race class lives). NOT `nop`: NOP
      // (0x4E71) currently decodes ILLEGAL in the DUT (fuzz finding — the
      // directed suite never EXECUTES one), so a moveq stands in.
      val lines = if (r.nextBoolean()) s"\tmoveq #${r.nextInt(256) - 128},${d()}" +: body else body
      Block(lines)
    }
  }
}
