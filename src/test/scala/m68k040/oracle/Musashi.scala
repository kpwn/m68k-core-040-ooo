package m68k040.oracle

import java.nio.file.{Files, Path, Paths}
import scala.io.Source

object Musashi {
  private val Sentinel = 0xffff0000L

  val runnerPath: Path = {
    val home = sys.props.getOrElse("user.home", "/root")
    val shared = Paths.get(home, ".cache", "m68k-core-040-ooo", "musashi_run")
    val inTree = Paths.get(System.getProperty("user.dir"), "tools", "musashi", "musashi_run")
    sys.env.get("MUSASHI_RUN").map(Paths.get(_)).getOrElse {
      if (Files.exists(shared)) shared else inTree
    }
  }

  def assembleAndRun(
      source: String,
      loadAddress: Long = ProgramAssembler.DefaultLoadAddress,
      initialSp: Long = 0x00100000L,
      stopPc: Option[Long] = None,
      irqLevel: Int = 0,
      irqAtPc: Option[Long] = None,
      irqEvents: Seq[(Long, Int)] = Seq.empty,
      interruptAckVector: Option[Int] = None,
      interruptAckSpurious: Boolean = false,
      maxCycles: Int = 50000): Either[OracleError, OracleState] = {
    require(irqLevel >= 0 && irqLevel <= 7, "IRQ level must be 0 through 7")
    require(irqEvents.forall { case (_, level) => level >= 0 && level <= 7 }, "IRQ event levels must be 0 through 7")
    require(interruptAckVector.forall(vector => vector >= 2 && vector <= 255), "Interrupt acknowledge vector must be 2 through 255")
    require(!(interruptAckVector.nonEmpty && interruptAckSpurious), "Specify either an interrupt acknowledge vector or spurious acknowledge")
    if (!Files.exists(runnerPath)) {
      return Left(OracleError(s"musashi_run not found at $runnerPath; build it with `make musashi`"))
    }

    ProgramAssembler.assemble(source, loadAddress).flatMap { image =>
      val bin = Files.createTempFile("m68k040-musashi-", ".bin")
      val out = sibling(bin, ".out")
      try {
        Files.write(bin, image.bytes.map(_.toByte).toArray)
        val cmd =
          Seq(
            runnerPath.toString,
            "--bin", bin.toString,
            "--out", out.toString,
            "--load-addr", f"0x${loadAddress & 0xffffffffL}%08x",
            "--initial-sp", f"0x${initialSp & 0xffffffffL}%08x",
            "--sentinel", f"0x${Sentinel & 0xffffffffL}%08x",
            "--irq-level", irqLevel.toString,
            "--max-cycles", maxCycles.toString) ++
            stopPc.toSeq.flatMap(pc => Seq("--stop-pc", f"0x${pc & 0xffffffffL}%08x")) ++
            irqAtPc.toSeq.flatMap(pc => Seq("--irq-at-pc", f"0x${pc & 0xffffffffL}%08x")) ++
            irqEvents.flatMap { case (pc, level) => Seq("--irq-event", f"0x${pc & 0xffffffffL}%08x:$level") } ++
            interruptAckVector.toSeq.flatMap(vector => Seq("--ack-vector", vector.toString)) ++
            (if (interruptAckSpurious) Seq("--ack-spurious") else Seq.empty)
        runCmd(cmd, out)
      } finally {
        deleteIfExists(out)
        deleteIfExists(bin)
      }
    }
  }

  def assembleAndTrace(
      source: String,
      loadAddress: Long = ProgramAssembler.DefaultLoadAddress,
      initialSp: Long = 0x00100000L,
      stopPc: Option[Long] = None,
      maxCycles: Int = 50000): Either[OracleError, Vector[OracleStep]] = {
    if (!Files.exists(runnerPath)) {
      return Left(OracleError(s"musashi_run not found at $runnerPath; build it with `make musashi`"))
    }

    ProgramAssembler.assemble(source, loadAddress).flatMap { image =>
      val bin   = Files.createTempFile("m68k040-musashi-", ".bin")
      val trace = sibling(bin, ".trace")
      try {
        Files.write(bin, image.bytes.map(_.toByte).toArray)
        val cmd =
          Seq(
            runnerPath.toString,
            "--bin", bin.toString,
            "--trace", trace.toString,
            "--load-addr", f"0x${loadAddress & 0xffffffffL}%08x",
            "--initial-sp", f"0x${initialSp & 0xffffffffL}%08x",
            "--sentinel", f"0x${Sentinel & 0xffffffffL}%08x",
            "--max-cycles", maxCycles.toString) ++
            stopPc.toSeq.flatMap(pc => Seq("--stop-pc", f"0x${pc & 0xffffffffL}%08x"))
        runTraceCmd(cmd, trace)
      } finally {
        deleteIfExists(trace)
        deleteIfExists(bin)
      }
    }
  }

  private def runTraceCmd(cmd: Seq[String], trace: Path): Either[OracleError, Vector[OracleStep]] = {
    try {
      val pb = new ProcessBuilder(cmd: _*)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      val p = pb.start()
      val stderrSrc = Source.fromInputStream(p.getErrorStream)
      val stderr =
        try stderrSrc.mkString
        finally stderrSrc.close()
      val exit = p.waitFor()
      // exit codes 0 and 1 are both acceptable (0 = pass, 1 = sentinel not magic value)
      if (exit > 1 && !Files.exists(trace)) {
        Left(OracleError(s"musashi_run failed before producing trace: ${if (stderr.nonEmpty) stderr.trim else s"exit code $exit"}"))
      } else {
        OracleStep.parseTrace(trace)
      }
    } catch {
      case e: java.io.IOException => Left(OracleError(s"musashi_run failed: ${e.getMessage}"))
    }
  }

  private def runCmd(cmd: Seq[String], out: Path): Either[OracleError, OracleState] = {
    try {
      val pb = new ProcessBuilder(cmd: _*)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      val p = pb.start()
      val stderrSrc = Source.fromInputStream(p.getErrorStream)
      val stderr =
        try stderrSrc.mkString
        finally stderrSrc.close()
      val exit = p.waitFor()
      if (exit != 0 && !Files.exists(out)) {
        Left(OracleError(s"musashi_run failed before producing output: ${if (stderr.nonEmpty) stderr.trim else s"exit code $exit"}"))
      } else {
        parseOutput(out)
      }
    } catch {
      case e: java.io.IOException => Left(OracleError(s"musashi_run failed: ${e.getMessage}"))
    }
  }

  private def parseOutput(out: Path): Either[OracleError, OracleState] = {
    val src = Source.fromFile(out.toFile)
    val lines =
      try src.getLines().toList
      finally src.close()

    val kv = lines.flatMap { line =>
      val idx = line.indexOf('=')
      if (idx > 0) Some(line.substring(0, idx) -> line.substring(idx + 1)) else None
    }.toMap
    val memLine = raw"mem\[0x([0-9a-fA-F]+)\]=0x([0-9a-fA-F]+)".r
    val writes = lines.collect {
      case memLine(address, value) => parseHex(address) -> (parseHex(value).toInt & 0xff)
    }.toMap
    val memEventLine = raw"mem_event\[0x([0-9a-fA-F]+)\]=0x([0-9a-fA-F]+)".r
    val writeTrace = lines.collect {
      case memEventLine(address, value) => parseHex(address) -> (parseHex(value).toInt & 0xff)
    }.toVector
    val ramLine = raw"ram\[0x([0-9a-fA-F]+)\]=0x([0-9a-fA-F]+)".r
    val finalRam = lines.collect {
      case ramLine(address, value) => parseHex(address) -> (parseHex(value).toInt & 0xff)
    }.toMap

    def req(key: String): Either[OracleError, String] =
      kv.get(key).toRight(OracleError(s"musashi output missing `$key`"))

    for {
      pass <- req("pass").map(_.trim == "1")
      sentinelHit <- req("sentinel_hit").map(_.trim == "1")
      sentinelValue <- req("sentinel_value").map(parseHex)
      sentinelSize <- req("sentinel_size").map(_.trim.toInt)
      cycles <- req("cycles").map(_.trim.toInt)
      pc <- req("pc").map(parseHex)
      sr <- req("sr").map(value => parseHex(value).toInt & 0xffff)
      ccr <- req("ccr").map(value => parseHex(value).toInt & 0x1f)
      d <- readRegs(kv, "d")
      a <- readRegs(kv, "a")
    } yield OracleState(
      pass,
      sentinelHit,
      sentinelValue,
      sentinelSize,
      cycles,
      pc,
      sr,
      ccr,
      d,
      a,
      writes,
      (if (writeTrace.nonEmpty) writeTrace else writes.toVector.sortBy(_._1))
        .map { case (address, value) => MemoryWriteEvent(address, value) },
      if (finalRam.nonEmpty) finalRam else writes)
  }

  private def readRegs(kv: Map[String, String], prefix: String): Either[OracleError, Vector[Long]] =
    (0 until 8).foldLeft(Right(Vector.empty): Either[OracleError, Vector[Long]]) { (acc, index) =>
      acc.flatMap { values =>
        kv.get(s"$prefix$index") match {
          case Some(value) => Right(values :+ parseHex(value))
          case None => Left(OracleError(s"musashi output missing `$prefix$index`"))
        }
      }
    }

  private def parseHex(value: String): Long = {
    val trimmed = value.trim
    val digits =
      if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) trimmed.substring(2)
      else trimmed
    java.lang.Long.parseUnsignedLong(digits, 16) & 0xffffffffL
  }

  private def sibling(path: Path, suffix: String): Path =
    path.resolveSibling(path.getFileName.toString.stripSuffix(".bin") + suffix)

  private def deleteIfExists(path: Path): Unit =
    try Files.deleteIfExists(path)
    catch { case _: java.io.IOException => () }
}
