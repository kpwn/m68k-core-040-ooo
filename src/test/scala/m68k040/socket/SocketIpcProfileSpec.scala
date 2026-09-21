package m68k040.socket

import m68k040.top.SocketIpcProfile
import org.scalatest.funsuite.AnyFunSuite

class SocketIpcProfileSpec extends AnyFunSuite {
  test("socket IPC profile is explicit and rejects misspelled build settings") {
    assert(!SocketIpcProfile.enabled("baseline"))
    assert(SocketIpcProfile.enabled("throughput-v1"))
    assert(SocketIpcProfile.enabled("throughput-v2"))
    assert(!SocketIpcProfile.lateStore("baseline"))
    assert(!SocketIpcProfile.lateStore("throughput-v1"))
    assert(SocketIpcProfile.lateStore("throughput-v2"))
    for(bad <- Seq("", "1", "throughput", "THROUGHPUT-V1", "throughput-v3")) {
      intercept[IllegalArgumentException](SocketIpcProfile.enabled(bad))
      intercept[IllegalArgumentException](SocketIpcProfile.lateStore(bad))
    }
  }
}
