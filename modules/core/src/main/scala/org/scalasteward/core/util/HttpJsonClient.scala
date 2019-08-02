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

package org.scalasteward.core.util

import cats.effect.Sync
import cats.implicits._
import io.circe.{Decoder, Encoder}
import org.http4s.Method.{GET, POST}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.client.Client
import org.http4s.{Headers, Method, Request, Response, Status, Uri}

import scala.util.control.NoStackTrace

final class HttpJsonClient[F[_]: Sync](
    implicit client: Client[F]
) {
  type ModReq = Request[F] => F[Request[F]]

  def get[A: Decoder](uri: Uri, modify: ModReq): F[A] = {
    println(s"get $uri")
    request[A](GET, uri, modify)
  }

  def post[A: Decoder](uri: Uri, modify: ModReq): F[A] = {
    println(s"post $uri")
    request[A](POST, uri, modify)
  }

  def postWithBody[A: Decoder, B: Encoder](uri: Uri, body: B, modify: ModReq): F[A] = {
    println(s"post $uri $body")
    post[A](uri, modify.compose(_.withEntity(body)(jsonEncoderOf[F, B])))
  }

  private def request[A: Decoder](method: Method, uri: Uri, modify: ModReq): F[A] =
    client.expectOr[A](modify(Request[F](method, uri)))(
      resp => toUnexpectedResponse(uri, method, resp)
    )(jsonOf[F, A])

  private def toUnexpectedResponse(
      uri: Uri,
      method: Method,
      response: Response[F]
  ): F[Throwable] = {
    val body = response.body.through(fs2.text.utf8Decode).compile.foldMonoid
    body.map(UnexpectedResponse(uri, method, response.headers, response.status, _))
  }
}

final case class UnexpectedResponse(
    uri: Uri,
    method: Method,
    headers: Headers,
    status: Status,
    body: String
) extends RuntimeException
    with NoStackTrace {

  override def getMessage: String =
    s"uri: $uri\nmethod: $method\nstatus: $status\nheaders: $headers\nbody: $body"
}
