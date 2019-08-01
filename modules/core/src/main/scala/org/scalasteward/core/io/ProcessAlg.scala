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

package org.scalasteward.core.io

import java.io.IOException

import better.files.File
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import org.scalasteward.core.application.Cli.EnvVar
import org.scalasteward.core.application.Config
import org.scalasteward.core.util.Nel

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.sys.process.{Process, ProcessLogger}

trait ProcessAlg[F[_]] {
  def exec(
      command: Nel[String],
      cwd: File,
      logPrefix: String,
      extraEnv: (String, String)*
  ): F[List[String]]

  def execSandboxed(command: Nel[String], cwd: File): F[List[String]]
}

object ProcessAlg {
  abstract class UsingFirejail[F[_]](config: Config) extends ProcessAlg[F] {
    override def execSandboxed(command: Nel[String], cwd: File): F[List[String]] = {
      val envVars = config.envVars.map(EnvVar.unapply(_).get)
      val logPrefix = ""
      if (config.disableSandbox) {
        exec(command, cwd, logPrefix = logPrefix, envVars: _*)
      } else {
        val whitelisted = (cwd.pathAsString :: config.whitelistedDirectories)
          .map(dir => s"--whitelist=$dir")
        val readOnly = config.readOnlyDirectories
          .map(dir => s"--read-only=$dir")
        exec(
          Nel("firejail", whitelisted ++ readOnly) ::: command,
          cwd,
          logPrefix = logPrefix,
          envVars: _*
        )
      }
    }
  }

  def create[F[_]](
      implicit
      config: Config,
      contextShift: ContextShift[F],
      logger: Logger[F],
      timer: Timer[F],
      F: Concurrent[F]
  ): ProcessAlg[F] =
    new UsingFirejail[F](config) {
      override def exec(
          command: Nel[String],
          cwd: File,
          logPrefix: String,
          extraEnv: (String, String)*
      ): F[List[String]] =
        logger.debug(s"Execute ${command.mkString_(" ")}") >>
          F.delay {
            val lb = ListBuffer.empty[String]
            val log = new ProcessLogger {
              override def out(s: => String): Unit = lb.append(logPrefix + s)
              override def err(s: => String): Unit = lb.append(logPrefix + s)
              override def buffer[T](f: => T): T = f
            }
            val exitCode = Process(command.toList, cwd.toJava, extraEnv: _*).!(log)
            if (exitCode =!= 0) throw new IOException(lb.mkString("\n"))
            lb.result()
          }
    }
}
