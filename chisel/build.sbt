// ChiselLMFV build configuration
// Minimal build: only depends on ChiselFV submodule.
// Per-benchmark compilation happens inside chisel/extra_bench/<benchmark>/
// using a separate sbt project (see chisel/extra_bench/build.sbt).

val chisel6Version = "6.7.0"
val scalaVersionFromChisel = "2.13.16"

lazy val commonSettings = Seq(
  organization := "edu.berkeley.cs",
  version := "1.0",
  scalaVersion := scalaVersionFromChisel,
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-Ytasty-reader",
    "-Ymacro-annotations"
  ),
  libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.3.1",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  exportJars := true,
  resolvers ++= Resolver.sonatypeOssRepos("snapshots") ++
                Resolver.sonatypeOssRepos("releases") ++
                Seq(Resolver.mavenLocal)
)

lazy val chiselSettings = Seq(
  libraryDependencies ++= Seq(
    "org.chipsalliance" %% "chisel" % chisel6Version,
    "org.apache.commons" % "commons-lang3" % "3.12.0",
    "org.apache.commons" % "commons-text" % "1.9"
  ),
  addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chisel6Version cross CrossVersion.full)
)

// -- ChiselFV library (from submodule) --
lazy val chiselfv = (project in file("chiselfv"))
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(
    name := "chiselfv",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala"
  )

lazy val root = Project("root", file("."))
  .aggregate(chiselfv)
  .settings(commonSettings)
