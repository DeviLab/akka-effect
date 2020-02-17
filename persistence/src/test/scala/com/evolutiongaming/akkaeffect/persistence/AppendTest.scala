package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

class AppendTest extends AsyncFunSuite with Matchers {

  test("adapter") {

    // TODO use everywhere
    implicit val toTry = ToTryFromToFuture.syncOrError[IO]

    case class Event(fa: IO[Unit])

    def eventsourced(act: Act, ref: Ref[IO, Queue[IO[Unit]]]): IO[Append.Eventsourced] = {
      Ref[IO]
        .of(0L)
        .map { seqNr =>
          new Append.Eventsourced {

            def lastSequenceNr = seqNr.get.toTry.get

            def persistAllAsync[A](events: List[A])(handler: A => Unit) = {
              val handlers = for {
                _ <- seqNr.update { _ + events.size }
                _ <- events.foldMapM { event => act.ask { handler(event) } }
              } yield {}
              ref
                .update { _.enqueue(handlers) }
                .toTry
                .get
            }
          }
        }
    }

    val error = new Throwable with NoStackTrace
    val stopped = new Throwable with NoStackTrace

    val result = for {
      ref          <- Ref[IO].of(Queue.empty[IO[Unit]])
      act          <- Act.of[IO]
      eventsourced <- eventsourced(act, ref)
      result       <- Append
        .adapter[IO, Int](act, eventsourced, stopped.pure[IO])
        .use { append =>

          def dequeue = {
            ref
              .modify { queue =>
                queue.dequeueOption match {
                  case Some((a, queue)) => (queue, a)
                  case None             => (Queue.empty, ().pure[IO])
                }
              }
              .flatten
          }

          for {
            seqNr0 <- append.value(Nel.of(Nel.of(0, 1), Nel.of(2)))
            seqNr1 <- append.value(Nel.of(Nel.of(3)))
            queue  <- ref.get
            _       = queue.size shouldEqual 3
            _      <- dequeue
            _      <- dequeue
            seqNr  <- seqNr0
            _       = seqNr shouldEqual 3L
            queue  <- ref.get
            _       = queue.size shouldEqual 1
            _      <- dequeue
            seqNr  <- seqNr1
            _       = seqNr shouldEqual 4L
            _      <- dequeue
            seqNr0 <- append.value(Nel.of(Nel.of(3)))
            seqNr1 <- append.value(Nel.of(Nel.of(4)))
            queue  <- ref.get
            _       = queue.size shouldEqual 2
            _      <- IO { append.onError(error, 2, 2L) }
            seqNr  <- seqNr0.attempt
            _       = seqNr shouldEqual error.asLeft
            seqNr  <- seqNr1.attempt
            _       = seqNr shouldEqual error.asLeft
            result <- append.value(Nel.of(Nel.of(4)))
          } yield result
        }
      result   <- result.attempt
      _         = result shouldEqual stopped.asLeft
    } yield {}

    result.run()
  }
}