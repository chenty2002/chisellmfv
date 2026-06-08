// import Mill dependency
import mill._
import mill.scalalib.TestModule.ScalaTest
import scalalib._
// support BSP
import mill.bsp._

object root extends SbtModule { m =>
  override def millSourcePath = os.pwd
  override def scalaVersion = "2.13.16"
  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Ymacro-annotations",
    "-Ytasty-reader"
  )
  override def ivyDeps = Agg(
    ivy"org.chipsalliance::chisel:6.7.0",
  )
  override def scalacPluginIvyDeps = Agg(
    ivy"org.chipsalliance:::chisel-plugin:6.7.0",
  )
  object test extends SbtModuleTests with ScalaTest {
    override def ivyDeps = m.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest:3.2.18"
    )
  }
}
