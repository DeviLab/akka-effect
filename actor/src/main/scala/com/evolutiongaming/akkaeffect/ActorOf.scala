package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receiveOf: ReceiveOf[F, Any, Any]
  ): Actor = {

    case class State(receive: Receive[F, Any, Any], release: F[Unit])

    def onPreStart(self: ActorRef, ctx: ActorCtx[F, Any, Any]): F[Option[State]] = {
      receiveOf(ctx)
        .allocated
        .attempt
        .flatMap {
          case Right((Some(receive), release)) =>
            State(receive, release).some.pure[F]

          case Right((None, release)) =>
            release
              .handleError { _ => () }
              .as(none[State])

          case Left(error) =>
            ActorError(s"$self.preStart failed to allocate receive with $error", error).raiseError[F, Option[State]]
        }
    }

    def onReceive(a: Any, state: Future[State], self: ActorRef, sender: ActorRef): F[Option[State]] = {
      FromFuture[F]
        .apply { state }
        .flatMap { state =>
          val reply = Reply.fromActorRef[F](to = sender, from = self.some)
          state.receive(a, reply)
            .attempt
            .flatMap {
              case Right(false) =>
                state
                  .some
                  .pure[F]

              case Right(true) =>
                state.release
                  .handleError { _ => () }
                  .as(none[State])

              case Left(error) =>
                state.release
                  .handleError { _ => () }
                  .productR {
                    ActorError(s"$self.receive failed on $a from $sender with $error", error)
                      .raiseError[F, Option[State]]
                  }
            }
        }
    }

    new Actor {

      import context.dispatcher

      val adapter = InReceive.Adapter(self)

      var stateVar = none[Future[State]]

      override def preStart(): Unit = {
        super.preStart()
        val ctx = ActorCtx[F](adapter.inReceive, context)
        val future = onPreStart(self, ctx).toFuture
        syncOrAsync(future)
      }

      def receive: Receive = adapter.receive orElse receiveAny

      override def postStop(): Unit = {
        stateVar.foreach { state =>
          FromFuture[F]
            .apply { state }
            .flatMap { _.release }
            .toFuture

          stateVar = none
        }

        super.postStop()
      }


      def receiveAny: Receive = {
        case a => stateVar.foreach { state =>
          val future = onReceive(a, state, self = self, sender = sender()).toFuture
          syncOrAsync(future)
        }
      }


      def syncOrAsync(future: Future[Option[State]]): Unit = {

        def stateAndFunc(a: Try[Option[State]]): (Option[State], () => Unit) = {
          a match {
            case Success(Some(a)) => (a.some, () => ())
            case Success(None)    => (none[State], () => { stateVar = none; context.stop(self) })
            case Failure(e)       => (none[State], () => { stateVar = none; throw e })
          }
        }

        future.value match {
          case Some(value) =>
            val (state, func) = stateAndFunc(value)
            stateVar = state.map { _.pure[Future] }
            func()

          case None =>
            stateVar = future
              .transform { value =>
                val (state, func) = stateAndFunc(value)
                adapter.inReceive { func() }
                state match {
                  case Some(state) => state.pure[Try]
                  case None        => Failure(Terminated)
                }
              }
              .some
        }
      }
    }
  }

  private case object Terminated extends RuntimeException with NoStackTrace
}