/*
 * Copyright 2018-2020 Scala Steward contributors
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

package org.scalasteward.core.scalafix

import io.circe.Decoder
import io.circe.generic.semiauto._

final case class ScalafixMigrations(
    disableDefaults: Boolean = false,
    extraMigrations: List[Migration] = List.empty
) {
  def migrations(defaultMigrations: List[Migration]) =
    if (disableDefaults) extraMigrations else defaultMigrations ++ extraMigrations
}

object ScalafixMigrations {
  implicit val scalafixMigrationsDecoder: Decoder[ScalafixMigrations] = deriveDecoder
}