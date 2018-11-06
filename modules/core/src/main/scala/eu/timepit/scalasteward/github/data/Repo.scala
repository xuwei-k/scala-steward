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

package eu.timepit.scalasteward.github.data

import cats.Order
import cats.implicits._
import io.circe.{KeyDecoder, KeyEncoder}

final case class Repo(
    owner: String,
    repo: String,
    createPullRequest: Boolean = false,
    testCommands: List[String] = "test:compile" :: Nil
) {
  def show: String = s"$owner/$repo"
}

object Repo {
  implicit val repoKeyDecoder: KeyDecoder[Repo] = {
    val string = "([^/]+)"
    val / = s"$string/$string".r
    KeyDecoder.instance {
      case owner / repo => Some(Repo(owner, repo))
      case _            => None
    }
  }

  implicit val repoKeyEncoder: KeyEncoder[Repo] =
    KeyEncoder.instance(repo => repo.owner + "/" + repo.repo)

  implicit val repoOrder: Order[Repo] =
    Order.by((repo: Repo) => (repo.owner, repo.repo))
}
