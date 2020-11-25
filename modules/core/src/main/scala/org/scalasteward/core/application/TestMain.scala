package org.scalasteward.core.application

import better.files.{File, StringInterpolations}
import cats.effect.{ExitCode, IO, IOApp}
import org.scalasteward.core.vcs.github.GitHubApp

object TestMain extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val githubApp = GitHubApp(
      id = 89853,
      file"/Users/kenji/xuwei-k/github-app-keys/scala-steward-bot-test-1.2020-11-23.private-key.pem"
    )

    Context
      .create[IO](
        Cli.Args(
          workspace = File("a"),
          reposFile = File("b"),
          gitAuthorEmail = "d",
          vcsLogin = "e",
          gitAskPass = File("f"),
          githubAppId = Some(githubApp.id),
          githubAppKeyFile = Some(githubApp.keyFile)
        )
      )
      .use { c =>
        c.getGitHubAppRepos1(githubApp).map { x =>
          x.foreach(println)
          ExitCode.Success
        }
      }
  }
}
