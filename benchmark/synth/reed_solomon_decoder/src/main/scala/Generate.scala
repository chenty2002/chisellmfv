package reeds

import circt.stage.ChiselStage
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

object Generate extends App {
  require(args.length == 2, "usage: Generate <variant> <target-dir>")
  val variant = args(0)
  val targetDir = Paths.get(args(1))
  val mode = RSVariant.fromName(variant)

  ChiselStage.emitSystemVerilogFile(
    new RS_dec(mode),
    Array("--target-dir", targetDir.toString),
    Array("--disable-all-randomization", "--strip-debug-info"))

  val emitted = targetDir.resolve("RS_dec.sv")
  require(Files.isRegularFile(emitted), s"missing emitted RTL: $emitted")
  val marker = "// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"
  val text = Files.readString(emitted)
  val markerCount = text.sliding(marker.length).count(_ == marker)
  require(markerCount <= 1, s"unexpected inline resource marker count: $markerCount")
  val cleaned = if (markerCount == 1) text.substring(0, text.indexOf(marker)).stripTrailing + "\n" else text
  require(!cleaned.contains("firrtl_black_box_resource_files.f"), "resource-list trailer remains")
  for (helper <- Seq("InitialAsyncReg", "UninitializedByte", "DPRamPrimitive", "GFDecPrimitive", "GFPowPrimitive")) {
    val count = ("(?m)^module\\s+" + helper + "\\b").r.findAllMatchIn(cleaned).length
    require(count == 1, s"expected one $helper definition, found $count")
  }
  Files.writeString(emitted, cleaned, StandardOpenOption.TRUNCATE_EXISTING)
}
