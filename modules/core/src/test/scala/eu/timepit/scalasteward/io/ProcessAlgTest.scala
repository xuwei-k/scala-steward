package eu.timepit.scalasteward.io

import better.files.File
import cats.data.{NonEmptyList => Nel}
import cats.effect.IO
import org.scalatest.{FunSuite, Matchers}

class ProcessAlgTest extends FunSuite with Matchers {
  val processAlg: ProcessAlg[IO] =
    ProcessAlg.create[IO](Nil)

  test("exec echo") {
    processAlg
      .exec(Nel.of("echo", "hello"), File.currentWorkingDirectory)
      .unsafeRunSync() shouldBe List("hello")
  }

  test("exec false") {
    processAlg
      .exec(Nel.of("ls", "--foo"), File.currentWorkingDirectory)
      .attempt
      .map(_.isLeft)
      .unsafeRunSync()
  }
}
