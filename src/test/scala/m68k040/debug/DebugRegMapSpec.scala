package m68k040.debug

import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source

/** Guards the ONE source of truth. `tools/debug/debug_regmap.def` defines the frozen
  * dbg_axi offsets, the append-only capability bitmap, the well-known constants and the
  * socket port surface; `DebugRegMap.scala` is GENERATED from it by
  * `tools/debug/gen_debug_regmap.py`. This spec re-parses the definition file and proves
  * the generated Scala still matches, so a stale generated file cannot reach synthesis.
  *
  * It deliberately re-implements the (tiny) parser rather than shelling out to Python:
  * the check must run inside `make test-fast` on any machine, with no interpreter
  * assumption beyond the JVM. */
class DebugRegMapSpec extends AnyFunSuite {

  private val defPath = "tools/debug/debug_regmap.def"
  private val genPath = "src/main/scala/m68k040/debug/DebugRegMap.scala"

  private def num(tok: String): Int =
    if (tok.toLowerCase.startsWith("0x")) Integer.parseInt(tok.substring(2), 16)
    else tok.toInt

  /** (offsets, features as (name, bit, stage), consts) parsed from the .def file. */
  private lazy val parsed: (Map[String, Int], Seq[(String, Int, Int)], Map[String, BigInt],
                            Seq[(String, String, Int)]) = {
    val src = Source.fromFile(defPath, "UTF-8")
    try {
      val offsets = scala.collection.mutable.LinkedHashMap[String, Int]()
      val feats   = scala.collection.mutable.ArrayBuffer[(String, Int, Int)]()
      val consts  = scala.collection.mutable.LinkedHashMap[String, BigInt]()
      val ports   = scala.collection.mutable.ArrayBuffer[(String, String, Int)]()
      for (raw <- src.getLines()) {
        val line = raw.split("#", 2)(0).trim
        if (line.nonEmpty) {
          val t = line.split("\\s+").toVector
          t(0) match {
            case "REG"   => offsets(t(1)) = num(t(2))
            case "BLK"   =>
              val base = num(t(2)); val count = num(t(3)); val stride = num(t(4))
              for (i <- 0 until count) offsets(s"${t(1)}$i") = base + i * stride
            case "RANGE" => offsets(t(1)) = num(t(2))
            case "FEAT"  => feats += ((t(1), num(t(2)), num(t(3))))
            case "CONST" =>
              consts(t(1)) = if (t(2).toLowerCase.startsWith("0x")) BigInt(t(2).substring(2), 16)
                             else BigInt(t(2))
            case "PORT"  => ports += ((t(1), t(2), num(t(3))))
            case other   => fail(s"unknown record kind '$other' in $defPath: $line")
          }
        }
      }
      (offsets.toMap, feats.toSeq, consts.toMap, ports.toSeq)
    } finally src.close()
  }

  /** The standalone `val OFF_*: Int = 0x...` declarations, re-read straight from
    * `DebugRegMap.scala`'s own source text. These are the form real RTL/consumer code
    * actually references (not the `allOffsets` tuple), so they need their own drift
    * guard rather than trusting `allOffsets` to be a faithful mirror. */
  private lazy val standaloneOffsetVals: Map[String, Int] = {
    val src = Source.fromFile(genPath, "UTF-8")
    try {
      val re = """^\s*val (OFF_\w+): Int = (0x[0-9A-Fa-f]+)\s*$""".r
      val out = scala.collection.mutable.LinkedHashMap[String, Int]()
      for (raw <- src.getLines()) {
        raw match {
          case re(name, hex) => out(name) = Integer.parseUnsignedInt(hex.substring(2), 16)
          case _              =>
        }
      }
      out.toMap
    } finally src.close()
  }

  test("the definition file is present and non-trivial") {
    val (offsets, feats, consts, ports) = parsed
    assert(offsets.size >= 100, s"only ${offsets.size} offsets parsed from $defPath")
    assert(feats.size == 24, s"expected 24 feature bits, got ${feats.size}")
    assert(consts.size >= 7, s"only ${consts.size} constants")
    assert(ports.size == 22, s"expected 22 socket ports, got ${ports.size}")
  }

  test("generated DebugRegMap.allOffsets matches the definition file exactly") {
    val (offsets, _, _, _) = parsed
    val generated = DebugRegMap.allOffsets.toMap
    val missing = (offsets.keySet -- generated.keySet).toSeq.sorted
    val extra   = (generated.keySet -- offsets.keySet).toSeq.sorted
    assert(missing.isEmpty, s"DebugRegMap.scala is STALE -- missing: ${missing.mkString(", ")}")
    assert(extra.isEmpty,   s"DebugRegMap.scala is STALE -- extra: ${extra.mkString(", ")}")
    val differing = offsets.filter { case (n, o) => generated(n) != o }
      .map { case (n, o) => f"$n: def 0x$o%05X vs scala 0x${generated(n)}%05X" }.toSeq.sorted
    assert(differing.isEmpty, s"offset drift: ${differing.mkString("; ")}")
  }

  test("standalone OFF_* val declarations in DebugRegMap.scala match allOffsets exactly") {
    // Regression guard: allOffsets and the standalone `val OFF_*` declarations are two
    // independently-hand/generator-written views of the same offsets, and only allOffsets
    // was previously cross-checked against the .def file. Real consumer code (RTL-facing
    // Scala, Task 6+) references the standalone vals, not the tuple -- so a drift here is
    // exactly the class of bug the other checks in this spec cannot see.
    val generated = DebugRegMap.allOffsets.toMap
    val standalone = standaloneOffsetVals
    assert(standalone.size >= 100, s"only ${standalone.size} standalone OFF_* vals parsed " +
      s"from $genPath -- the regex may no longer match the file's style")
    val missing = (generated.keySet -- standalone.keySet).toSeq.sorted
    val extra   = (standalone.keySet -- generated.keySet).toSeq.sorted
    assert(missing.isEmpty,
      s"DebugRegMap.scala is missing standalone val(s) present in allOffsets: ${missing.mkString(", ")}")
    assert(extra.isEmpty,
      s"DebugRegMap.scala has standalone val(s) absent from allOffsets: ${extra.mkString(", ")}")
    val differing = generated.filter { case (n, o) => standalone(n) != o }
      .map { case (n, o) => f"$n: allOffsets 0x$o%05X vs standalone val 0x${standalone(n)}%05X" }
      .toSeq.sorted
    assert(differing.isEmpty,
      s"DebugRegMap.scala's standalone OFF_* val(s) disagree with its own allOffsets: ${differing.mkString("; ")}")
  }

  test("generated DebugRegMap.features matches the definition file exactly") {
    val (_, feats, _, _) = parsed
    assert(DebugRegMap.features.sortBy(_._2) == feats.sortBy(_._2),
      s"DebugRegMap.scala is STALE -- def=${feats.sortBy(_._2)} scala=${DebugRegMap.features.sortBy(_._2)}")
  }

  test("generated DebugRegMap.ports matches the definition file exactly") {
    val (_, _, _, ports) = parsed
    assert(DebugRegMap.ports == ports,
      s"DebugRegMap.scala is STALE -- def=$ports scala=${DebugRegMap.ports}")
  }

  test("generated constants match the definition file") {
    val (_, _, consts, _) = parsed
    assert(BigInt(DebugRegMap.DBG_AW)             == consts("DBG_AW"))
    assert(BigInt(DebugRegMap.DBG_DW)             == consts("DBG_DW"))
    assert(DebugRegMap.VERSION_VALUE              == consts("VERSION_VALUE"))
    assert(BigInt(DebugRegMap.RAM_WINDOW_LG2_POR) == consts("RAM_WINDOW_LG2_POR"))
    assert(BigInt(DebugRegMap.RAM_WINDOW_LG2_MIN) == consts("RAM_WINDOW_LG2_MIN"))
    assert(BigInt(DebugRegMap.RAM_WINDOW_LG2_MAX) == consts("RAM_WINDOW_LG2_MAX"))
    assert(BigInt(DebugRegMap.MON_SENSE_POR)      == consts("MON_SENSE_POR"))
    assert(BigInt(DebugRegMap.POR_CYCLES_DEFAULT) == consts("POR_CYCLES_DEFAULT"))
  }

  test("the frozen offsets that Stage 1 decodes are at their deployed addresses") {
    // Spot-checks against macqd700-soc/cpu/rtl/core/debug/debug_ctrl.v:438-509 so a
    // silent renumbering of the block Stage 1 actually implements cannot pass.
    assert(DebugRegMap.OFF_VERSION        == 0x00000)
    assert(DebugRegMap.OFF_BUILD_ID       == 0x00004)
    assert(DebugRegMap.OFF_CONTROL        == 0x00008)
    assert(DebugRegMap.OFF_STATUS         == 0x0000C)
    assert(DebugRegMap.OFF_RAM_WINDOW_LG2 == 0x00058)
    assert(DebugRegMap.OFF_MON_SENSE      == 0x0005C)
    assert(DebugRegMap.OFF_FEATURES       == 0x000A0)
    assert(DebugRegMap.OFF_DBG_RESET_CTL  == 0x000A4)
    assert(DebugRegMap.OFF_CAP_TRACE      == 0x000A8)
  }

  test("featuresForStage is honest: Stage 1 advertises exactly 0x0004000F") {
    assert(DebugRegMap.featuresForStage(0) == BigInt(0))
    assert(DebugRegMap.featuresForStage(1) == BigInt(0x0004000FL),
      f"got 0x${DebugRegMap.featuresForStage(1)}%08X")
    // Monotonic: a later stage never retracts an earlier stage's bit.
    for (s <- 1 until 8) {
      val lower = DebugRegMap.featuresForStage(s - 1)
      val upper = DebugRegMap.featuresForStage(s)
      assert((lower & upper) == lower, s"stage $s retracts a bit advertised by stage ${s - 1}")
    }
    // Every bit advertised at stage 1 has a FEAT record whose stage really is <= 1.
    val advertised = DebugRegMap.featuresForStage(1)
    for ((name, bit, stage) <- DebugRegMap.features if ((advertised >> bit) & 1) == 1)
      assert(stage <= 1, s"feature '$name' (bit $bit) is advertised at stage 1 but is stage $stage")
  }

  test("offsets are 32-bit aligned, unique, and inside the 20-bit window") {
    val all = DebugRegMap.allOffsets
    assert(all.forall(_._2 % 4 == 0), "an offset is not 32-bit aligned")
    assert(all.forall(o => o._2 >= 0 && o._2 < (1 << DebugRegMap.DBG_AW)),
      "an offset escapes the 20-bit dbg_axi window")
    val byOffset = all.groupBy(_._2).filter(_._2.size > 1)
    assert(byOffset.isEmpty, s"duplicate offsets: ${byOffset.map { case (o, ns) =>
      f"0x$o%05X -> ${ns.map(_._1).mkString("/")}" }.mkString(", ")}")
  }
}
