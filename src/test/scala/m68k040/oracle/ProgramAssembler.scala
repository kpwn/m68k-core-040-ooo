package m68k040.oracle

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.io.Source

object ProgramAssembler {
  val DefaultLoadAddress = 0x40800000L

  final case class Image(bytes: Vector[Int], loadAddress: Long)

  def assemble(source: String, loadAddress: Long = DefaultLoadAddress): Either[OracleError, Image] = {
    val s = Files.createTempFile("m68k040-program-", ".s")
    val obj = sibling(s, ".o")
    val elf = sibling(s, ".elf")
    val bin = sibling(s, ".bin")
    try {
      Files.write(s, source.getBytes(StandardCharsets.UTF_8))

      for {
        _ <- runCmd(Seq("m68k-linux-gnu-as", "-m68040", "-o", obj.toString, s.toString), "assembler")
        _ <- runCmd(Seq("m68k-linux-gnu-ld", "-Ttext", f"0x$loadAddress%08x", "-o", elf.toString, obj.toString), "linker")
        _ <- runCmd(Seq("m68k-linux-gnu-objcopy", "-O", "binary", elf.toString, bin.toString), "objcopy")
      } yield Image(Files.readAllBytes(bin).toVector.map(_ & 0xff), loadAddress & 0xffffffffL)
    } finally {
      deleteIfExists(bin)
      deleteIfExists(elf)
      deleteIfExists(obj)
      deleteIfExists(s)
    }
  }

  private def sibling(path: Path, suffix: String): Path =
    path.resolveSibling(path.getFileName.toString.stripSuffix(".s") + suffix)

  private def deleteIfExists(path: Path): Unit =
    try Files.deleteIfExists(path)
    catch { case _: java.io.IOException => () }

  private def runCmd(cmd: Seq[String], label: String): Either[OracleError, Unit] = {
    try {
      val pb = new ProcessBuilder(cmd: _*)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      val p = pb.start()
      val stderrSrc = Source.fromInputStream(p.getErrorStream)
      val stderr =
        try stderrSrc.mkString
        finally stderrSrc.close()
      val exit = p.waitFor()
      if (exit == 0) Right(())
      else Left(OracleError(s"$label failed: ${if (stderr.nonEmpty) stderr.trim else s"exit code $exit"}"))
    } catch {
      case e: java.io.IOException => Left(OracleError(s"$label failed: ${e.getMessage}"))
    }
  }
}
