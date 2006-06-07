/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author Stephane Micheloud
 * Adapted from Lex Spoon's sbaz manual
 */
//$Id: $

package man

// For help on man pages see:
// - http://www.linuxfocus.org/English/November2003/article309.shtml
// - http://www.schweikhardt.net/man_page_howto.html

object EmitManPage {
  import ManPage._

  val out = Console

  def escape(text: String) =
    text.replaceAll("-", "\\-")

  def emitSection(section: Section, depth: int): Unit = {
    def emitText(text: AbstractText): Unit =
      text match {
        case seq:SeqText =>
          seq.components.foreach(emitText)

        case Text(text) =>
          out.print(escape(text))

        case NDash | MDash =>
          out.print("\\-")

        case Bold(text) =>
          out.print("\\fB")
          emitText(text)
          out.print("\\fR")

        case Italic(text) =>
          out.print("\\fI")
          emitText(text)
          out.print("\\fR")

        case Emph(text) =>
          out.print("\\fI")
          emitText(text)
          out.print("\\fI")

        case Mono(text) =>
          out.print("")
          emitText(text)
          out.print("")

        case Quote(text) =>
          out.print("\"")
          emitText(text)
          out.print("\"")

        case DefinitionList(definitions @ _*) =>
          var n = definitions.length
          for (val d <- definitions) {
            out.println(".TP")
            emitText(d.term)
            out.println
            emitText(d.description)
            if (n > 1) { out.println; n = n - 1 }
          }

        case Link(label, url) =>
          emitText(label)

        case _ =>
          error("unknown text node " + text)
      }

    def emitParagraph(para: Paragraph): Unit =
      para match {
        case TextParagraph(text) =>
          out.println(".PP")
          emitText(text)
          out.println

        case BlockQuote(text) =>
          out.println(".TP")
          emitText(text)
          out.println

        case CodeSample(text) =>
          out.println("\n.nf")
          out.print(text)
          out.println(".fi")

        case lst:BulletList =>
          out.println("<ul>")
          for(val item <- lst.items) {
            out.print("<li>")
            emitText(item)
          }
          out.println("</ul>")

        case lst:NumberedList =>
          out.println("<ol>")
          for(val item <- lst.items) {
            out.print("<li>")
            emitText(item)
          }
          out.println("</ol>")

        case TitledPara(title, text) =>
          out.println("<p><strong>" + escape(title) + "</strong>")
          emitText(text)

        case EmbeddedSection(sect) =>
          emitSection(sect, depth+1)

        case _ =>
          error("unknown paragraph node " + para)
      }

    out.println(".\\\"")
    out.println(".\\\" ############################## " + section.title + " ###############################")
    out.println(".\\\"")
    val tag = if (depth > 1) ".SS" else ".SH"
    val title =
      if (section.title.indexOf(" ") > 0) "\"" + section.title + "\""
      else section.title
    out.println(tag + " " + title)

    section.paragraphs.foreach(emitParagraph)
  }

  def emitDocument(doc: Document) = {
    out.println(".\\\" ##########################################################################")
    out.println(".\\\" #                      __                                                #")
    out.println(".\\\" #      ________ ___   / /  ___     Scala 2 On-line Manual Pages          #")
    out.println(".\\\" #     / __/ __// _ | / /  / _ |    (c) 2002-2006, LAMP/EPFL              #")
    out.println(".\\\" #   __\\ \\/ /__/ __ |/ /__/ __ |                                          #")
    out.println(".\\\" #  /____/\\___/_/ |_/____/_/ | |    http://scala.epfl.ch/                 #")
    out.println(".\\\" #                           |/                                           #")
    out.println(".\\\" ##########################################################################")
    out.println(".\\\"")
    out.println(".\\\" Process this file with nroff -man scala.1")
    out.println(".\\\"")
    out.println(".TH " + doc.title + " " + doc.category.id +
                "  \"" + doc.date + "\" \"version " + doc.version +
                "\" \"" + doc.category + "\"")

    doc.sections.foreach(s => emitSection(s, 1))
  }

  def main(args: Array[String]) =
    try {
      val cl = ClassLoader.getSystemClassLoader()
      val clasz = cl.loadClass("man.man1." + args(0))
      val meth = clasz.getDeclaredMethod("manpage", Array[Class]())
      val doc = meth.invoke(null, Array[Object]()).asInstanceOf[Document]
      emitDocument(doc)
    } catch {
      case e: Exception =>
        System.err.println(e.getMessage())
    }

}