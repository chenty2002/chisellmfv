package chisellmfv.indexer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.meta._

object Main {
  private val IoPattern = raw"IO\((Input|Output)\((Bool|UInt|SInt)\((.*?)\)?\)\)\)".r
  private val RegPattern = raw"Reg(?:Init)?\((Bool|UInt|SInt)\((.*?)\)?\)".r
  private val WirePattern = raw"Wire(?:Default)?\((Bool|UInt|SInt)\((.*?)\)?\)".r
  private val LiteralWireDefaultPattern = raw"WireDefault\(.*\.(U|S|B)(?:\((.*?)\))?\)".r
  private val WidthPattern = raw"([0-9]+)\.W".r

  private final case class IndexedStatement(path: String, owner: String, tree: Tree, row: Map[String, Any])

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
    val statementRows = collection.mutable.ArrayBuffer.empty[IndexedStatement]
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
        val hardwareObjects = collection.mutable.Map.empty[String, String]
        val registerNames = collection.mutable.Set.empty[String]
        cls.templ.stats.collect { case value: Defn.Val => value }.foreach { value =>
          value.pats.collect { case Pat.Var(name) => name.value }.foreach { name =>
            classify(value.rhs.syntax).foreach { fact =>
              val anchor = sourceAnchor(relative, sourceHash, owner, value.pos)
              val objectId = stableId(relative, owner, name, value.pos.startLine.toString)
              hardwareObjects(name) = objectId
              if (fact("hardware_kind") == "reg") registerNames += name
              objectRows += Map(
                "object_id" -> objectId,
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
          case value: Defn.Val
              if value.pats.exists {
                case Pat.Var(name) => hardwareObjects.contains(name.value)
                case _ => false
              } =>
            IndexedStatement(
              relative,
              owner,
              value,
              statementRow(
                relative,
                sourceHash,
                owner,
                "declaration",
                value,
                value.pats.collectFirst { case Pat.Var(name) => name.value }.get,
                hardwareObjects
              )
            )
          case term @ Term.ApplyInfix(target, Term.Name(":="), _, _) =>
            val targetName = target.syntax.takeWhile(char => char.isLetterOrDigit || char == '_')
            IndexedStatement(relative, owner, term, statementRow(
              relative,
              sourceHash,
              owner,
              if (registerNames.contains(targetName)) "register_update" else "assignment",
              term,
              target.syntax,
              hardwareObjects
            ))
          case term: Term.If =>
            IndexedStatement(relative, owner, term, elaborationEntityRow(
              relative,
              sourceHash,
              owner,
              "elaboration_guard",
              term,
              term,
              Map("condition" -> term.cond.syntax)
            ))
          case term @ Term.Apply(Term.Apply(Term.Name("when"), _), args) if args.nonEmpty =>
            IndexedStatement(relative, owner, term, statementRow(
              relative, sourceHash, owner, "when", term, "", hardwareObjects
            ))
          case term @ Term.Apply(Term.Apply(Term.Select(_, Term.Name("elsewhen")), _), args)
              if args.nonEmpty =>
            IndexedStatement(relative, owner, term, statementRow(
              relative, sourceHash, owner, "elsewhen", term, "", hardwareObjects
            ))
          case term @ Term.Apply(Term.Name("switch"), _) =>
            IndexedStatement(relative, owner, term, statementRow(
              relative, sourceHash, owner, "switch", term, "", hardwareObjects
            ))
          case term @ Term.Apply(Term.Name("is"), _) =>
            IndexedStatement(relative, owner, term, statementRow(
              relative, sourceHash, owner, "case", term, "", hardwareObjects
            ))
        }.foreach(statementRows += _)

        cls.templ.collect {
          case term @ Term.Apply(Term.Select(_, Term.Name("updated")), List(row, value)) =>
            IndexedStatement(relative, owner, term, elaborationEntityRow(
              relative,
              sourceHash,
              owner,
              "table_update",
              term,
              term,
              Map(
                "row_expression" -> row.syntax,
                "value_expression" -> value.syntax
              )
            ))
          case pair @ Term.ApplyInfix(
                key: Lit.String,
                Term.Name("->"),
                _,
                List(value)
              ) =>
            IndexedStatement(relative, owner, pair, elaborationEntityRow(
              relative,
              sourceHash,
              owner,
              "blackbox_parameter",
              pair,
              pair,
              Map("parameter" -> key.value, "value_expression" -> value.syntax)
            ))
        }.foreach(statementRows += _)

        cls.templ.collect {
          case term: Term.If =>
            guardRows += guardRow(relative, sourceHash, owner, "elaboration", term.cond, term.pos)
          case term @ Term.Apply(fun, args)
              if args.nonEmpty && (fun.syntax == "when" || fun.syntax.endsWith(".elsewhen")) =>
            guardRows += guardRow(relative, sourceHash, owner, "hardware", args.head, term.pos)
        }
      }

      tree.collect { case obj: Defn.Object => obj }.foreach { obj =>
        val tableWidths = obj.templ.stats.collect {
          case Defn.Val(_, List(Pat.Var(name)), _, Term.Apply(Term.Name("Seq"), rows))
              if rows.nonEmpty =>
            name.value -> (rows.head match {
              case Term.Apply(Term.Name("Seq"), values) => values.size
              case _ => rows.size
            })
        }.toMap
        obj.templ.collect { case Term.Apply(Term.Name("Seq"), values) => values }
          .foreach { values =>
            values.zipWithIndex.foreach { case (term, selectionIndex) =>
              val updates = tableUpdates(term)
              term match {
                case Term.Apply(_, (name: Lit.String) :: _) if updates.nonEmpty =>
                  statementRows += IndexedStatement(relative, obj.name.value, term, elaborationEntityRow(
                    relative,
                    sourceHash,
                    obj.name.value,
                    "table_update",
                    term,
                    name,
                    Map(
                      "selection_parameter" -> "variantIndex",
                      "selection_value" -> selectionIndex,
                      "row_width" -> tableWidth(term, tableWidths).fold[Any](null)(identity),
                      "updates" -> updates.map { case (row, value) =>
                        Map("row_expression" -> row.syntax, "value_expression" -> value.syntax)
                      }
                    )
                  ))
                case _ =>
              }
            }
          }
      }
    }

    val statements = withHierarchy(statementRows.toList)

    val payload = Map(
      "schema_version" -> "scala_source_index",
      "sources" -> sourceRows.toList,
      "objects" -> objectRows.toList.sortBy(row => row("object_id").toString),
      "guards" -> guardRows.toList.sortBy(row => row("guard_id").toString),
      "statements" -> statements.sortBy(row => row("statement_id").toString)
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
    case LiteralWireDefaultPattern(suffix, body) =>
      val kind = Map("U" -> "UInt", "S" -> "SInt", "B" -> "Bool")(suffix)
      Some(Map("hardware_kind" -> "wire", "direction" -> "internal", "kind" -> kind, "width" -> width(kind, body)))
    case _ => None
  }

  private def width(kind: String, body: String): Any = {
    if (kind == "Bool") 1
    else WidthPattern.findFirstMatchIn(body).map(_.group(1).toInt).orNull
  }

  private def tableUpdates(tree: Tree): List[(Tree, Tree)] = tree.collect {
    case Term.Apply(Term.Select(_, Term.Name("updated")), List(row, value)) => (row, value)
  }

  private def tableWidth(tree: Tree, widths: Map[String, Int]): Option[Int] = {
    val matches = tree.collect {
      case name: Term.Name if widths.contains(name.value) => widths(name.value)
    }.distinct
    if (matches.size == 1) matches.headOption else None
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

  private def statementRow(
      path: String,
      sourceHash: String,
      owner: String,
      kind: String,
      tree: Tree,
      target: String,
      hardwareObjects: collection.Map[String, String]
  ): Map[String, Any] = {
    val names = tree.collect { case name: Term.Name => name.value }.toSet
    Map(
      "statement_id" -> statementId("stmt_", path, owner, kind, tree),
      "statement_kind" -> kind,
      "entity_kind" -> kind,
      "execution_phase" -> "runtime",
      "exact_origin_spec" -> null,
      "exact_origins" -> List.empty[Any],
      "source_anchor" -> sourceAnchor(path, sourceHash, owner, tree.pos),
      "column_start" -> (tree.pos.startColumn + 1),
      "column_end" -> (tree.pos.endColumn + 1),
      "owner_module" -> owner,
      "syntax" -> tree.syntax,
      "target" -> target,
      "semantic_object_ids" -> names.toList.sorted.flatMap(hardwareObjects.get)
    )
  }

  private def elaborationEntityRow(
      path: String,
      sourceHash: String,
      owner: String,
      kind: String,
      tree: Tree,
      anchor: Tree,
      originSpec: Map[String, Any]
  ): Map[String, Any] = {
    Map(
      "statement_id" -> statementId("entity_", path, owner, kind, anchor),
      "statement_kind" -> kind,
      "entity_kind" -> kind,
      "execution_phase" -> "elaboration",
      "exact_origin_spec" -> originSpec,
      "exact_origins" -> List.empty[Any],
      "source_anchor" -> sourceAnchor(path, sourceHash, owner, anchor.pos),
      "column_start" -> (anchor.pos.startColumn + 1),
      "column_end" -> (anchor.pos.endColumn + 1),
      "owner_module" -> owner,
      "syntax" -> tree.syntax,
      "target" -> "",
      "semantic_object_ids" -> List.empty[Any]
    )
  }

  private def withHierarchy(rows: List[IndexedStatement]): List[Map[String, Any]] = {
    val retained = rows.filterNot { child =>
      child.row("statement_kind") == "elaboration_guard" && rows.exists { candidate =>
        candidate.row("statement_kind") == "blackbox_parameter" &&
          candidate.path == child.path && candidate.owner == child.owner &&
          candidate.tree.pos.start <= child.tree.pos.start && candidate.tree.pos.end >= child.tree.pos.end
      }
    }
    val ancestors = retained.map { child =>
      val containers = retained.filter { candidate =>
        candidate.path == child.path && candidate.owner == child.owner &&
          candidate.tree.pos.start <= child.tree.pos.start && candidate.tree.pos.end >= child.tree.pos.end &&
          (candidate.tree.pos.start < child.tree.pos.start || candidate.tree.pos.end > child.tree.pos.end)
      }.sortBy(candidate => candidate.tree.pos.end - candidate.tree.pos.start)
      child.row("statement_id").toString -> containers.map(_.row("statement_id").toString)
    }.toMap
    val parents = ancestors.collect { case (child, parent :: _) => child -> parent }
    val children = parents.toList.groupMap(_._2)(_._1).view.mapValues(_.sorted).toMap
    retained.map { indexed =>
      val id = indexed.row("statement_id").toString
      indexed.row ++ Map(
        "parent_statement_id" -> parents.getOrElse(id, null),
        "ancestor_statement_ids" -> ancestors.getOrElse(id, Nil),
        "child_statement_ids" -> children.getOrElse(id, Nil)
      )
    }
  }

  private def statementId(prefix: String, path: String, owner: String, kind: String, tree: Tree): String = {
    val identity = Seq(
      path,
      owner,
      kind,
      tree.pos.startLine.toString,
      tree.pos.startColumn.toString,
      tree.pos.endLine.toString,
      tree.pos.endColumn.toString
    )
    prefix + sha256(identity.mkString("\u0000").getBytes(StandardCharsets.UTF_8)).take(24)
  }

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
