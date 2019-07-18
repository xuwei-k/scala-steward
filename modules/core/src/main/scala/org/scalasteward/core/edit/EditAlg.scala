/*
 * Copyright 2018-2019 Scala Steward contributors
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

package org.scalasteward.core.edit

import better.files.File
import cats._
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import org.scalasteward.core.data.Update
import org.scalasteward.core.io.{isFileSpecificTo, isSourceFile, FileAlg, WorkspaceAlg}
import org.scalasteward.core.sbt.SbtAlg
import org.scalasteward.core.scalafix
import org.scalasteward.core.util._
import org.scalasteward.core.vcs.data.Repo

final class EditAlg[F[_]](
    implicit
    fileAlg: FileAlg[F],
    logger: Logger[F],
    sbtAlg: SbtAlg[F],
    workspaceAlg: WorkspaceAlg[F],
    F: Sync[F]
) {
  def applyUpdate(repo: Repo, update: Update): F[Unit] =
    for {
      _ <- applyScalafixMigrations(repo, update)
      repoDir <- workspaceAlg.repoDir(repo)
      files <- fileAlg.findSourceFilesContaining(
        repoDir,
        update.currentVersion,
        f => isSourceFile(f) && isFileSpecificTo(update)(f)
      )
      noFilesFound = logger.warn("No files found that contain the current version")
      _ <- files.toNel.fold(noFilesFound)(applyUpdateTo(_, update))
    } yield ()

  def applyUpdates(repo: Repo, updates: NonEmptyList[Update]): F[List[Update]] = {
    def updateFile(file: File, updates: NonEmptyList[Update]): F[List[Update]] =
      F.delay {
        val (contents, list) = updates.foldLeft((file.contentAsString, List.empty[Update])) {
          case ((a, noChanges), b) =>
            b.replaceAllIn(a) match {
              case Some(x) =>
                (x, noChanges)
              case None =>
                (a, b :: noChanges)
            }
        }
        file.write(contents)
        list.reverse
      }

    workspaceAlg.repoDir(repo).flatMap { dir =>
      Functor[F].map(
        FileAlg
          .create[F]
          .walk(dir)
          .filter(isSourceFile)
          .evalMap(updateFile(_, updates))
          .compile
          .toList
      ) { list =>
        val size = list.size
        list.flatten
          .groupBy(identity)
          .map { case (k, v) => k -> v.size }
          .filter(_._2 === size)
          .keys
          .toList
      }
    }
  }

  def applyUpdateTo[G[_]: Traverse](files: G[File], update: Update): F[Unit] = {
    val actions = UpdateHeuristic.all.map { heuristic =>
      logger.info(s"Trying heuristic '${heuristic.name}'") >>
        fileAlg.editFiles(files, heuristic.replaceF(update))
    }
    bindUntilTrue(actions).void
  }

  def applyScalafixMigrations(repo: Repo, update: Update): F[Unit] =
    Nel.fromList(scalafix.findMigrations(update)) match {
      case Some(migrations) =>
        logger.info(s"Applying migrations: $migrations") >> sbtAlg.runMigrations(repo, migrations)
      case None =>
        F.unit
    }
}
