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

package org.scalasteward.core.vcs.data

import cats.implicits._
import io.circe.Encoder
import io.circe.generic.semiauto._
import org.scalasteward.core.data.{SemVer, Update}
import org.scalasteward.core.git.Branch
import org.scalasteward.core.nurture.UpdateData
import org.scalasteward.core.repoconfig.RepoConfigAlg
import org.scalasteward.core.util.Nel
import org.scalasteward.core.{git, scalafix}

final case class NewPullRequestData(
    title: String,
    body: String,
    head: String,
    base: Branch
)

object NewPullRequestData {
  implicit val newPullRequestDataEncoder: Encoder[NewPullRequestData] =
    deriveEncoder

  def bodyFor(update: Update, login: String, binaryIssues: String): String = {
    val artifacts = update match {
      case s: Update.Single =>
        s" ${s.groupId}:${s.artifactId} "
      case g: Update.Group =>
        g.artifactIds
          .map(artifactId => s"* ${g.groupId}:$artifactId\n")
          .mkString_("\n", "", "\n")
    }
    val (migrationLabel, appliedMigrations) = migrationNote(update)
    val labels = Nel.fromList(semVerLabel(update).toList ++ migrationLabel.toList)

    s"""|Updates${artifacts}from ${update.currentVersion} to ${update.nextVersion}.
        |
        |I'll automatically update this PR to resolve conflicts as long as you don't change it yourself.
        |
        |If you'd like to skip this version, you can just close this PR. If you have any feedback, just mention @$login in the comments below.
        |
        |Have a fantastic day writing Scala!
        |
        |```
        |${binaryIssues}
        |```
        |
        |<details>
        |<summary>Ignore future updates</summary>
        |
        |Add this to your `${RepoConfigAlg.repoConfigBasename}` file to ignore future updates of this dependency:
        |```
        |${RepoConfigAlg.configToIgnoreFurtherUpdates(update)}
        |```
        |</details>
        |${appliedMigrations.getOrElse("")}
        |${labels.fold("")(_.mkString_("labels: ", ", ", ""))}
        |""".stripMargin.trim
  }

  def migrationNote(update: Update): (Option[String], Option[String]) = {
    val migrations = scalafix.findMigrations(update)
    if (migrations.isEmpty)
      (None, None)
    else
      (
        Some("scalafix-migrations"),
        Some(
          s"""<details>
             |<summary>Applied Migrations</summary>
             |
             |${migrations.flatMap(_.rewriteRules.toList).map(rule => s"* ${rule}").mkString("\n")}
             |</details>
             |""".stripMargin.trim
        )
      )
  }

  def semVerLabel(update: Update): Option[String] =
    for {
      curr <- SemVer.parse(update.currentVersion)
      next <- SemVer.parse(update.nextVersion)
      change <- SemVer.getChange(curr, next)
    } yield s"semver-${change.render}"

  def from(
      data: UpdateData,
      branchName: String,
      authorLogin: String,
      binaryIssues: String
  ): NewPullRequestData =
    NewPullRequestData(
      title = git.commitMsgFor(data.update),
      body = binaryIssues,
      head = branchName,
      base = data.baseBranch
    )
}
