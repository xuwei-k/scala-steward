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

package org.scalasteward.core

import org.scalasteward.core.data.Update
import org.scalasteward.core.update.show

package object git {
  def branchFor(updates: Update*): Branch =
    Branch(
      updates.toList
        .sortBy(u => (u.name, u.nextVersion))
        .map(u => s"${u.name}-${u.nextVersion}")
        .mkString("update-", "-", "")
    )

  def commitMsgFor(update: Update): String =
    s"Update ${show.oneLiner(update)} to ${update.nextVersion}"

  // man 7 gitrevisions:
  // When you have two commits r1 and r2 you can ask for commits that are
  // reachable from r2 excluding those that are reachable from r1 by ^r1 r2
  // and it can be written as
  //   r1..r2.
  def dotdot(r1: Branch, r2: Branch): String =
    s"${r1.name}..${r2.name}"
}
