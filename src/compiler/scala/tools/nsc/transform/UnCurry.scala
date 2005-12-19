/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author
 */
// $Id$
package scala.tools.nsc.transform;

import symtab.Flags._;

/*<export>*/
/** - uncurry all symbol and tree types (@see UnCurryPhase)
 *  - for every curried parameter list:  (ps_1) ... (ps_n) ==> (ps_1, ..., ps_n)
 *  - for every curried application: f(args_1)...(args_n) ==> f(args_1, ..., args_n)
 *  - for every type application: f[Ts] ==> f[Ts]() unless followed by parameters
 *  - for every use of a parameterless function: f ==> f()  and  q.f ==> q.f()
 *  - for every def-parameter:  x: => T ==> x: () => T
 *  - for every use of a def-parameter: x ==> x.apply()
 *  - for every argument to a def parameter `x: => T':
 *      if argument is not a reference to a def parameter:
 *        convert argument `e' to (expansion of) `() => e'
 *  - for every repated parameter `x: T*' --> x: Seq[a].
 *  - for every argument list that corresponds to a repeated parameter
 *       (a_1, ..., a_n) => (Seq(a_1, ..., a_n))
 *  - for every argument list that is an escaped sequence
 *       (a_1:_*) => (a_1)g
 *  - convert implicit method types to method types
 *  - convert non-trivial catches in try statements to matches
 */
/*</export>*/
abstract class UnCurry extends InfoTransform {
  import global._;                  // the global environment
  import definitions._;             // standard classes and methods
  import typer.{typed};             // methods to type trees
  import posAssigner.atPos;         // for filling in tree positions

  val phaseName: String = "uncurry";
  def newTransformer(unit: CompilationUnit): Transformer = new UnCurryTransformer(unit);
  override def changesBaseClasses = false;

  private val uncurry = new TypeMap {
    def apply(tp: Type): Type = tp match {
      case MethodType(formals, MethodType(formals1, restpe)) =>
	apply(MethodType(formals ::: formals1, restpe))
      case mt: ImplicitMethodType =>
	apply(MethodType(mt.paramTypes, mt.resultType))
      case PolyType(List(), restpe) =>
	apply(MethodType(List(), restpe))
      case PolyType(tparams, restpe) =>
	PolyType(tparams, apply(MethodType(List(), restpe)))
      case TypeRef(pre, sym, List(arg)) if (sym == ByNameParamClass) =>
	apply(functionType(List(), arg))
      case TypeRef(pre, sym, args) if (sym == RepeatedParamClass) =>
	apply(rawTypeRef(pre, SeqClass, args));
      case _ =>
	mapOver(tp)
    }
  }

  /** - return symbol's transformed type,
   *  - if symbol is a def parameter with transformed type T, return () => T
   */
  def transformInfo(sym: Symbol, tp: Type): Type =
    if (sym.isType) tp
    else uncurry(tp);

  class UnCurryTransformer(unit: CompilationUnit) extends Transformer {

    private var needTryLift = false;
    private var inPattern = false;
    private var inConstructorFlag = 0L;

    override def transform(tree: Tree): Tree = try { //debug
      postTransform(mainTransform(tree));
    } catch {
      case ex: Throwable =>
	System.out.println("exception when traversing " + tree);
      throw ex
    }

    /* Is tree a reference `x' to a call by name parameter that neeeds to be converted to
     * x.apply()? Note that this is not the case if `x' is used as an argument to another
     * call by name parameter.
     */
    def isByNameRef(tree: Tree): boolean = (
      tree.isTerm && tree.hasSymbol &&
      tree.symbol.tpe.symbol == ByNameParamClass && tree.tpe == tree.symbol.tpe.typeArgs.head
    );

    /** Uncurry a type of a tree node.
     *  This function is sensitive to whether or not we are in a pattern -- when in a pattern
     *  additional parameter sections of a case class are skipped.
     */
    def uncurryTreeType(tp: Type): Type = tp match {
      case MethodType(formals, MethodType(formals1, restpe)) if (inPattern) =>
	uncurryTreeType(MethodType(formals, restpe))
      case _ =>
	uncurry(tp)
    }

    /*  Transform a function node (x_1,...,x_n) => body of type FunctionN[T_1, .., T_N, R] to
     *
     *    class $anon() extends Object() with FunctionN[T_1, .., T_N, R] with ScalaObject {
     *      def apply(x_1: T_1, ..., x_N: T_n): R = body
     *    }
     *    new $anon()
     *
     *  transform a function node (x => body) of type PartialFunction[T, R] where
     *    body = x match { case P_i if G_i => E_i }_i=1..n
     *  to:
     *
     *    class $anon() extends Object() with PartialFunction[T, R] with ScalaObject {
     *      def apply(x: T): R = body;
     *      def isDefinedAt(x: T): boolean = x match {
     *        case P_1 if G_1 => true
     *        ...
     *        case P_n if G_n => true
     *        case _ => false
     *      }
     *    }
     *    new $anon()
     *
     *  However, if one of the patterns P_i if G_i is a default pattern, generate instead
     *
     *      def isDefinedAt(x: T): boolean = true
     */
    def transformFunction(fun: Function): Tree = {
      val anonClass = fun.symbol.owner.newAnonymousFunctionClass(fun.pos)
        .setFlag(FINAL | SYNTHETIC | inConstructorFlag);
      val formals = fun.tpe.typeArgs.init;
      val restpe = fun.tpe.typeArgs.last;
      anonClass setInfo ClassInfoType(
	List(ObjectClass.tpe, fun.tpe, ScalaObjectClass.tpe), new Scope(), anonClass);
      val applyMethod = anonClass.newMethod(fun.pos, nme.apply)
	.setFlag(FINAL).setInfo(MethodType(formals, restpe));
      anonClass.info.decls enter applyMethod;
      for (val vparam <- fun.vparams) vparam.symbol.owner = applyMethod;
      new ChangeOwnerTraverser(fun.symbol, applyMethod).traverse(fun.body);
      var members = List(
	DefDef(Modifiers(FINAL), nme.apply, List(), List(fun.vparams), TypeTree(restpe), fun.body)
	  setSymbol applyMethod);
      if (fun.tpe.symbol == PartialFunctionClass) {
	val isDefinedAtMethod = anonClass.newMethod(fun.pos, nme.isDefinedAt)
	  .setFlag(FINAL).setInfo(MethodType(formals, BooleanClass.tpe));
	anonClass.info.decls enter isDefinedAtMethod;
	def idbody(idparam: Symbol) = fun.body match {
	  case Match(_, cases) =>
	    val substParam = new TreeSymSubstituter(List(fun.vparams.head.symbol), List(idparam));
	    def transformCase(cdef: CaseDef): CaseDef =
	      resetAttrs(CaseDef(cdef.pat.duplicate, cdef.guard.duplicate, Literal(true)));
	    if (cases exists treeInfo.isDefaultCase) Literal(true)
	    else
              Match(
	        Ident(idparam),
	        (cases map transformCase) :::
		   List(CaseDef(Ident(nme.WILDCARD), EmptyTree, Literal(false))))
	}
	members = DefDef(isDefinedAtMethod, vparamss => idbody(vparamss.head.head)) :: members;
      }
      typer.atOwner(currentOwner).typed {
	atPos(fun.pos) {
	  Block(
	    List(ClassDef(anonClass, List(List()), List(List()), members)),
	    Typed(
	      New(TypeTree(anonClass.tpe), List(List())),
	      TypeTree(fun.tpe)))
        }
      }
    }

    def transformArgs(pos: int, args: List[Tree], formals: List[Type]) = {
      if (formals.isEmpty) {
	assert(args.isEmpty); List()
      } else {
	val args1 =
          formals.last match {
            case TypeRef(pre, sym, List(elempt)) if (sym == RepeatedParamClass) =>
	      def mkSequence(args: List[Tree]) =
                atPos(pos)(ArrayValue(TypeTree(elempt), args) setType formals.last);
	      if (args.isEmpty) List(mkSequence(args))
	      else {
	        val suffix = args.last match {
		  case Typed(arg, Ident(name)) if name == nme.WILDCARD_STAR.toTypeName =>
		    arg setType seqType(arg.tpe)
		  case _ =>
		    mkSequence(args.drop(formals.length - 1))
	        }
	        args.take(formals.length - 1) ::: List(suffix)
	      }
            case _ => args
          }
	List.map2(formals, args1) ((formal, arg) =>
	  if (formal.symbol != ByNameParamClass) arg
	  else if (isByNameRef(arg)) arg setType functionType(List(), arg.tpe)
	  else {
            val fun = typer.atOwner(currentOwner).typed(
              Function(List(), arg) setPos arg.pos).asInstanceOf[Function];
            new ChangeOwnerTraverser(currentOwner, fun.symbol).traverse(arg);
            transformFunction(fun)
          })
      }
    }

    def mainTransform(tree: Tree): Tree = {

      def withNeedLift(needLift: Boolean)(f: => Tree): Tree = {
        val savedNeedTryLift = needTryLift;
        needTryLift = needLift;
        val t = f;
        needTryLift = savedNeedTryLift;
        t
      }

      def withInConstructorFlag(inConstructorFlag: long)(f: => Tree): Tree = {
        val savedInConstructorFlag = this.inConstructorFlag;
        this.inConstructorFlag = inConstructorFlag;
        val t = f;
        this.inConstructorFlag = savedInConstructorFlag;
        t
      }

      tree match {
        case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
          withNeedLift(false) {
            if (tree.symbol.isConstructor) {
              atOwner(tree.symbol) {
                val rhs1 = rhs match {
                  case Block(stat :: stats, expr) =>
                    copy.Block(
                      rhs,
                      withInConstructorFlag(INCONSTRUCTOR) { transform(stat) } :: transformTrees(stats),
                      transform(expr));
                  case _ =>
                    withInConstructorFlag(INCONSTRUCTOR) { transform(rhs) }
                }
                copy.DefDef(
	          tree, mods, name, transformAbsTypeDefs(tparams),
                  transformValDefss(vparamss), transform(tpt), rhs1)
              }
            } else {
              super.transform(tree)
            }
          }

        case ValDef(_, _, _, rhs)
          if (!tree.symbol.owner.isSourceMethod) =>
            withNeedLift(true) { super.transform(tree) }


        case Apply(Select(Block(List(), Function(vparams, body)), nme.apply), args) =>
	  // perform beta-reduction; this helps keep view applications small
          withNeedLift(true) {
	    mainTransform(new TreeSubstituter(vparams map (.symbol), args).transform(body))
          }

        case Apply(fn, args) =>
          if (settings.noassertions.value &&
              fn.symbol != null &&
              (fn.symbol.name == nme.assert_ || fn.symbol.name == nme.assume_) &&
              fn.symbol.owner == PredefModule.moduleClass) {
            Literal(()).setPos(tree.pos).setType(UnitClass.tpe)
          } else {
            withNeedLift(true) {
	      val formals = fn.tpe.paramTypes;
              copy.Apply(tree, transform(fn), transformTrees(transformArgs(tree.pos, args, formals)))
            }
          }

        case Assign(Select(_, _), _) =>
          withNeedLift(true) { super.transform(tree) }

        case Try(block, catches, finalizer) =>
          if (needTryLift) {
            if (settings.debug.value)
              log("lifting try at: " + unit.position(tree.pos));

            val sym = currentOwner.newMethod(tree.pos, unit.fresh.newName("liftedTry"));
            sym.setInfo(MethodType(List(), tree.tpe));
            new ChangeOwnerTraverser(currentOwner, sym).traverse(tree);

            transform(typed(atPos(tree.pos)(
              Block(List(DefDef(sym, List(List()), tree)),
                    Apply(Ident(sym), Nil)))))
          } else
            super.transform(tree)

        case CaseDef(pat, guard, body) =>
          inPattern = true;
	  val pat1 = transform(pat);
	  inPattern = false;
	  copy.CaseDef(tree, pat1, transform(guard), transform(body))

        case fun @ Function(_, _) =>
          mainTransform(transformFunction(fun))

        case Template(_, _) =>
          withInConstructorFlag(0) { super.transform(tree) }

        case _ =>
          val tree1 = super.transform(tree);
	  if (isByNameRef(tree1))
	    typed(atPos(tree1.pos)(
	      Apply(Select(tree1 setType functionType(List(), tree1.tpe), nme.apply), List())))
	  else tree1;
      }
    } setType uncurryTreeType(tree.tpe);

    def postTransform(tree: Tree): Tree = atPhase(phase.next) {
      def applyUnary(tree: Tree): Tree =
        if (tree.symbol.isMethod && (!tree.tpe.isInstanceOf[PolyType] || tree.tpe.typeParams.isEmpty)) {
	  if (!tree.tpe.isInstanceOf[MethodType]) tree.tpe = MethodType(List(), tree.tpe);
	  atPos(tree.pos)(Apply(tree, List()) setType tree.tpe.resultType)
	} else if (tree.isType && !tree.isInstanceOf[TypeTree]) {
	  TypeTree(tree.tpe) setPos tree.pos
	} else {
	  tree
	}
      tree match {
        case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
          copy.DefDef(tree, mods, name, tparams, List(List.flatten(vparamss)), tpt, rhs);
        case Try(body, catches, finalizer) =>
          if (catches forall treeInfo.isCatchCase) tree
          else {
            val exname = unit.fresh.newName("ex$");
            val cases =
              if (catches exists treeInfo.isDefaultCase) catches
              else catches ::: List(CaseDef(Ident(nme.WILDCARD), EmptyTree, Throw(Ident(exname))));
            val catchall =
              atPos(tree.pos) {
                CaseDef(
                  Bind(exname, Ident(nme.WILDCARD)),
                  EmptyTree,
                  Match(Ident(exname), cases))
              }
            if (settings.debug.value) log("rewrote try: " + catches + " ==> " + catchall);
            val catches1 = typer.atOwner(currentOwner).typedCases(
              tree, List(catchall), ThrowableClass.tpe, WildcardType);
            copy.Try(tree, body, catches1, finalizer)
          }
	case Apply(Apply(fn, args), args1) =>
	  copy.Apply(tree, fn, args ::: args1)
	case Ident(name) =>
	  if (name == nme.WILDCARD_STAR.toTypeName)
	    unit.error(tree.pos, " argument does not correspond to `*'-parameter");
	  applyUnary(tree);
	case Select(_, _) =>
	  applyUnary(tree)
	case TypeApply(_, _) =>
	  applyUnary(tree)
	case _ =>
	  tree
      }
    }
  }

  private val resetAttrs = new Traverser {
    override def traverse(tree: Tree): unit = tree match {
      case EmptyTree | TypeTree() =>
	;
      case _ =>
	if (tree.hasSymbol) tree.symbol = NoSymbol;
	tree.tpe = null;
	super.traverse(tree)
    }
  }
}