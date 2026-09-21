package m68k040.socket

import m68k040.top.SocketIpcProfile
import org.scalatest.funsuite.AnyFunSuite

class SocketIpcProfileSpec extends AnyFunSuite {
  test("socket IPC profile is explicit and rejects misspelled build settings") {
    assert(!SocketIpcProfile.enabled("baseline"))
    assert(SocketIpcProfile.enabled("throughput-v1"))
    for(bad <- Seq("", "1", "throughput", "THROUGHPUT-V1", "throughput-v2"))
      intercept[IllegalArgumentException](SocketIpcProfile.enabled(bad))
  }
}
