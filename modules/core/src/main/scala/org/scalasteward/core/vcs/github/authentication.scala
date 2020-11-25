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

package org.scalasteward.core.vcs.github

import java.io.FileReader
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, Security}
import java.util.Date

import better.files.File
import cats.Applicative
import cats.syntax.all._
import io.jsonwebtoken.{Jwts, SignatureAlgorithm}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemReader
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Request}
import org.scalasteward.core.vcs.data.AuthenticatedUser
import scala.concurrent.duration.Duration
import scala.util.Using

object authentication {
  def addCredentials[F[_]: Applicative](user: AuthenticatedUser): Request[F] => F[Request[F]] =
    _.putHeaders(Authorization(BasicCredentials(user.login, user.accessToken))).pure[F]

  Security.addProvider(new BouncyCastleProvider())

  private[this] def parsePEMFile(pemFile: File): Array[Byte] =
    Using.resource(new PemReader(new FileReader(pemFile.toJava))) { reader =>
      reader.readPemObject().getContent
    }

  private[this] def getPrivateKey(keyBytes: Array[Byte]): PrivateKey = {
    val kf = KeyFactory.getInstance("RSA")
    val keySpec = new PKCS8EncodedKeySpec(keyBytes)
    kf.generatePrivate(keySpec)
  }

  private[this] def readPrivateKey(file: File): PrivateKey = {
    val bytes = parsePEMFile(file)
    getPrivateKey(bytes)
  }

  /** [[https://docs.github.com/en/free-pro-team@latest/developers/apps/authenticating-with-github-apps#authenticating-as-a-github-app]] */
  def createJWT(app: GitHubApp, ttl: Duration): String = {
    val ttlMillis = ttl.toMillis
    val nowMillis = System.currentTimeMillis()
    val now = new Date(nowMillis)
    val signingKey = readPrivateKey(app.keyFile)
    val builder = Jwts
      .builder()
      .setIssuedAt(now)
      .setIssuer(app.id.toString)
      .signWith(signingKey, SignatureAlgorithm.RS256)
    if (ttlMillis > 0) {
      val expMillis = nowMillis + ttlMillis
      val exp = new Date(expMillis)
      builder.setExpiration(exp)
    }
    builder.compact()
  }
}
