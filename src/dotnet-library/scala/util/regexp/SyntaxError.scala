/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2006, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$


package scala.util.regexp

/** This runtime exception is thrown if an attempt to instantiate a
 *  syntactically incorrect expression is detected.
 *
 *  @author  Burak Emir
 *  @version 1.0
 */
class SyntaxError(e: String) extends java.lang.RuntimeException(e)