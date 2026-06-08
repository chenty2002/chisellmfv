// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.16"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "chenty"

val chiselVersion = "6.7.0"
val scalaTestVersion = "3.2.18"

lazy val root = (project in file("."))
  .settings(
    name := "ChiselFV",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Ymacro-annotations",
      "-Ytasty-reader",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    Test / parallelExecution := false,
  )
