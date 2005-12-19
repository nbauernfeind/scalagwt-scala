package scala.util.automata ;

import scala.collection.{ Set, Map };

/** A deterministic automaton. States are integers, where
 *  0 is always the only initial state. Transitions are represented
 *  in the delta function. A default transitions is one that
 *  is taken when no other transition can be taken.
 *  All states are reachable. Accepting states are those for which
 *  the partial function 'finals' is defined.
 */
abstract class DetWordAutom[T <: AnyRef] {

  val nstates:  Int;
  val finals:   Array[Int] ;
  val delta:    Array[Map[T,Int]];
  val default:  Array[Int] ;

  def isFinal(q: Int) = finals(q) != 0;

  def isSink(q: Int) = delta(q).isEmpty && default(q) == q;

  def next(q: Int, label: T) = {
    delta(q).get(label) match {
      case Some(p) => p
      case _       => default(q)
    }
  }

  override def toString() = {
    val sb = new StringBuffer();
    sb.append("[DetWordAutom  nstates=");
    sb.append(nstates);
    sb.append(" finals=");
    var map = new scala.collection.immutable.ListMap[Int,Int];
    var j = 0; while( j < nstates ) {
      if(j < finals.length)
        map = map.update(j,finals(j));
      j = j + 1;
    }
    sb.append(map.toString());
    sb.append(" delta=\n");
    for( val i <- Iterator.range(0,nstates)) {
      sb.append( i );
      sb.append("->");
      sb.append(delta(i).toString());
      sb.append('\n');
      if(i < default.length) {
        sb.append("_>");
        sb.append(default(i).toString());
        sb.append('\n');
      }
    }
    sb.toString();
  }
}