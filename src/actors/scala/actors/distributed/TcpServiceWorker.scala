/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2006, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.actors.distributed

import java.io._
import java.net.Socket

/**
 * @author Philipp Haller
 */
class TcpServiceWorker(parent: TcpService, so: Socket) extends Thread {
  val in = so.getInputStream()
  val out = so.getOutputStream()

  val datain = new DataInputStream(in)
  val dataout = new DataOutputStream(out)

  val reader = new BufferedReader(new InputStreamReader(in))
  val writer = new PrintWriter(new OutputStreamWriter(out))

  val log = new Debug("TcpServiceWorker")
  log.level = 2

  def transmit(msg: Send): Unit = synchronized {
    val data = parent.serializer.serialize(msg)
    transmit(data)
  }

  def transmit(data: String): Unit = synchronized {
    log.info("Transmitting " + data)
    writer.write(data)
    writer.flush()
  }

  def transmit(data: Array[byte]): Unit = synchronized {
    log.info("Transmitting " + data)
    dataout.writeInt(data.length)
    dataout.write(data)
    dataout.flush()
  }

  def sendNode = {
    Console.println("Sending our name " + parent.node)
    parent.serializer.writeObject(dataout, parent.node)
  }

  var connectedNode: TcpNode = _

  def readNode = {
    Console.println("" + parent.node + ": Reading node name...")
    //val node = parent.serializer.deserialize(reader)
    val node = parent.serializer.readObject(datain)
    Console.println("Connection from " + node)
    node match {
      case n: TcpNode => {
        connectedNode = n
        Console.println("Adding connection to " + node + " to table.")
        parent.addConnection(n, this)
      }
    }
  }

  var running = true
  def halt = synchronized {
    so.close() // close socket
    running = false // stop
  }

  override def run(): Unit = {
    try {
      while (running) {
        if (in.available() > 0) {
          log.info("deserializing...");
          //val msg = parent.serializer.deserialize(reader);
          val msg = parent.serializer.readObject(datain);
          log.info("Received object: " + msg);
          parent.kernel.processMsg(msg)
        }
      }
    }
    catch {
      case ioe: IOException =>
        Console.println("" + ioe + " while reading from socket.");
        parent nodeDown connectedNode
      case e: Exception =>
        // catch-all
        Console.println("" + e + " while reading from socket.");
        parent nodeDown connectedNode
    }
  }
}