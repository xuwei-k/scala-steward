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

import io.circe.{KeyDecoder, KeyEncoder}
import org.scalasteward.core.data.Update

final case class Repo(
    owner: String,
    repo: String,
    createPullRequest: Boolean = false,
    testCommands: List[String] = "test:compile" :: Nil,
    filter: Update.Single => Boolean = Function.const(true)
) {
  def show: String = s"$owner/$repo"
}

object Repo {
  implicit val repoKeyDecoder: KeyDecoder[Repo] = {
    val / = s"(.+)/([^/]+)".r
    KeyDecoder.instance {
      case owner / repo => Some(Repo(owner, repo))
      case _            => None
    }
  }

  implicit val repoKeyEncoder: KeyEncoder[Repo] =
    KeyEncoder.instance(repo => repo.owner + "/" + repo.repo)
}
