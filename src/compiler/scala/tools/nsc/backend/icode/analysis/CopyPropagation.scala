package scala.tools.nsc.backend.icode.analysis;

import scala.collection.mutable.{Map, HashMap};

/**
 * A modified copy-propagation like analysis. It
 *  is augmented with a record-like value which is used
 *  to represent closures.
 */
abstract class CopyPropagation {
  val global: Global;
  import global._;
  import icodes._;

  /** Locations can be local variables. */
  abstract sealed class Location;
  case class LocalVar(l: Local) extends Location;

  /** Values that can be on the stack. */
  abstract class Value;
  case class This extends Value;
  case class Record(cls: Symbol, bindings: Map[Symbol, Value]) extends Value;
  case class Deref(l: Location) extends Value;
  case class Field(r: Record, sym: Symbol)   extends Value;
  case object Unknown extends Value;

  /** The lattice for this analysis.   */
  object copyLattice extends CompleteLattice {
    type Bindings = Map[Location, Value];

    def emptyBinding = new HashMap[Location, Value]();

    class State(val bindings: Bindings, var stack: List[Value]) {
      override def equals(that: Any): Boolean =
        (this eq that.asInstanceOf[AnyRef]) ||
        that.isInstanceOf[State] && {
          val other = that.asInstanceOf[State];

          /* comparison with bottom is reference equality! */
          if ((other eq bottom) || (this eq bottom))
            (this eq other)
          else {
            this.bindings == other.bindings &&
            List.forall2(this.stack, other.stack) { (a, b) => a == b }
          }
        }

      /* Return the binding for the given local (another local) */
      def getAlias(l: Local): Local = {
        var target = l;
        var stop = false;

        while (bindings.isDefinedAt(LocalVar(target)) && !stop) {
          Console.println("finding alias for " + target);
          bindings(LocalVar(target)) match {
            case Deref(LocalVar(t)) => target = t;
            case _ => stop = true;
          }
        }
        target
      }

      /* Return the binding for the given local. */
      def getBinding(l: Local): Value = {
        var target = l;
        var stop = false;
        var value: Value = Deref(LocalVar(target));

        while (bindings.isDefinedAt(LocalVar(target)) && !stop) {
//          Console.println("finding binding for " + target);
          value = bindings(LocalVar(target));
          value match {
            case Deref(LocalVar(t)) => target = t;
            case _ => stop = true;
          }
        }
        value
      }



      /* Return the binding for the given field of the given record */
      def getBinding(r: Record, f: Symbol): Value = {
        assert(r.bindings.isDefinedAt(f),
               "Record " + r + " does not contain a field " + f);

        var target: Value = r.bindings(f);
        target match {
          case Deref(LocalVar(sym)) => getBinding(sym)
          case _ => target
        }
      }

      override def toString(): String = {
        "\nBindings: " + bindings + "\nStack: " + stack;
      }

      def dup: State = {
        val b: Bindings = new HashMap();
        b ++= bindings;
        new State(b, stack)
      }
    }

    type Elem = State;

    val top    = new State(emptyBinding, Nil);
    val bottom = new State(emptyBinding, Nil);

    def lub2(a: Elem, b: Elem): Elem = {
      if (a eq bottom)      b
      else if (b eq bottom) a
      else if (a == b) a
      else {
        assert(a.stack.length == b.stack.length,
               "Invalid stacks in states: " + a + b);
        val resStack = List.map2(a.stack, b.stack) { (v1, v2) =>
          if (v1 == v2) v1 else Unknown
        }
        val commonPairs = a.bindings.toList intersect (b.bindings.toList);
        val resBindings = new HashMap[Location, Value];
        for (val Pair(k, v) <- commonPairs)
          resBindings += k -> v;
        new State(resBindings, resStack)
      }
    }
  }

  final class CopyAnalysis extends DataFlowAnalysis[copyLattice.type] {
    type P = BasicBlock;
    val lattice = copyLattice;

    var method: IMethod = _;

    def init(m: IMethod): Unit = {
      this.method = m;

      init {
        worklist += m.code.startBlock;
        worklist ++= (m.exh map (.startBlock));
        m.code.blocks.foreach { b =>
          in(b)  = lattice.bottom;
          out(b) = lattice.bottom;
        }
        m.exh foreach { e =>
          in(e.startBlock) = new copyLattice.State(copyLattice.emptyBinding, Unknown :: Nil);
        }
      }
    }

    override def run: Unit = {
      forwardAnalysis(blockTransfer);
      if (settings.debug.value) {
        linearizer.linearize(method).foreach(b => if (b != method.code.startBlock)
          assert(in(b) != lattice.bottom,
            "Block " + b + " in " + this.method + " has input equal to bottom -- not visited?"));
      }
    }

    def blockTransfer(b: BasicBlock, in: lattice.Elem): lattice.Elem = {
      b.toList.foldLeft(in)(interpret)
    }

    import opcodes._;

    /** Abstract interpretation for one instruction. */
    def interpret(in: copyLattice.Elem, i: Instruction): copyLattice.Elem = {
      var out = in.dup;

      if (settings.debug.value) {
        Console.println("- " + i);
        Console.println("in: " + in);
        Console.println("\n");
      }

      i match {
        case THIS(_) =>
          out.stack = This :: out.stack;

        case CONSTANT(_) =>
          out.stack = Unknown :: out.stack;

        case LOAD_ARRAY_ITEM(_) =>
          out.stack = (Unknown :: out.stack.drop(2));

        case LOAD_LOCAL(local) =>
          out.stack = Deref(LocalVar(local)) :: out.stack;

        case LOAD_FIELD(field, isStatic) =>
          if (isStatic)
            out.stack = Unknown :: out.stack; /* ignore static fields */
          else {
            val v1 = in.stack match {
              case (r @ Record(cls, bindings)) :: xs =>
                Field(r, field)

              case _ => Unknown
            }
            out.stack = v1 :: out.stack.drop(1);
          }

        case LOAD_MODULE(module) =>
          out.stack = Unknown :: out.stack;

        case STORE_ARRAY_ITEM(kind) =>
          out.stack = out.stack.drop(3);

        case STORE_LOCAL(local) =>
          cleanReferencesTo(out, local.sym);
          in.stack match {
            case Unknown :: xs => ();
            case v :: vs =>
              v match {
                case Deref(LocalVar(other)) =>
                  if (other != local)
                    out.bindings += LocalVar(local) -> v;
                case _ =>
                  out.bindings += LocalVar(local) -> v;
              }
          }
          out.stack = out.stack drop 1;

        case STORE_FIELD(field, isStatic) =>
          if (isStatic)
            out.stack = out.stack.drop(1);
          else {
            out.stack = out.stack.drop(2);
            cleanReferencesTo(out, field);
            in.stack match {
              case v :: Record(_, bindings) :: vs =>
                bindings += field -> v;
              case _ => ();
            }
          }

        case CALL_PRIMITIVE(primitive) =>
          out.stack = Unknown :: out.stack.drop(i.consumed);

        case CALL_METHOD(method, style) => style match {
          case Dynamic =>
            out = simulateCall(in, method, false);

          case Static(onInstance) =>
            if (onInstance) {
              val obj = out.stack.drop(method.info.paramTypes.length).head;
              if (method.isConstructor) {
                obj match {
                  case Record(_, bindings) =>
                    for (val v <- out.stack.take(method.info.paramTypes.length + 1);
                         v ne obj) {
                       bindings ++= getBindingsForClosure(in, method);
                    }
                  case _ => ()
                }
                // put the Record back on the stack and remove the 'returned' value
                out.stack = out.stack.drop(1 + method.info.paramTypes.length);
              } else
                out = simulateCall(in, method, false);
            } else
              out = simulateCall(in, method, true);

          case SuperCall(_) =>
            out = simulateCall(in, method, false);
        }

        case NEW(kind) =>
          val v1 =
            kind match {
              case REFERENCE(cls) =>
                if (isClosureClass(cls))
                  Record(cls, new HashMap[Symbol, Value])
                else Unknown

              case _ =>
                Unknown
            }
          out.stack = v1 :: out.stack

        case CREATE_ARRAY(elem) =>
          out.stack = Unknown :: out.stack.drop(1);

        case IS_INSTANCE(tpe) =>
          out.stack = Unknown :: out.stack.drop(1);

        case CHECK_CAST(tpe) =>
          out.stack = Unknown :: out.stack.drop(1);

        case SWITCH(tags, labels) =>
          out.stack = out.stack.drop(1);

        case JUMP(where) =>
          ()

        case CJUMP(success, failure, cond, kind) =>
          out.stack = out.stack.drop(2);

        case CZJUMP(success, failure, cond, kind) =>
          out.stack = out.stack.drop(1);

        case RETURN(kind) =>
          if (kind != UNIT)
            out.stack = out.stack.drop(1);

        case THROW() =>
          out.stack = out.stack.drop(1);

        case DROP(kind) =>
          out.stack = out.stack.drop(1);

        case DUP(kind) =>
          out.stack = out.stack.head :: out.stack;

        case MONITOR_ENTER() =>
          out.stack = out.stack.drop(1);

        case MONITOR_EXIT() =>
          out.stack = out.stack.drop(1);

        case _ =>
          dump;
          abort("Unknown instruction: " + i);
      }
      out
    } /* def interpret */

    /** Remove all references to this local variable from both stack
     *  and bindings. It is called when a new assignment destroys
     *  previous copy-relations.
     */
    final def cleanReferencesTo(s: copyLattice.State, sym: Symbol): Unit = {
      def cleanRecord(r: Record): Record = {
        r.bindings filter { (loc, value) =>
          value match {
            case Deref(LocalVar(sym1)) if (sym1 == sym) => false
            case Field(_, sym1) if (sym1 == sym) => false
            case _ => true
          }
        }
        r
      }

      s.stack = s.stack map { v => v match {
        case Record(_, bindings) =>
          cleanRecord(v.asInstanceOf[Record])
        case _ => v
      }}

      s.bindings filter { (loc, value) =>
        value match {
          case Deref(LocalVar(sym1)) if (sym1 == sym) => false
          case Field(_, sym1) if (sym1 == sym) => false
          case Record(_, _) =>
            cleanRecord(value.asInstanceOf[Record]);
            true
          case _ => true
        }
      }
    }

    /** Update the state 's' after the call to 'method'. The stack elements are dropped and replaced
     * by the result of the call. If the method is impure, all bindings to record fields are cleared.
     */
    final def simulateCall(s: copyLattice.State, method: Symbol, static: Boolean): copyLattice.State = {
//       if (static)
//         Console.println("Method type is: " + method.info);

      val out = new copyLattice.State(s.bindings, s.stack);
      out.stack = out.stack.drop(method.info.paramTypes.length + (if (static) 0 else 1));
//       if (static)
//         Console.println("Stack after drops: " + out.stack);
      if (method.info.resultType != definitions.UnitClass.tpe)
        out.stack = Unknown :: out.stack;
//       if (static)
//         Console.println("Stack after return type: " + out.stack);
      if (!isPureMethod(method))
        invalidateRecords(out);
      out
    }

    /** Drop everything known about record fields. */
    final def invalidateRecords(s: copyLattice.State): Unit = {
      s.stack = s.stack map { v => v match {
        case Record(cls, bindings) =>
          Record(cls, new HashMap[Symbol, Value]);
        case _ => v
      }}

      s.bindings filter { (loc, value) =>
        value match {
          case Field(_, _) => false
          case _ => true
        }
      }
    }

    /**
     * Return bindings from closure's fields to the values on the stack. This
     * method has to find the correct mapping from fields to the order in which
     * they are passed on the stack.
     */
    private def getBindingsForClosure(in: copyLattice.State, ctor: Symbol): Map[Symbol, Value] = {
      val paramAccessors = ctor.owner.constrParamAccessors;
      var values = in.stack.take(1 + ctor.info.paramTypes.length).reverse.drop(1);
      val bindings = new HashMap[Symbol, Value];

      // this relies on having the same order in paramAccessors and
      // the arguments on the stack. It should be the same!
      for (val p <- paramAccessors) {
        bindings += p -> values.head;
        values = values.tail;
      }

      bindings
    }

    /** Is `cls' a closure class? */
    final def isClosureClass(cls: Symbol): Boolean =
        cls.isFinal &&
        cls.tpe.parents.exists { t =>
          val TypeRef(_, sym, _) = t;
          definitions.FunctionClass exists sym.==
        }

    /** Is `m' a pure method? */
    final def isPureMethod(m: Symbol): Boolean =
      m.isGetter;

    final override def toString(): String = {
      var res = "";
      for (val b <- this.method.code.blocks.toList)
        res = (res + "\nIN(" + b.label + "):\t Bindings: " + in(b).bindings +
               "\nIN(" + b.label +"):\t Stack: " + in(b).stack) + "\n";
      res
    }

  } /* class CopyAnalysis */
}