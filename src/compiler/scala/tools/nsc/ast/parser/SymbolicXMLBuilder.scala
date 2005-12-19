/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  buraq
 */
// $Id$
package scala.tools.nsc.ast.parser;

import scala.tools.nsc.util.Position;
import scala.Iterator;
import scala.collection.immutable.{ Map, ListMap };
import scala.collection.mutable;
import scala.xml.{Text,TextBuffer};

import symtab.Flags.MUTABLE;

/** this class builds instance of Tree that represent XML */
abstract class SymbolicXMLBuilder(make: TreeBuilder, p: Parsers # Parser, preserveWS: Boolean ) {

  val global: Global;
  import global._;
  import posAssigner.atPos;

  //import scala.tools.scalac.ast.{TreeList => myTreeList}

  var isPattern:Boolean = _;

  import nme.{
   _Attribute           ,
   _MetaData            ,
   _NamespaceBinding    ,
   _NodeBuffer          ,

   _Null                ,

   _PrefixedAttribute   ,
   _UnprefixedAttribute ,
   _Elem                ,
   _Seq                 ,
   _immutable           ,
   _mutable             ,
   _append              ,
   _plus                ,
   _collection          ,
   _toList              ,
   _xml                 ,
   _Comment             ,
   _CharData            ,
   _Node                ,
   _ProcInstr           ,
   _Text                ,
   _EntityRef           ,
  _md,
  _scope,
  _tmpscope};


  // convenience methods
  private def LL[A](x:A*):List[List[A]] = List(List(x:_*));

  private def _scala(name: Name) = Select(Ident(nme.scala_), name);

  private def _scala_Seq  = _scala( _Seq );
  private def _scala_xml(name: Name) = Select( _scala(_xml), name );

  private def _scala_xml_MetaData           = _scala_xml( _MetaData );
  private def _scala_xml_NamespaceBinding   = _scala_xml( _NamespaceBinding );
  private def _scala_xml_Null               = _scala_xml( _Null );
  private def _scala_xml_PrefixedAttribute  = _scala_xml(_PrefixedAttribute);
  private def _scala_xml_UnprefixedAttribute= _scala_xml(_UnprefixedAttribute);
  private def _scala_xml_Node               = _scala_xml( _Node );
  private def _scala_xml_NodeBuffer         = _scala_xml( _NodeBuffer );
  private def _scala_xml_EntityRef          = _scala_xml( _EntityRef );
  private def _scala_xml_Comment            = _scala_xml( _Comment );
  private def _scala_xml_CharData           = _scala_xml( _CharData );
  private def _scala_xml_ProcInstr          = _scala_xml( _ProcInstr );
  private def _scala_xml_Text               = _scala_xml( _Text );
  private def _scala_xml_Elem               = _scala_xml( _Elem );
  private def _scala_xml_Attribute          = _scala_xml( _Attribute );


  private def bufferToArray(buf: mutable.Buffer[Tree]): Array[Tree] = {
    val arr = new Array[Tree]( buf.length );
    var i = 0;
    for (val x <- buf.elements) { arr(i) = x; i = i + 1; }
    arr;
  }

  // create scala xml tree

  /**
   *  @arg  namespace: a Tree of type defs.STRING_TYPE
   *  @arg  label:     a Tree of type defs.STRING_TYPE
   *  @todo map:       a map of attributes !!!
   */

  protected def mkXML(pos: int, isPattern: boolean, pre: Tree, label: Tree, attrs: /*Array[*/Tree/*]*/ , scope:Tree, children: mutable.Buffer[Tree]): Tree = {
    if( isPattern ) {
      //val ts = new mutable.ArrayBuffer[Tree]();
      convertToTextPat( children );
      atPos (pos) {
        Apply( _scala_xml_Elem, List(
          pre, label, Ident( nme.WILDCARD ) /* attributes? */ , Ident( nme.WILDCARD )) /* scope? */ ::: children.toList )
      }
    } else {
      var ab = List(pre, label, attrs, scope);
      if(( children.length ) > 0 )
        ab = ab ::: List(Typed(makeXMLseq(pos, children), Ident(nme.WILDCARD_STAR.toTypeName)));
      atPos(pos) { New( _scala_xml_Elem, List(ab) )}
    }
  }

  final def entityRef( pos:int, n: String ) = {
    atPos(pos) { New( _scala_xml_EntityRef, LL(Literal(Constant( n )))) };

  };
  // create scala.xml.Text here <: scala.xml.Node
  final def text( pos: Int, txt:String ):Tree =  {
    //makeText( isPattern, gen.mkStringLit( txt ));
    val txt1 = Literal(Constant(txt));
    atPos(pos) {
      if( isPattern )
        makeTextPat( txt1 );
      else
        makeText1( txt1 );
    }
  }

  // create scala.xml.Text here <: scala.xml.Node
  def makeTextPat(txt:Tree ) = Apply(_scala_xml_Text, List(txt));
  def makeText1(txt:Tree )   = New( _scala_xml_Text, LL( txt ));

  // create
  def comment( pos: int, text: String ):Tree =
    atPos(pos) { Comment( Literal(Constant(text))) };

  // create
  def charData( pos: int, txt: String ):Tree =
    atPos(pos) { CharData( Literal(Constant(txt))) };

  // create scala.xml.Text here <: scala.xml.Node
  def procInstr( pos: int, target: String, txt: String ) =
    atPos(pos) { ProcInstr(Literal(Constant(target)), Literal(Constant(txt))) };

  protected def CharData(txt: Tree) = New( _scala_xml_CharData, LL(txt));
  protected def Comment(txt: Tree)  = New( _scala_xml_Comment, LL(txt));
  protected def ProcInstr(target: Tree, txt: Tree) =
    New(_scala_xml_ProcInstr, LL( target, txt ));


  /** @todo: attributes */
  def makeXMLpat(pos: int, n: String, args: mutable.Buffer[Tree]): Tree =
    mkXML(pos,
          true,
          Ident( nme.WILDCARD ),
          Literal(Constant(n)),
          null, //Predef.Array[Tree](),
          null,
          args);

  protected def convertToTextPat(t: Tree): Tree = t match {
    case _:Literal => makeTextPat(t);
    case _ => t
  }

  protected def convertToTextPat(buf: mutable.Buffer[Tree]): Unit = {
    var i = 0; while( i < buf.length ) {
      val t1 = buf( i );
      val t2 = convertToTextPat( t1 );
      if (!t1.eq(t2)) {
        buf.remove(i);
        buf.insert(i,t2);
      }
      i = i + 1;
    }
  }

    def freshName(prefix:String): Name;

  def isEmptyText(t:Tree) = t match {
    case Literal(Constant("")) => true;
    case _                   => false;
  }
  def makeXMLseq( pos:int, args:mutable.Buffer[Tree] ) = {
    var _buffer = New( _scala_xml_NodeBuffer, List(Nil));
    val it = args.elements;
    while( it.hasNext ) {
      val t = it.next;
      if( !isEmptyText( t )) {
        _buffer = Apply(Select(_buffer, _plus), List( t ));
      }
    }
    atPos(pos) { Select(_buffer, _toList) };
  }

  def makeXMLseqPat( pos:int, args:List[Tree] ) = atPos(pos) {Apply( _scala_Seq, args )};


  /** returns Some(prefix) if pre:name, None otherwise */
  def getPrefix( name:String ):Option[String] = {
    val i = name.indexOf(':');
    if( i != -1 ) Some( name.substring(0, i) ) else None
  }

  /** splits */
  protected def qualifiedAttr( pos:Int, namespace:String, name:String ):Pair[String,String] = {
    getPrefix( name ) match {
      case Some( pref ) =>
        val newLabel = name.substring( pref.length()+1, name.length() );
        // if( newLabel.indexOf(':') != -1 )  syntaxError
        Pair( "namespace$"+pref, newLabel );
      case None =>
        Pair( namespace, name );
    }
  }
  protected def qualified( pos:Int, name:String ):Pair[String,String] =
    getPrefix( name ) match {
      case Some( pref ) =>
        val newLabel = name.substring( pref.length()+1, name.length() );
        // if( newLabel.indexOf(':') != -1 )  syntaxError
        Pair( "namespace$"+pref, newLabel );
      case None =>
        Pair( "namespace$default", name );
    }


  /** makes an element */
  def element(pos: int, qname: String, attrMap: mutable.Map[String,Tree], args: mutable.Buffer[Tree]): Tree = {
    //Console.println("SymbolicXMLBuilder::element("+pos+","+qname+","+attrMap+","+args+")");
    var setNS = new mutable.HashMap[String, Tree];

    var tlist: List[Tree] = List();

    /* pre can be null */
    def handleNamespaceBinding(pre:String , uri:Tree): Unit = {
      val t = Assign(Ident(_tmpscope), New( _scala_xml_NamespaceBinding,
        LL(Literal(Constant(pre)), uri, Ident( _tmpscope))));
      tlist = t :: tlist;
      //Console.println("SymbolicXMLBuilder::handleNamespaceBinding:");
      //Console.println(t.toString());
    }

    /* DEBUG */
    val attrIt = attrMap.keys;
    while( attrIt.hasNext ) {
      val z = attrIt.next;
      if( z.startsWith("xmlns") ) {  // handle namespace
        val i = z.indexOf(':');
        if( i == -1 )
          handleNamespaceBinding(null, attrMap( z ));
          //setNS.update("default", attrMap( z ) );
        else {
          val zz = z.substring( i+1, z.length() );
          //setNS.update( zz, attrMap( z ) );
          handleNamespaceBinding(zz, attrMap( z ));
        }
        attrMap -= z;
      }
    }

    val moreNamespaces = (0 < tlist.length);

    /* */
    val i = qname.indexOf(':');

    var newlabel = qname;
    val pre = getPrefix(qname) match {
      case Some(p) =>
        newlabel = qname.substring(p.length()+1, qname.length());
        p;
      case None =>
        null
    }
    var tlist2: List[Tree] = List();

    // make attributes

    def handlePrefixedAttribute(pre:String, key:String, value:Tree): Unit = {
      val t = atPos(pos) {
        Assign(Ident(_md), New( _scala_xml_PrefixedAttribute,
                                       LL(
                                         Literal(Constant(pre)),
                                         Literal(Constant(key)),
                                         value,
                                         Ident(_md)
                                       )))};
      tlist2 = t :: tlist2;
     // Console.println("SymbolicXMLBuilder::handlePrefixed :");
     // Console.println(t.toString());
    }

    def handleUnprefixedAttribute(key:String, value:Tree): Unit = {
      val t = atPos(pos) {
        Assign(Ident(_md), New(_scala_xml_UnprefixedAttribute,
                               LL(Literal(Constant(key)),value,Ident(_md))
                             ))};
      tlist2 = t :: tlist2;
    }

    var it = attrMap.elements;
    while (it.hasNext) {
      val ansk = it.next;
      getPrefix(ansk._1) match {
        case Some(pre) =>
          val key = ansk._1.substring(pre.length()+1, ansk._1.length());
          handlePrefixedAttribute(pre, key, ansk._2);
        case None      =>
          handleUnprefixedAttribute(ansk._1, ansk._2);
      }
    }
    //  attrs


    val moreAttributes = (0 < tlist2.length);

    var ts: List[Tree] = tlist;
    var ts2: List[Tree] = List();

    if(moreAttributes) {
      ts2 = atPos(pos) {ValDef(Modifiers(MUTABLE),
                              _md,
                              _scala_xml_MetaData,
                              _scala_xml_Null)} :: tlist2;
    }
    if(moreNamespaces) {
      ts = atPos(pos) {
      ValDef(Modifiers(MUTABLE),
             _tmpscope,
             _scala_xml_NamespaceBinding,
             Ident(_scope))} :: ts;

      ts2 = ValDef(NoMods, _scope, _scala_xml_NamespaceBinding, Ident(_tmpscope)) :: ts2;
    }

    val makeSymbolicAttrs = {
      if (moreAttributes) Ident(_md) else _scala_xml_Null;
    }

    var t = mkXML(pos,
                  false,
                  Literal(Constant(pre)) /* can be null */ ,
                  Literal(Constant(newlabel)):Tree,
                  makeSymbolicAttrs,
                  Ident(_scope),
                  args);

    atPos(pos) { Block(ts, Block( ts2, t)) }
  }
}
