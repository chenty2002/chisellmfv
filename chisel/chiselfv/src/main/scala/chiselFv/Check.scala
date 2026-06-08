package chiselFv

import chisel3.RawModule
import chisel3.stage.{ChiselGeneratorAnnotation, DesignAnnotation}
import circt.stage.ChiselStage

import java.io.{File, PrintWriter}
import java.nio.file.Paths
import scala.io.Source
import scala.util.control.Breaks._

object Check {
  private def jasperGold(files: Array[String]): String = {
    s"""analyze -sv12 ${files.reduce((a, b) => s"$a $b")}
       |elaborate
       |reset reset
       |clock clock
       |
       |set_prove_time_limit 168h
       |set_proofgrid_per_engine_max_jobs 64
       |set_engine_threads 16
       |
       |prove -all
       |report
       |""".stripMargin
  }

  private def sby(mode: String = "prove", engines: String = "smtbmc boolector", depthStr: String, files: Array[String], module: String) = {
    s"""[options]
       |mode $mode
       |$depthStr
       |
       |[engines]
       |$engines
       |
       |[script]
       |read -sv ${files.reduce((a, b) => a + " " + b)}
       |prep -top $module -nordff
       |
       |[files]
       |${files.reduce((a, b) => a + "\n" + b)}
       |""".stripMargin
  }

  private def btorGenYs(files: String, top: String, targetFilename: String = "") = {
    s"""read -sv $files
       |prep -top $top -nordff
       |flatten
       |memory -nomap
       |hierarchy -check
       |setundef -undriven -init -expose
       |write_btor -s ${if (targetFilename == "") top else targetFilename}.btor2
       |""".stripMargin
  }

  def jg[T <: RawModule](dutGen: () => T) = {
    checkJG(dutGen)
  }

  def bmc[T <: RawModule](dutGen: () => T, depth: Int = 20) = {
    checkYosys(dutGen, "bmc", depth)
  }

  def kInduction[T <: RawModule](dutGen: () => T, depth: Int = 20) = {
    checkYosys(dutGen, "prove", depth)
  }

  def pdr[T <: RawModule](dutGen: () => T, depth: Int = 20) = {
    checkYosys(dutGen, "abcPdr", depth)
  }

  private def checkJG[T <: RawModule](dutGen: () => T) = {
    generateRTL(dutGen, "_jg")
    val mod = modName(dutGen)
    val jgTCL = s"$mod.tcl"
    val dirName = mod + "_jg"

    val dir = new File(dirName)
    val files = dir.listFiles.filter(_.getName.endsWith(".sv")).map(_.getName)

    if (dir.listFiles.exists(_.getName.equals(mod))) {
      new ProcessBuilder("rm", "-rf", "./" + mod).directory(dir).start()
    }

    val jgFileContent = jasperGold(files)
    new PrintWriter(dirName + "/" + jgTCL) {
      write(jgFileContent)
      close()
    }

    new ProcessBuilder("jg", "-allow_unsupported_OS", "-tcl", jgTCL).directory(dir).start()
  }

  private def checkYosys[T <: RawModule](dutGen: () => T, mode: String, depth: Int) = {
    generateRTL(dutGen, "_" + mode)
    val mod = modName(dutGen)
    val sbyFileName = s"$mod.sby"
    val dirName = mod + "_" + mode

    val dir = new File(dirName)
    val files = dir.listFiles.filter(_.getName.endsWith(".sv")).map(_.getName)

    if (dir.listFiles.exists(_.getName.equals(mod))) {
      new ProcessBuilder("rm", "-rf", "./" + mod).directory(dir).start()
    }

    var modeStr = "bmc"
    var engines = "smtbmc boolector"
    if (mode.equals("prove")) {
      modeStr = "prove"
    } else if (mode.equals("abcPdr")) {
      modeStr = "prove"
      engines = "abc pdr"
    }
    val depthStr = "depth " + depth


    val sbyFileContent = sby(modeStr, engines, depthStr, files, mod)
    new PrintWriter(dirName + "/" + sbyFileName) {
      write(sbyFileContent)
      close()
    }

    val sbyProcess = new ProcessBuilder("sby", sbyFileName).directory(dir).start()

    processResultHandler(sbyProcess, mod + "_" + mode, dirName)
  }

  private def checkLine(line: String) = {
    val errorEncountered = line.toLowerCase.contains("error")
    val assertFailed = line.toLowerCase.contains("assert failed")
    val coverFailed = line.toLowerCase.contains("unreached cover statement")

    val message = if (coverFailed) {
      "Cover failed"
    } else if (assertFailed) {
      "Assert failed"
    } else if (errorEncountered) {
      "Error encountered"
    } else {
      ""
    }
    if(message != "") {
      println(message)
      false
    } else {
      true
    }
  }

  def generateRTL[T <: RawModule] (dutGen: () => T, targetDirSufix: String = "_build", outputFile: String = "") = {
    val name = modName(dutGen)
    val targetDir = name + targetDirSufix
    val target = new File(targetDir)
    if (!target.exists()) {
      target.mkdirs()
    }
    val rtl = ChiselStage.emitSystemVerilog(dutGen())

    val suffix = "sv"
    val currentPath = Paths.get(System.getProperty("user.dir"))
    val out = if (outputFile.isEmpty) {
      name + "." + suffix
    } else {
      outputFile
    }
    val filePath = Paths.get(currentPath.toString, targetDir, out)
    new PrintWriter(filePath.toString) {
      print(rtl);
      close()
    }
  }

  private def processResultHandler(process: Process, name: String, dir: String): Unit = {

    val output = Source.fromInputStream(process.getInputStream).getLines().mkString("\n")
    val error = Source.fromInputStream(process.getErrorStream).getLines().mkString("\n")

    if (error != "") {
      println("Error: " + error)
    }

    new PrintWriter(dir + "/" + name + ".log") {
      write(output)
      close()
    }

    new PrintWriter(dir + "/" + name + ".err") {
      write(error)
      close()
    }

    var flag = true
    for (line <- output.linesIterator) {
      breakable {
        if (!checkLine(line)) {
          flag = false
          break()
        }
      }
    }
    if (flag) {
      println(name + " successful")
    } else {
      println(name + " failed")
    }
  }

  def generateBtor[T <: RawModule] (dutGen: () => T, targetDirSufix: String = "_btor_gen", outputFile: String = "")  = {
    val name = modName(dutGen)
    val targetDir = name + targetDirSufix
    generateRTL(dutGen, targetDirSufix, outputFile)
    val currentPath = Paths.get(System.getProperty("user.dir"))
    val filePath = Paths.get(currentPath.toString, targetDir, name + ".ys")
    val files = new File(targetDir).listFiles.filter(_.getName.endsWith(".sv")).map(_.getName).mkString(" ")

    new PrintWriter(filePath.toString) {
      print(btorGenYs(files, name, name));
      close()
    }
    var dir = new File(targetDir)
    val yosysProcess = new ProcessBuilder("yosys", filePath.toString).directory(dir).start()

    processResultHandler(yosysProcess, "yosys_parse", targetDir)

  }

  def modName[T <: RawModule] (dutGen: () => T): String = {
    val annos = ChiselGeneratorAnnotation(() => dutGen()).elaborate
    val designAnno = annos.last
    designAnno match {
      case DesignAnnotation(dut) => dut.name
    }
  }
}
