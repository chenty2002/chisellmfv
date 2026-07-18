ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file("."))
  .settings(
    name := "chisel-source-indexer",
    version := "0.1.0",
    libraryDependencies += "org.scalameta" %% "scalameta" % "4.13.7"
  )
