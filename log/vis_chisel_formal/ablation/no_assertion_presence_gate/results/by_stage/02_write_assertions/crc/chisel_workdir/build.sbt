// Verilog2Chisel Build Configuration
val chiselVersion = "6.7.0"
val scalaVersionFromChisel = "2.13.16"

lazy val root = (project in file("."))
  .settings(
    name := "chisel_extra_bench",
    organization := "edu.berkeley.cs",
    version := "1.0",
    scalaVersion := scalaVersionFromChisel,
    
    scalacOptions ++= Seq(
      "-deprecation",
      "-unchecked",
      "-Ymacro-annotations"
    ),
    
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
    ),
    
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    
    // Source directories
    Compile / scalaSource := baseDirectory.value,
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / ".." / "chiselfv" / "src" / "main" / "scala",
    
    resolvers ++= Resolver.sonatypeOssRepos("snapshots") ++ 
                  Resolver.sonatypeOssRepos("releases") ++ 
                  Seq(Resolver.mavenLocal)
  )
