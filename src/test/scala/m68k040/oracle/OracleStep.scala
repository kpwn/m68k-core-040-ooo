package m68k040.oracle

import scala.io.Source
import java.nio.file.Path

/** One retired-instruction record from the Musashi per-instruction trace.
  * Post-instruction architectural state (the lock-step compare granularity). */
final case class OracleStep(pc: Long, sr: Int, d: Vector[Long], a: Vector[Long],
                            msp: Long = -1L, isp: Long = -1L,
                            fp: Vector[BigInt] = Vector.empty,
                            fpcr: Long = 0L, fpsr: Long = 0L, fpiar: Long = 0L) {
  def ccr: Int = sr & 0x1f
}

object OracleStep {
  private def hex(s: String): Long = java.lang.Long.parseLong(s.stripPrefix("0x"), 16)
  // fpNl fields are FULL 64-bit unsigned values (e.g. Musashi's default NaN
  // significand ffffffffffffffff), which java.lang.Long.parseLong THROWS on.
  // Parse every FP field as an unsigned BigInt instead.
  private def hexBig(s: String): BigInt = BigInt(s.stripPrefix("0x"), 16)

  def parseTrace(path: Path): Either[OracleError, Vector[OracleStep]] = {
    val src = Source.fromFile(path.toFile)
    try {
      val steps = src.getLines().filter(_.startsWith("step ")).map(parseLine).toVector
      sequence(steps)
    } catch {
      case e: Exception => Left(OracleError(s"failed to parse trace ${path}: ${e.getMessage}"))
    } finally src.close()
  }

  private def parseLine(line: String): Either[OracleError, OracleStep] = {
    val kv = line.split("\\s+").flatMap { tok =>
      val i = tok.indexOf('=')
      if (i > 0) Some(tok.substring(0, i) -> tok.substring(i + 1)) else None
    }.toMap
    def get(k: String): Either[OracleError, Long] =
      kv.get(k).map(hex).toRight(OracleError(s"trace line missing $k: $line"))
    for {
      pc <- get("pc")
      sr <- get("sr")
      d  <- sequence((0 until 8).map(i => get(s"d$i")).toVector)
      a  <- sequence((0 until 8).map(i => get(s"a$i")).toVector)
    } yield {
      val fpRegs: Vector[BigInt] =
        if ((0 until 8).forall(i => kv.contains(s"fp${i}h") && kv.contains(s"fp${i}l")))
          (0 until 8).map { i =>
            (hexBig(kv(s"fp${i}h")) << 64) | hexBig(kv(s"fp${i}l"))
          }.toVector
        else Vector.empty
      OracleStep(pc, sr.toInt & 0xffff, d, a,
                 msp   = kv.get("msp").map(hex).getOrElse(-1L),
                 isp   = kv.get("isp").map(hex).getOrElse(-1L),
                 fp    = fpRegs,
                 fpcr  = kv.get("fpcr").map(hex).getOrElse(0L),
                 fpsr  = kv.get("fpsr").map(hex).getOrElse(0L),
                 fpiar = kv.get("fpiar").map(hex).getOrElse(0L))
    }
  }

  private def sequence[A](xs: Vector[Either[OracleError, A]]): Either[OracleError, Vector[A]] =
    xs.foldRight(Right(Vector.empty): Either[OracleError, Vector[A]]) { (e, acc) =>
      for { x <- e; rest <- acc } yield x +: rest
    }
}
