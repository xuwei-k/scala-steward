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

package org.scalasteward.core.git

import better.files.File
import cats.effect.Bracket
import cats.implicits._
import org.http4s.Uri
import org.scalasteward.core.application.Config
import org.scalasteward.core.io.{FileAlg, ProcessAlg, WorkspaceAlg}
import org.scalasteward.core.util.{BracketThrowable, Nel}
import org.scalasteward.core.vcs.data.Repo

trait GitAlg[F[_]] {
  def branchAuthors(repo: Repo, branch: Branch, base: Branch): F[List[String]]

  def checkoutBranch(repo: Repo, branch: Branch): F[Unit]

  def clone(repo: Repo, url: String): F[Unit]

  def commitAll(repo: Repo, message: String): F[Unit]

  def containsChanges(repo: Repo): F[Boolean]

  def createBranch(repo: Repo, branch: Branch): F[Unit]

  def deleteBranch(repo: Repo, branch: Branch): F[Unit]

  def currentBranch(repo: Repo): F[Branch]

  /** Returns `true` if merging `branch` into `base` results in merge conflicts. */
  def hasConflicts(repo: Repo, branch: Branch, base: Branch): F[Boolean]

  def isMerged(repo: Repo, branch: Branch, base: Branch): F[Boolean]

  def latestSha1(repo: Repo, branch: Branch): F[Sha1]

  /** Merges `branch` into the current branch using `theirs` as merge strategy option. */
  def mergeTheirs(repo: Repo, branch: Branch): F[Unit]

  def push(repo: Repo, branch: Branch, force: Boolean): F[Unit]

  def remoteBranchExists(repo: Repo, branch: Branch): F[Boolean]

  def removeClone(repo: Repo): F[Unit]

  def setAuthor(repo: Repo, author: Author): F[Unit]

  def syncFork(repo: Repo, upstreamUrl: String, defaultBranch: Branch): F[Unit]

  final def returnToCurrentBranch[A, E](repo: Repo)(fa: F[A])(implicit F: Bracket[F, E]): F[A] =
    F.bracket(currentBranch(repo))(_ => fa)(checkoutBranch(repo, _))
}

object GitAlg {
  val gitCmd: String = "git"

  def create[F[_]](
      implicit
      config: Config,
      fileAlg: FileAlg[F],
      processAlg: ProcessAlg[F],
      workspaceAlg: WorkspaceAlg[F],
      F: BracketThrowable[F]
  ): GitAlg[F] =
    new GitAlg[F] {
      override def branchAuthors(repo: Repo, branch: Branch, base: Branch): F[List[String]] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          exec(Nel.of("log", "--pretty=format:'%an'", dotdot(base, branch)), repoDir)
        }

      override def checkoutBranch(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("checkout", branch.name), repoDir)
        } yield ()

      override def clone(repo: Repo, url: String): F[Unit] =
        for {
          rootDir <- workspaceAlg.rootDir
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("clone", "--recursive", url.toString, repoDir.pathAsString), rootDir)
        } yield ()

      override def commitAll(repo: Repo, message: String): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          trimMessage = {
            val max = 2048
            if (message.length > max) {
              println(s"too long commit message! ${message.length}")
              message.linesIterator.next
            } else {
              message
            }
          }
          sign = if (config.signCommits) List("--gpg-sign") else List("--no-gpg-sign")
          _ <- exec(Nel.of("commit", "--all", "-m", trimMessage) ++ sign, repoDir)
          _ <- exec(Nel.of("diff", "HEAD^"), repoDir)
        } yield ()

      override def containsChanges(repo: Repo): F[Boolean] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          exec(Nel.of("status", "--porcelain", "--untracked-files=no"), repoDir).map(_.nonEmpty)
        }

      override def createBranch(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- {
            val f = exec(Nel.of("checkout", "-b", branch.name), repoDir)
            f.recoverWith {
              case e =>
                println(e)
                for {
                  _ <- deleteBranch(repo, branch)
                  x <- f
                } yield x
            }
          }
        } yield ()

      override def deleteBranch(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("branch", "-D", branch.name), repoDir)
        } yield ()

      override def currentBranch(repo: Repo): F[Branch] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          lines <- exec(Nel.of("rev-parse", "--abbrev-ref", "HEAD"), repoDir)
        } yield Branch(lines.mkString.trim)

      override def hasConflicts(repo: Repo, branch: Branch, base: Branch): F[Boolean] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          val tryMerge = exec(Nel.of("merge", "--no-commit", "--no-ff", branch.name), repoDir)
          val abortMerge = exec(Nel.of("merge", "--abort"), repoDir).void

          returnToCurrentBranch(repo) {
            checkoutBranch(repo, base) >> F.guarantee(tryMerge)(abortMerge).attempt.map(_.isLeft)
          }
        }

      override def isMerged(repo: Repo, branch: Branch, base: Branch): F[Boolean] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          exec(Nel.of("log", "--pretty=format:'%h'", dotdot(base, branch)), repoDir).map(_.isEmpty)
        }

      override def latestSha1(repo: Repo, branch: Branch): F[Sha1] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          lines <- exec(Nel.of("rev-parse", "--verify", branch.name), repoDir)
          sha1 <- F.fromEither(Sha1.from(lines.mkString("").trim))
        } yield sha1

      override def mergeTheirs(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          sign = if (config.signCommits) List("--gpg-sign") else List.empty[String]
          _ <- exec(Nel.of("merge", "--strategy-option=theirs") ++ (sign :+ branch.name), repoDir)
        } yield ()

      override def push(repo: Repo, branch: Branch, force: Boolean): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          f = if (force) List("--force") else Nil
          _ <- exec(
            Nel.of("push", f ::: List("--set-upstream", "origin", branch.name): _*),
            repoDir
          ).recoverWith {
            case e if !force =>
              println(s"skip push for ${repo} ${branch} ${e}")
              F.point(Nil)
          }
        } yield ()

      override def remoteBranchExists(repo: Repo, branch: Branch): F[Boolean] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          branches <- exec(Nel.of("branch", "-r"), repoDir)
        } yield branches.exists(_.endsWith(branch.name))

      override def removeClone(repo: Repo): F[Unit] =
        workspaceAlg.repoDir(repo).flatMap(fileAlg.deleteForce)

      override def setAuthor(repo: Repo, author: Author): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("config", "user.email", author.email), repoDir)
          _ <- exec(Nel.of("config", "user.name", author.name), repoDir)
        } yield ()

      override def syncFork(repo: Repo, upstreamUrl: String, defaultBranch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          remote = "upstream"
          branch = defaultBranch.name
          remoteBranch = s"$remote/$branch"
          _ <- exec(Nel.of("remote", "add", remote, upstreamUrl.toString), repoDir)
          _ <- exec(Nel.of("fetch", remote), repoDir)
          _ <- exec(Nel.of("checkout", "-B", branch, "--track", remoteBranch), repoDir)
          _ <- exec(Nel.of("merge", remoteBranch), repoDir)
          _ <- push(repo, defaultBranch, force = true)
        } yield ()

      def exec(command: Nel[String], cwd: File): F[List[String]] =
        processAlg.exec(
          gitCmd :: command,
          cwd,
          logPrefix = "[" + cwd.name + "]"
        )
    }
}
