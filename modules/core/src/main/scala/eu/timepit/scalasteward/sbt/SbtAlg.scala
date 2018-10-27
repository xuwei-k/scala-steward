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

package eu.timepit.scalasteward.sbt

import better.files.File
import cats.Monad
import cats.implicits._
import cats.data.{NonEmptyList => Nel}
import eu.timepit.scalasteward.dependency.Dependency
import eu.timepit.scalasteward.dependency.parser.parseDependencies
import eu.timepit.scalasteward.github.data.Repo
import eu.timepit.scalasteward.io.{FileAlg, FileData, ProcessAlg, WorkspaceAlg}
import eu.timepit.scalasteward.model.Update
import eu.timepit.scalasteward.sbt.command._
import eu.timepit.scalasteward.sbtLegacy
import io.chrisdavenport.log4cats.Logger

trait SbtAlg[F[_]] {
  def testCompile(repo: Repo): F[Unit]

  def addGlobalPlugin(plugin: FileData): F[Unit]

  def addGlobalPlugins: F[Unit]

  def getDependencies(repo: Repo): F[List[Dependency]]

  def getUpdates(project: ArtificialProject): F[List[Update]]

  def getUpdatesForRepo(repo: Repo): F[List[Update]]
}

object SbtAlg {
  def create[F[_]](
      implicit
      fileAlg: FileAlg[F],
      logger: Logger[F],
      processAlg: ProcessAlg[F],
      workspaceAlg: WorkspaceAlg[F],
      F: Monad[F]
  ): SbtAlg[F] =
    new SbtAlg[F] {
      override def addGlobalPlugin(plugin: FileData): F[Unit] =
        List("0.13", "1.0").traverse_ { series =>
          sbtDir.flatMap(dir => fileAlg.writeFileData(dir / series / "plugins", plugin))
        }

      override def addGlobalPlugins: F[Unit] =
        for {
          _ <- logger.info("Add global sbt plugins")
          _ <- addGlobalPlugin(sbtUpdatesPlugin)
          _ <- addGlobalPlugin(stewardPlugin)
        } yield ()

      override def getDependencies(repo: Repo): F[List[Dependency]] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          cmd = sbtCmd(libraryDependenciesAsJson, reloadPlugins, libraryDependenciesAsJson)
          lines <- ignoreOptsFiles(repoDir)(processAlg.execSandboxed(cmd, repoDir))
        } yield lines.flatMap(parseDependencies)

      override def getUpdates(project: ArtificialProject): F[List[Update]] =
        for {
          updatesDir <- workspaceAlg.rootDir.map(_ / "updates")
          projectDir = updatesDir / "project"
          _ <- fileAlg.writeFileData(updatesDir, project.mkBuildSbt)
          _ <- fileAlg.writeFileData(projectDir, project.mkBuildProperties)
          _ <- fileAlg.writeFileData(projectDir, project.mkPluginsSbt)
          cmd = sbtCmd(project.dependencyUpdatesCmd: _*)
          lines <- processAlg.exec(cmd, updatesDir)
          _ <- fileAlg.deleteForce(updatesDir)
        } yield sbtLegacy.toUpdates(lines)

      override def getUpdatesForRepo(repo: Repo): F[List[Update]] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          cmd = sbtCmd(setCredentialsToNil, dependencyUpdates, reloadPlugins, dependencyUpdates)
          lines <- ignoreOptsFiles(repoDir)(processAlg.execSandboxed(cmd, repoDir))
        } yield sbtLegacy.sanitizeUpdates(sbtLegacy.toUpdates(lines))

      val sbtDir: F[File] =
        fileAlg.home.map(_ / ".sbt")

      def sbtCmd(command: String*): Nel[String] =
        Nel.of("sbt", "-batch", "-no-colors", command.mkString(";", ";", ""))

      def ignoreOptsFiles[A](dir: File)(fa: F[A]): F[A] =
        fileAlg.removeTemporarily(dir / ".jvmopts") {
          fileAlg.removeTemporarily(dir / ".sbtopts") {
            fa
          }
        }

      def testCompile(repo: Repo): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          cmd = sbtCmd("test:compile")
          _ <- ignoreOptsFiles(repoDir)(processAlg.execSandboxed(cmd, repoDir))
        } yield ()
    }
}
