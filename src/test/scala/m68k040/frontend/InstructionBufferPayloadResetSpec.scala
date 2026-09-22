package m68k040.frontend

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class InstructionBufferPayloadResetSpec extends AnyFunSuite {
  class CheckedBuffer extends InstructionBuffer {
    headPtr.simPublic()
    entries.foreach { e => e.word.simPublic(); e.pred.flatten.foreach(_.simPublic()) }
  }
  case class Item(word: Int, simple: Boolean, length: Int, ambiguous: Boolean,
                  size: SpinalEnumElement[Size.type], control: Boolean)
  private val zero = Item(0, false, 0, false, Size.BYTE, false)

  for (seed <- Seq(1, 17)) {
    test(s"poisoned instruction-buffer payload preserves exact FIFO outputs seed=$seed", VerilatorTest) {
      M68kSim().compile(new CheckedBuffer).doSim { d =>
        SimTimeout(1000000)
        val cd = d.clockDomain
        val rng = new scala.util.Random(0x49425546L + seed)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var queue = Vector.empty[Item]
        var head = 0
        var cycles = 0; var resets = 0; var warmOwnedResets = 0
        var blocked = 0; var wraps = 0; var pushFlush = 0; var pushShift = 0
        val heads = scala.collection.mutable.Set.empty[Int]
        val counts = scala.collection.mutable.Set.empty[Int]
        val pushes = scala.collection.mutable.Set.empty[Int]
        val shifts = scala.collection.mutable.Set.empty[Int]
        def record(s: String): Unit = digest.update((s + "\n").getBytes("UTF-8"))
        def randomItem(): Item = Item(rng.nextInt(65536), rng.nextBoolean(), rng.nextInt(16),
          rng.nextBoolean(), Seq(Size.BYTE, Size.WORD, Size.LONG)(rng.nextInt(3)), rng.nextBoolean())
        def drive(push: Option[Vector[Item]], shift: Int, flush: Boolean): Unit = {
          d.io.push.valid #= push.nonEmpty; d.io.push.payload.n #= push.fold(0)(_.size)
          d.io.shift #= shift; d.io.flush #= flush
          for (i <- 0 until 4) {
            val e = push.flatMap(_.lift(i)).getOrElse(zero)
            d.io.push.payload.words(i) #= e.word
            val p = d.io.push.payload.preds(i)
            p.simple #= e.simple; p.lenWords #= e.length; p.ambiguousLine #= e.ambiguous
            p.size #= e.size; p.ctrlXfer #= e.control
          }
        }
        def poisonUnowned(): Unit = {
          val occupied = queue.indices.map(i => (head + i) % 20).toSet
          for (i <- 0 until 20 if !occupied(i)) {
            val e = d.entries(i)
            e.word #= (0xe000 | ((cycles & 31) << 5) | i)
            e.pred.simple #= true; e.pred.lenWords #= 15
            e.pred.ambiguousLine #= true; e.pred.size #= Size.LONG
            e.pred.ctrlXfer #= true
          }
        }
        def check(): Unit = {
          assert(d.count.toInt == queue.size && d.headPtr.toInt == head,
            s"IBuf ownership control mismatch cycle=$cycles")
          assert(d.io.cnt.toInt == queue.size && d.io.avail.toInt == math.min(queue.size, 10))
          assert(d.io.push.ready.toBoolean == (queue.size <= 16))
          heads += head; counts += queue.size
          val observed = (0 until 10).map { i =>
            val p = d.io.headPred(i)
            Item(d.io.head(i).toInt, p.simple.toBoolean, p.lenWords.toInt,
              p.ambiguousLine.toBoolean, p.size.toEnum, p.ctrlXfer.toBoolean)
          }
          val expected = (0 until 10).map(i => queue.lift(i).getOrElse(zero))
          assert(observed == expected,
            s"IBuf poisoned payload escaped: cycle=$cycles head=$head count=${queue.size} expected=$expected got=$observed")
          for ((expected, logical) <- queue.zipWithIndex) {
            val e = d.entries((head + logical) % 20)
            val got = Item(e.word.toInt, e.pred.simple.toBoolean, e.pred.lenWords.toInt,
              e.pred.ambiguousLine.toBoolean, e.pred.size.toEnum, e.pred.ctrlXfer.toBoolean)
            assert(got == expected, s"IBuf poisoned occupied row: cycle=$cycles logical=$logical")
          }
          record(s"$cycles:$head:${queue.size}:${d.io.push.ready.toBoolean}:${observed.mkString(":")}")
        }
        def resetAndPoison(): Unit = {
          if (queue.nonEmpty) warmOwnedResets += 1
          cd.waitFallingEdge(); drive(None, 0, false); cd.assertReset(); sleep(30)
          cd.waitFallingEdge(); cd.deassertReset(); sleep(1)
          queue = Vector.empty; head = 0; resets += 1
          poisonUnowned(); sleep(1); check()
        }
        def step(push: Option[Vector[Item]] = None, shift: Int = 0, flush: Boolean = false): Boolean = {
          require(shift <= queue.size && shift <= 10 && push.forall(_.size <= 4))
          cd.waitFallingEdge(); drive(push, shift, flush); sleep(1)
          val fire = push.nonEmpty && d.io.push.ready.toBoolean
          if (push.nonEmpty && !fire) blocked += 1
          if (fire) pushes += push.get.size
          shifts += shift
          if (fire && flush) pushFlush += 1
          if (fire && shift != 0) pushShift += 1
          if (!flush && head + shift >= 20) wraps += 1
          val next = if (flush) Vector.empty else queue.drop(shift) ++ (if (fire) push.get else Vector.empty)
          head = if (flush) 0 else (head + shift) % 20
          cd.waitRisingEdge(); sleep(1)
          queue = next; cycles += 1
          poisonUnowned(); sleep(1); check()
          fire
        }
        cd.forkStimulus(10); drive(None, 0, false); cd.waitSampling(10); resetAndPoison()
        // Exercise every occupancy, including the fixed four-word admission rule.
        for (n <- 0 to 20) {
          step(flush = true)
          for (i <- 0 until n / 4) step(Some(Vector.tabulate(4)(j => zero.copy(word = 0x1000 + 4 * i + j))))
          if (n % 4 != 0) step(Some(Vector.fill(n % 4)(randomItem())))
          assert(queue.size == n)
        }
        val held = Vector.fill(4)(randomItem())
        assert(!step(Some(held), shift = 1)) // 20 -> 19, no admission bypass.
        assert(!step(Some(held), shift = 3)) // 19 -> 16, still blocked on old count.
        assert(step(Some(held)))            // Same payload accepted now.
        step(shift = 10); step(shift = 10)
        assert(step(Some(Vector.empty)))    // Zero-length push is legal when ready.
        assert(step(Some(Vector.fill(4)(randomItem())), flush = true))
        step(Some(Vector.fill(4)(randomItem()))); resetAndPoison()

        var pending = Option.empty[Vector[Item]]
        for (i <- 0 until 30000) {
          if (i != 0 && i % 2048 == 0) { resetAndPoison(); pending = None }
          if (pending.isEmpty && rng.nextInt(4) != 0)
            pending = Some(Vector.fill(rng.nextInt(5))(randomItem()))
          val shift = rng.nextInt(math.min(queue.size, 10) + 1)
          val flush = rng.nextInt(151) == 0
          val accepted = step(pending, shift, flush)
          if (accepted || flush) pending = None
        }
        assert(heads.size == 20 && counts.size == 21 && pushes.size == 5 && shifts.size == 11)
        assert(blocked > 2 && wraps > 100 && pushFlush > 50 && pushShift > 100 && warmOwnedResets > 2)
        val hash = digest.digest().map(b => f"${b & 255}%02x").mkString
        println(s"IBUF_POISON_TRACE seed=$seed cycles=$cycles resets=$resets warm=$warmOwnedResets blocked=$blocked wraps=$wraps pushFlush=$pushFlush pushShift=$pushShift sha256=$hash")
      }
    }
  }
}
