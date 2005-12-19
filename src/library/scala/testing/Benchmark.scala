/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2004, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */


/**
 * Scala benchmarking mini framework.
 */

package scala.testing;

/** <code>Benchmark</code> can be used to quickly turn an existing
 *  class into a benchmark. Here is a short example:
 *
 *  <pre>
 *  object sort1 extends Sorter with Benchmark {
 *     def run = sort(List.range(1, 1000));
 *  }
 *  </pre>
 *
 * The run method has to be defined by the user, who will perform
 * the timed operation there.
 * Run the benchmark as follows:
 * <pre>
 *   scala sort1 5 times.log
 * </pre>
 * This will run the benchmark 5 times and log the execution times in
 * a file called times.log
 */
trait Benchmark {

  /** this method should be implemented by the concrete benchmark */
  def run: Unit;

  /** Run the benchmark the specified number of times
   *  and return a list with the execution times in milliseconds
   *  in reverse order of the execution */
  def runBenchmark(noTimes: Int): List[Long] =

    for (val i <- List.range(1, noTimes + 1)) yield {
      val startTime = System.currentTimeMillis();
      run;
      val stopTime = System.currentTimeMillis();
      System.gc();

      stopTime - startTime
    }

  /**
  * The entry point. It takes two arguments: the number of
  * consecutive runs, and the name of a log file where to
  * append the times.
  *
  */
  def main(args: Array[String]): Unit = {
    if (args.length > 1) {
      val logFile = new java.io.FileWriter(args(1), true); // append, not overwrite

      logFile.write(getClass().getName());
      for (val t <- runBenchmark(Integer.parseInt(args(0))))
	logFile.write("\t\t" + t);

      logFile.write("\n");
      logFile.flush();
    } else
      Console.println("Usage: scala benchmarks.program <runs> <logfile>");
  }
}
