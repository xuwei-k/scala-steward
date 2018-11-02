import sbtcrossproject.CrossProject
import sbtcrossproject.CrossType
import sbtcrossproject.Platform

/// variables

val groupId = "eu.timepit"
val projectName = "scala-steward"
val rootPkg = s"$groupId.${projectName.replace("-", "")}"
val gitHubOwner = "fthomas"

val moduleCrossPlatformMatrix: Map[String, List[Platform]] = Map(
  "core" -> List(JVMPlatform)
)

/// projects

lazy val root = project
  .in(file("."))
  .aggregate(coreJVM)
  .aggregate(readme)
  .settings(commonSettings)
  .settings(noPublishSettings)

lazy val core = myCrossProject("core")
  .enablePlugins(BuildInfoPlugin)
  .settings(
    libraryDependencies ++= Seq(
      compilerPlugin(Dependencies.kindProjector),
      Dependencies.betterFiles,
      Dependencies.catsEffect,
      Dependencies.circeGeneric,
      Dependencies.circeParser,
      Dependencies.circeRefined,
      Dependencies.fs2Core,
      Dependencies.http4sBlazeClient,
      Dependencies.http4sCirce,
      Dependencies.log4catsSlf4j,
      Dependencies.logbackClassic,
      Dependencies.refined,
      Dependencies.scalaTest % Test
    ),
    assembly / test := {},
    buildInfoKeys := Seq[BuildInfoKey](scalaVersion, sbtVersion),
    buildInfoPackage := rootPkg,
    initialCommands += s"""
      import $rootPkg._
      import $rootPkg.github.data._
      import better.files.File
      import cats.effect.ContextShift
      import cats.effect.IO
      import org.http4s.client.blaze.BlazeClientBuilder
      import scala.concurrent.ExecutionContext

      implicit val ctxShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
    """,
    fork in run := true
  )

lazy val coreJVM = core.jvm
  .settings(
    scalacOptions -= "-Xfatal-warnings"
  )

lazy val readme = project
  .in(file("modules/readme"))
  .enablePlugins(TutPlugin)
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    fork in Tut := true,
    scalacOptions -= "-Ywarn-unused:imports",
    tutSourceDirectory := baseDirectory.value,
    tutTargetDirectory := (LocalRootProject / baseDirectory).value
  )

/// settings

def myCrossProject(name: String): CrossProject =
  CrossProject(name, file(name))(moduleCrossPlatformMatrix(name): _*)
    .crossType(CrossType.Pure)
    .withoutSuffixFor(JVMPlatform)
    .in(file(s"modules/$name"))
    .settings(moduleName := s"$projectName-$name")
    .settings(commonSettings)
    // workaround for https://github.com/portable-scala/sbt-crossproject/issues/74
    .settings(Seq(Compile, Test).flatMap(inConfig(_) {
      unmanagedResourceDirectories ++= {
        unmanagedSourceDirectories.value
          .map(src => (src / ".." / "resources").getCanonicalFile)
          .filterNot(unmanagedResourceDirectories.value.contains)
          .distinct
      }
    }))

lazy val commonSettings = Def.settings(
  compileSettings,
  metadataSettings,
  scaladocSettings
)

lazy val compileSettings = Def.settings()

lazy val metadataSettings = Def.settings(
  name := projectName,
  organization := groupId,
  homepage := Some(url(s"https://github.com/$gitHubOwner/$projectName")),
  startYear := Some(2018),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  headerLicense := Some(HeaderLicense.ALv2("2018", s"$projectName contributors")),
  developers := List(
    Developer(
      id = "fthomas",
      name = "Frank S. Thomas",
      email = "",
      url(s"https://github.com/fthomas")
    )
  )
)

lazy val noPublishSettings = Def.settings(
  skip in publish := true
)

lazy val scaladocSettings = Def.settings()

/// commands

def addCommandsAlias(name: String, cmds: Seq[String]) =
  addCommandAlias(name, cmds.mkString(";", ";", ""))

addCommandsAlias(
  "validate",
  Seq(
    "clean",
    "headerCheck",
    "scalafmtCheck",
    "scalafmtSbtCheck",
    "test:scalafmtCheck",
    "coverage",
    "test",
    "coverageReport",
    "doc",
    "readme/tut",
    "package",
    "packageSrc",
    "core/assembly"
  )
)

addCommandsAlias(
  "formatAll",
  Seq(
    "headerCreate",
    "scalafmt",
    "test:scalafmt",
    "scalafmtSbt"
  )
)
