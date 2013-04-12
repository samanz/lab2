package dos.lab2
import scala.actors.Actor
import scala.actors.Actor._
import scala.actors.remote._
import scala.actors.remote.RemoteActor._
import scala.collection.mutable.{ ArrayBuffer, HashMap }
import scala.actors.AbstractActor
import scala.util.Random

/**
 * Actor message: Request from pig to join the P2P network. Will send information to pig so that it can join.
 * @param computer The hostname the computer is connecting from
 */
case class Connect(computer: String)
/**
 * Actor message: Request from pig to join the P2P network. Will send information to pig so that it can join.
 * @param computer The hostname the computer is connecting from
 */
case class Connected

/**
 * Master class. Is an Actor whose job it is to help construct the P2P and send initial state of game.
 * Master also sends messages between the game and P2P network when appropriate
 */
class Master extends Actor {
  /** The config information of all the pigs in the network **/
  val connections = ArrayBuffer[PigConfig]()
  /** The connections to all the pigs in the network mapped by their pigIds **/
  val pigToCon = HashMap[Int, AbstractActor]()
  /** The number of connections connected to **/
  var upConnections = 0
  /** The number of pigs ready to play **/
  var readyPigs = 0
  /** Reference to the current Game **/
  var game: Game = null
  val rand = new Random
  /** Is the game ready to start, all pigs are connected to the network **/
  def ready: Boolean = (readyPigs == Config.N)

  /**
   * Get a pig configuration from the configurations based on a hostname
   *
   * @param address A hostname present in the configuration file
   */
  def getPig(address: String): PigConfig = {
    assert(Config.computers.contains(address))
    val computer = Config.computers(address)
    val pig = computer.pigs.filter(x => !x.connected).head
    pig.connected = true
    connections += pig
    pig.idNumber = connections.size
    pig
  }

  /** Gets the previous pig configuration file to send to connecting pig so that they can communicate **/
  def getPrevious(): PigConfig = {
    if (connections.size == 0) new PigConfig("", "", 0)
    else connections.last
  }

  /**
   * Determines if the hit location will hit any pigs
   *
   * @param location The hit location of the bird
   * @return Array of locations that the bird will cause pigs to die
   */
  def affected(location: Int): Array[Int] = {
    val stone = Config.N + 1
    if (location >= 0 && location < game.board.size) {
      if (game.board(location) == stone) {
        if (location == game.landing)
          return affected(location + 1) ++ affected(location - 1)
        else if (location > game.landing)
          return affected(location + 1)
        else
          return affected(location - 1)
      } else if (game.finalBoard.values.toSet.contains(location)) {
        if (game.landing > location) return Array(location) ++ affected(location - 1)
        else return Array(location) ++ affected(location + 1)
      } else return Array()
    } else Array()
  }

  def act() {
    alive(Config.master.port)
    register(Symbol(Config.master.address), self)
    loop {
      react {
        case Connect(computer) => {
          println("Connection Requested from " + computer)
          val p = getPig(computer)
          sender ! (p, upConnections)
        }
        case Connected => {
          readyPigs += 1
        }
        case Ready => {
          upConnections += 1
          if (upConnections == Config.N) {
            println("All systems go!")
            for (con <- connections) {
              pigToCon(con.idNumber) = select(Node(con.address, con.port), Symbol(con.name))
            }
            for(p <- pigToCon.values) p ! ConnectToAll(connections.toList)
          }
        }
        case SendGame(board, round) => {
          for (piggy <- pigToCon.values)
            piggy ! SendGame(board, round)
        }
        case Where => {
          sender ! game.landing
        }
        case Done(numHit) => {
          game.success = numHit
          game.done = true
        }
      }
    }
  }
}

object Master {
  def main(args: Array[String]) {
    RemoteActor.classLoader = getClass().getClassLoader()
    if (args.length < 1) {
      println("Usage: Master [config file]")
      System.exit(1)
    }
    Config.fromFile(args(0))
    val master = new Master()
    master.start()
  }
}