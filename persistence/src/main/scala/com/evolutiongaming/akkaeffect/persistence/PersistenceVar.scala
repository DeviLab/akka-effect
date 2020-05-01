package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Sync
import com.evolutiongaming.akkaeffect.{Act, ActorVar, Fail, ReplyOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

private[akkaeffect] trait PersistenceVar[F[_], S, C, E, R] {

  def preStart(eventSourced: EventSourcedAny[F, S, C, E, R]): Unit

  def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]): Unit

  def event(seqNr: SeqNr, event: E): Unit

  def recoveryCompleted(
    seqNr: SeqNr,
    replyOf: ReplyOf[F, R],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Unit

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): Unit

  def postStop(seqNr: SeqNr): F[Unit]
}

private[akkaeffect] object PersistenceVar {

  def apply[F[_] : Sync : ToFuture : FromFuture : Fail, S, C, E, R](
    act: Act[Future],
    context: ActorContext
  ): PersistenceVar[F, S, C, E, R] = {
    apply(ActorVar[F, Persistence[F, S, C, E, R]](act, context))
  }

  def apply[F[_] : Sync : Fail, S, C, E, R](
    actorVar: ActorVar[F, Persistence[F, S, C, E, R]]
  ): PersistenceVar[F, S, C, E, R] = {

    new PersistenceVar[F, S, C, E, R] {

      def preStart(eventSourced: EventSourcedAny[F, S, C, E, R]) = {
        actorVar.preStart {
          Persistence.started(eventSourced)
        }
      }

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        actorVar.receive { _.snapshotOffer(seqNr, snapshotOffer) }
      }

      def event(seqNr: SeqNr, event: E) = {
        actorVar.receive { _.event(seqNr, event) }
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        replyOf: ReplyOf[F, R],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        actorVar.receive { _.recoveryCompleted(seqNr, replyOf, journaller, snapshotter) }
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        actorVar.receive { _.command(seqNr, cmd, sender) }
      }

      def postStop(seqNr: SeqNr) = {
        actorVar.postStop()
      }
    }
  }
}
