val chiselVersion = "6.7.0"

lazy val root = (project in file("."))
  .settings(
    name := "gigamax-smv-to-chisel",
    organization := "chisellmfv.benchmark",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Ymacro-annotations"),
    libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion,
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    )
  )
