package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorRefFactory, Props}
import cats.effect.{Bracket, Resource, Sync}
import cats.{Applicative, Defer, ~>}

/**
  * Resource-full api for ActorRefFactory
  *
  * @see [[akka.actor.ActorRefFactory]]
  */
trait ActorRefOf[F[_]] {

  def apply(
    props: Props,
    name: Option[String] = None
  ): Resource[F, ActorRef]

  // TODO remove this
  def toUnsafe: ActorRefFactory
}

object ActorRefOf {

  def fromActorRefFactory[F[_]: Sync](
    actorRefFactory: ActorRefFactory
  ): ActorRefOf[F] = {

    new ActorRefOf[F] {

      def apply(props: Props, name: Option[String]) = {
        Resource.make {
          name match {
            case Some(name) => Sync[F].delay { actorRefFactory.actorOf(props, name) }
            case None       => Sync[F].delay { actorRefFactory.actorOf(props) }
          }
        } { actorRef =>
          Sync[F].delay { actorRefFactory.stop(actorRef) }
        }
      }

      def toUnsafe = actorRefFactory
    }
  }


  implicit class ActorRefOfOps[F[_]](val self: ActorRefOf[F]) extends AnyVal {

    def mapK[G[_]](
      f: F ~> G)(implicit
      B: Bracket[F, Throwable],
      D: Defer[G],
      G: Applicative[G]
    ): ActorRefOf[G] = new ActorRefOf[G] {

      def apply(props: Props, name: Option[String]) = self(props, name).mapK(f)

      def toUnsafe = self.toUnsafe
    }
  }
}