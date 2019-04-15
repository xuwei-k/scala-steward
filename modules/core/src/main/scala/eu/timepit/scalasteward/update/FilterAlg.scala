/*
 * Copyright 2018 scala-steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.timepit.scalasteward.update

import cats.implicits._
import cats.{Applicative, TraverseFilter}
import eu.timepit.scalasteward.github.data.Repo
import eu.timepit.scalasteward.model.Update
import io.chrisdavenport.log4cats.Logger

trait FilterAlg[F[_]] {
  def globalFilter(update: Update): F[Option[Update]]

  def localFilter(repo: Repo, update: Update): F[Option[Update]]

  def globalFilterMany[G[_]: TraverseFilter](updates: G[Update])(
      implicit F: Applicative[F]
  ): F[G[Update]] =
    updates.traverseFilter(globalFilter)

  def localFilterMany[G[_]: TraverseFilter](repo: Repo, updates: G[Update])(
      implicit F: Applicative[F]
  ): F[G[Update]] =
    updates.traverseFilter(update => localFilter(repo, update))
}

object FilterAlg {
  def create[F[_]](implicit logger: Logger[F], F: Applicative[F]): FilterAlg[F] =
    new FilterAlg[F] {
      object ScalazVersions {
        def unapply(value: String): Boolean =
          (value.startsWith("7.3") || value.startsWith("8"))
      }

      def globalKeep(update: Update): Boolean =
        (update.groupId, update.artifactId, update.nextVersion) match {
          // squeryl
          case ("mysql", "mysql-connector-java", v) if v.startsWith("8.") => false
          case ("org.postgresql", "postgresql", v) if v.startsWith("42.") => false

          case ("org.scala-sbt", "sbt-launch", _) => false

          case ("org.scalaz.stream", "scalaz-stream", "0.8.6") => false
          
          case ("org.mockito", "mockito-core", _ ) => false // TODO JDK11

          case ("org.scalaz", _, ScalazVersions()) => false

          case ("javax.servlet", "javax.servlet-api", _) => false
          
          // https://github.com/scala/scala-parser-combinators/issues/197
          // https://github.com/sbt/sbt/issues/4609
          case ("org.scala-lang.modules", "scala-parser-combinators", "1.1.2") => false
          
          case ("com.thesamet.scalapb", _, "0.9.0-RC1") => false // 0.9.0-M1 is newer

          // argonaut
          case ("com.google.caliper", "caliper", _) => false

          case ("com.geirsson", a, _) if a.startsWith("scalafmt-core") => false

          case ("org.scala-lang", "scala-compiler", _) => false
          case ("org.scala-lang", "scala-library", _)  => false

          case ("org.eclipse.jetty", "jetty-server", _)    => false
          case ("org.eclipse.jetty", "jetty-websocket", _) => false

          // transitive dependencies of e.g. com.lucidchart:sbt-scalafmt
          case ("com.geirsson", "scalafmt-cli_2.11", _)  => false
          case ("com.geirsson", "scalafmt-core_2.12", _) => false

          // https://github.com/fthomas/scala-steward/issues/105
          case ("io.monix", _, "3.0.0-fbcb270") => false

          // https://github.com/esamson/remder/pull/5
          case ("net.sourceforge.plantuml", "plantuml", "8059") => false

          // https://github.com/http4s/http4s/pull/2153
          case ("org.http4s", _, "0.19.0") => false

          // https://github.com/lightbend/migration-manager/pull/260
          case ("org.scalatest", "scalatest", "3.2.0-SNAP10") => false

          case _ => true
        }

      def localKeep(repo: Repo, update: Update): Boolean =
        (repo.show, update.groupId, update.artifactId) match {
          case ("scala/scala-dist", "com.amazonaws", "aws-java-sdk-s3") => false
          case ("squeryl/squeryl", "org.apache.derby", "derby")         => false

          case ("foundweekends/conscript", "net.databinder.dispatch", _) => false
          case ("foundweekends/conscript", "net.liftweb", _)             => false

          case ("foundweekends/giter8", "org.codehaus.plexus", "plexus-archiver") => false
          case ("foundweekends/giter8", "org.scalacheck", "scalacheck")           => false

          case ("gitbucket/gitbucket", "com.wix", "wix-embedded-mysql") => false

          case ("xuwei-k/iarray", "org.scalaz", _) => false

          case ("eed3si9n/sjson-new", "pl.project13.scala", "sbt-jmh") => false

          case ("gitbucket/gitbucket", "org.json4s", "json4s-jackson") => false

          case ("atnos-org/eff", "org.portable-scala", _) => false
          case ("atnos-org/eff", "org.tpolecat", _) => false // doobie
          
          case ("scalatra/scalatra", "org.apache.httpcomponents", _) => false
          case ("scalatra/scalatra", "org.eclipse.jetty", _) => false
          case ("scalatra/scalatra", "javax.servlet", _) => false
          
          case ("scalikejdbc/scalikejdbc", "org.apache.derby", _) => false
          
          case ("scalikejdbc/csvquery", "com.h2database", _) => false

          case ("skinny-framework/skinny-micro", "org.apache.httpcomponents", _) => false

          case _ => true
        }

      def filterImpl(keep: Boolean, update: Update): F[Option[Update]] =
        if (keep) F.pure(Some(update))
        else logger.info(s"Ignore ${update.show}") *> F.pure(None)

      override def globalFilter(update: Update): F[Option[Update]] =
        filterImpl(globalKeep(update), update)

      override def localFilter(repo: Repo, update: Update): F[Option[Update]] =
        filterImpl(globalKeep(update) && localKeep(repo, update), update)
    }
}
