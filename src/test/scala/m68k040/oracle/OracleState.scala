package m68k040.oracle

final case class MemoryWriteEvent(address: Long, value: Int) {
  def normalized: MemoryWriteEvent =
    MemoryWriteEvent(address & 0xffffffffL, value & 0xff)
}

final case class OracleState(
    pass: Boolean,
    sentinelHit: Boolean,
    sentinelValue: Long,
    sentinelSize: Int,
    cycles: Int,
    pc: Long,
    sr: Int,
    ccr: Int,
    dataRegs: Vector[Long],
    addressRegs: Vector[Long],
    memoryWrites: Map[Long, Int],
    memoryWriteTrace: Vector[MemoryWriteEvent],
    finalRam: Map[Long, Int]) {
  require(dataRegs.length == 8, "expected D0-D7")
  require(addressRegs.length == 8, "expected A0-A7")

  def d(n: Int): Long = dataRegs(n)
  def a(n: Int): Long = addressRegs(n)

  def normalizedWithoutSentinel(sentinel: Long): OracleState =
    copy(
      pc = pc & 0xffffffffL,
      sr = sr & 0xffff,
      ccr = ccr & 0x1f,
      dataRegs = dataRegs.map(_ & 0xffffffffL),
      addressRegs = addressRegs.map(_ & 0xffffffffL),
      memoryWrites = memoryWrites.collect {
        case (address, value) if !isSentinelByte(address, sentinel) =>
          (address & 0xffffffffL) -> (value & 0xff)
      },
      memoryWriteTrace = memoryWriteTrace.collect {
        case event if !isSentinelByte(event.address, sentinel) => event.normalized
      },
      finalRam = finalRam.collect {
        case (address, value) if !isSentinelByte(address, sentinel) =>
          (address & 0xffffffffL) -> (value & 0xff)
      })

  private def isSentinelByte(address: Long, sentinel: Long): Boolean = {
    val normalizedAddress = address & 0xffffffffL
    val normalizedSentinel = sentinel & 0xffffffffL
    normalizedAddress >= normalizedSentinel && normalizedAddress < ((normalizedSentinel + 4) & 0xffffffffL)
  }
}

final case class OracleError(reason: String)
