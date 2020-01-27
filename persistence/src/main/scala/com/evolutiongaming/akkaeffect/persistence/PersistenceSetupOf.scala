package com.evolutiongaming.akkaeffect.persistence

import cats.Applicative
import cats.effect._
import com.evolutiongaming.akkaeffect.ActorCtx

trait PersistenceSetupOf[F[_], S, C, E, R] {

  def apply(ctx: ActorCtx[F, C, R]): Resource[F, PersistenceSetup[F, S, C, E]]
}

object PersistenceSetupOf {

  def const[F[_] : Applicative, S, C, E, R](
    persistenceSetup: PersistenceSetup[F, S, C, E]
  ): PersistenceSetupOf[F, S, C, E, R] = {
    _ => Resource.pure(persistenceSetup)
  }
}