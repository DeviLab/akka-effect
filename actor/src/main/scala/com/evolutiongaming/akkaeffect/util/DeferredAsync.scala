package com.evolutiongaming.akkaeffect.util

import cats.effect.concurrent.Deferred
import cats.effect.{Async, Sync}

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

object DeferredAsync {

  def apply[F[_]: Async, A]: F[Deferred[F, A]] = {
    Sync[F].delay { unsafe[F, A] }
  }

  def unsafe[F[_], A](implicit F: Async[F]): Deferred[F, A] = {

    sealed abstract class S

    object S {
      final case class Set(a: A) extends S
      final case class Unset(fs: List[A => Unit]) extends S
    }

    val ref = new AtomicReference[S](S.Unset(List.empty))

    new Deferred[F, A] {

      def get = {
        F.suspend {
          ref.get match {
            case S.Set(a)   =>
              F.pure(a)
            case S.Unset(_) =>
              F.async { callback =>
                @tailrec def register(): Unit = {
                  ref.get match {
                    case S.Set(a)   =>
                      callback(Right(a))
                    case s: S.Unset =>
                      val f = (a: A) => callback(Right(a))
                      val updated = S.Unset(f :: s.fs)
                      if (!ref.compareAndSet(s, updated)) register()
                  }
                }
                register()
              }
          }
        }
      }

      def complete(a: A) = {
        F.suspend {
          @tailrec def complete(): F[Unit] = {
            ref.get match {
              case _: S.Set   =>
                F.unit
              case s: S.Unset =>
                if (ref.compareAndSet(s, S.Set(a))) {
                  if (s.fs.nonEmpty) {
                    F.delay { s.fs.foreach { f => f(a) } }
                  } else {
                    F.unit
                  }
                } else {
                  complete()
                }
            }
          }

          complete()
        }
      }
    }
  }

}