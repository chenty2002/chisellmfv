package chisellmfv.indexer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.meta._

object Main {
  private val IoPattern = raw"IO\((Input|Output)\((Bool|UInt|SInt)\((.*?)\)?\)\)\)".r
  private val RegPattern = raw"Reg(?:Init)?\((Bool|UInt|SInt)\((.*?)\)?\)".r
  private val WirePattern = raw"Wire(?:Default)?\((Bool|UInt|SInt)\((.*?)\)?\)".r
  private val WidthPattern = raw"([0-9]+)\.W".r

  def main(args: Array[String]): Unit = {
    if (args.length < 4 || args(0) != "--root" || args(2) != "--output") {
      sys.error("usage: chisel-source-indexer --root ROOT --output OUT SOURCE...")
    }
    val root = Paths.get(args(1)).toAbsolutePath.normalize
    val output = Paths.get(args(3)).toAbsolutePath.normalize
    val sources = args.drop(4).map(Paths.get(_).toAbsolutePath.normalize)
    if (sources.isEmpty) sys.error("at least one Scala source is required")

    val sourceRows = collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val objectRows = collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val guardRows = collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    implicit val dialect: Dialect = dialects.Scala213

    sources.foreach { source =>
      if (!source.startsWith(root)) sys.error(s"source escapes root: $source")
      val relative = root.relativize(source).toString.replace('\\', '/')
      val bytes = Files.readAllBytes(source)
      val sourceHash = sha256(bytes)
      val input = Input.VirtualFile(relative, new String(bytes, StandardCharsets.UTF_8))
      val tree = input.parse[Source].fold(error => sys.error(error.toString), identity)
      sourceRows += Map("path" -> relative, "sha256" -> sourceHash)

      tree.collect { case cls: Defn.Class => cls }.foreach { cls =>
        val owner = cls.name.value
        cls.templ.stats.collect { case value: Defn.Val => value }.foreach { value =>
          value.pats.collect { case Pat.Var(name) => name.value }.foreach { name =>
            classify(value.rhs.syntax).foreach { fact =>
              val anchor = sourceAnchor(relative, sourceHash, owner, value.pos)
              objectRows += Map(
                "object_id" -> stableId(relative, owner, name, value.pos.startLine.toString),
                "name" -> name,
                "source_anchor" -> anchor,
                "hardware_kind" -> fact("hardware_kind"),
                "scala_hardware_domain" -> "hardware",
                "chisel_type" -> Map(
                  "kind" -> fact("kind"),
                  "width" -> fact("width"),
                  "signed" -> (fact("kind") == "SInt"),
                  "fields" -> List.empty[Any],
                  "index_domain" -> null
                ),
                "direction" -> fact("direction"),
                "owner_module" -> owner,
                "guard_context" -> Map(
                  "elaboration_conditions" -> List.empty[Any],
                  "hardware_guards" -> List.empty[Any]
                ),
                "accessibility" -> (if (fact("hardware_kind") == "port") "direct" else "wrapper"),
                "fact_status" -> "source_candidate",
                "evidence_refs" -> List(s"$relative:${value.pos.startLine + 1}")
              )
            }
          }
        }

        cls.templ.collect {
          case term: Term.If =>
            guardRows += guardRow(relative, sourceHash, owner, "elaboration", term.cond, term.pos)
          case term @ Term.Apply(fun, args)
              if args.nonEmpty && (fun.syntax == "when" || fun.syntax.endsWith(".elsewhen")) =>
            guardRows += guardRow(relative, sourceHash, owner, "hardware", args.head, term.pos)
        }
      }
    }

    val payload = Map(
      "schema_version" -> "scala_source_index.v1",
      "sources" -> sourceRows.toList,
      "objects" -> objectRows.toList.sortBy(row => row("object_id").toString),
      "guards" -> guardRows.toList.sortBy(row => row("guard_id").toString)
    )
    Files.createDirectories(output.getParent)
    Files.write(output, (json(payload) + "\n").getBytes(StandardCharsets.UTF_8))
  }

  private def classify(source: String): Option[Map[String, Any]] = source match {
    case IoPattern(direction, kind, body) =>
      Some(Map(
        "hardware_kind" -> "port",
        "direction" -> direction.toLowerCase,
        "kind" -> kind,
        "width" -> width(kind, body)
      ))
    case RegPattern(kind, body) =>
      Some(Map("hardware_kind" -> "reg", "direction" -> "internal", "kind" -> kind, "width" -> width(kind, body)))
    case WirePattern(kind, body) =>
      Some(Map("hardware_kind" -> "wire", "direction" -> "internal", "kind" -> kind, "width" -> width(kind, body)))
    case _ => None
  }

  private def width(kind: String, body: String): Any = {
    if (kind == "Bool") 1
    else WidthPattern.findFirstMatchIn(body).map(_.group(1).toInt).orNull
  }

  private def guardRow(
      path: String,
      sourceHash: String,
      owner: String,
      domain: String,
      expression: Tree,
      position: Position
  ): Map[String, Any] = Map(
    "guard_id" -> stableId(path, owner, domain, expression.syntax, position.startLine.toString),
    "source_anchor" -> sourceAnchor(path, sourceHash, owner, position),
    "owner_module" -> owner,
    "domain" -> domain,
    "expression" -> expression.syntax
  )

  private def sourceAnchor(
      path: String,
      sourceHash: String,
      owner: String,
      position: Position
  ): Map[String, Any] = Map(
    "path" -> path,
    "line_start" -> (position.startLine + 1),
    "line_end" -> (position.endLine + 1),
    "enclosing_symbol" -> owner,
    "source_sha256" -> sourceHash
  )

  private def stableId(parts: String*): String = "obj_" + sha256(parts.mkString("\u0000").getBytes(StandardCharsets.UTF_8)).take(20)

  private def sha256(bytes: Array[Byte]): String = {
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
  }

  private def json(value: Any): String = value match {
    case null => "null"
    case value: String => "\"" + value.flatMap {
      case '\"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if char < ' ' => f"\\u${char.toInt}%04x"
      case char => char.toString
    } + "\""
    case value: Boolean => value.toString
    case value: Byte => value.toString
    case value: Short => value.toString
    case value: Int => value.toString
    case value: Long => value.toString
    case value: Map[_, _] => value.toSeq.sortBy(_._1.toString).map { case (key, item) => json(key.toString) + ":" + json(item) }.mkString("{", ",", "}")
    case value: Iterable[_] => value.map(json).mkString("[", ",", "]")
    case other => sys.error(s"unsupported JSON value: ${other.getClass}")
  }
}
