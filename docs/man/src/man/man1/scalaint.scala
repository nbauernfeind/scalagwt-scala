/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author Stephane Micheloud
 */
//$Id: $

package man.man1

object scalaint extends Command {
  import ManPage._

  protected val cn = new Error().getStackTrace()(0).getClassName()

  val name = Section("NAME",

    MBold(command) & " " & NDash & " Interpreter for the " &
    Link("Scala 2", "http://scala.epfl.ch/") & " language")

  val synopsis = Section("SYNOPSIS",

    CmdLine(" [ " & Argument("source file") & " ]"))

  val parameters = Section("PARAMETERS",

    DefinitionList(
      Definition(
        Mono(Argument("source file")),
        "One source file to be interpreted (such as " &
        Mono("MyClass.scala") & ").")))

  val description = Section("DESCRIPTION",

    "The " & MBold(command) & " tool reads class and object definitions, " &
    "written in the Scala programming language, and interprets them in an " &
    "interactive shell environment.")

  val examples = Section("EXAMPLES",

    DefinitionList(
      Definition(
        "Interpret a Scala program",
        CmdLine("HelloWorld"))))

  val seeAlso = Section("SEE ALSO",

    Link(Bold("scala") & "(1)", "scala.html") & ", " &
    Link(Bold("scalac") & "(1)", "scalac.html") & ", " &
    Link(Bold("scaladoc") & "(1)", "scaladoc.html") & ", " &
    Link(Bold("scalascript") & "(1)", "scalascript.html"))

  def manpage = new Document {
    title = command
    date = "April 29, 2005"
    author = "Stephane Micheloud"
    version = "0.1"
    sections = List(
      name,
      synopsis,
      parameters,
      description,
      examples,
      authors,
      bugs,
      copyright,
      seeAlso)
  }
}