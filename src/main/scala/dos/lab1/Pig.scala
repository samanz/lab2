package dos.lab1
import scala.actors.Actor
import scala.actors.AbstractActor
import scala.actors.Actor._
import scala.actors.remote._
import scala.actors.remote.RemoteActor._
import java.util.UUID
import scala.collection.mutable.{ ArrayBuffer, HashMap }

/** Actor message: Tells master that it is ready to be connected to **/
case object Ready
/** Actor message: Asks the master to help get information about estimated location of bird hitting **/
case object Where
/**
 * Actor message: requests neighbor to connect back to current pig to create bidirectional ring
 *
 * @param pig The configuration information so the pig can connect to the current actor
 */
case class ForwardConnect(pig: PigConfig)
/**
 * Actor message: floods information to P2P network with information about estimated location bird lands
 *
 * @param position The location the bird might land
 * @param messageId The UUID of the message
 * @param hopcount The number of hops allowed left to be made in the p2p network
 */
case class BirdApproaching(position: Int, messageId: UUID, hopcount: Int)
/**
 * Actor message: floods information to P2P message indicating a certain pig should move away from bird
 *
 * @param pigId The id of the pig that will recieve the message
 * @param loc The location that the pig should move away from
 * @param messageId The UUID of the message
 */
case class TakeShelter(pigId: Int, loc: Int, messageId: UUID)
/**
 * Actor message: floods the P2P network with requesting the status from all the pigs
 *
 * @param hopcount The number of hops allowed left to be made in the p2p network
 * @param trav The ids indicating the traversal of the message through the p2p network so it can be reversed by the reply message
 * @param messageId The UUID of the message
 */
case class StatusAll(hopcount: Int, trav: Array[Int], messageId: UUID)
/**
 * Actor message: Replies to StatusAll with information about the status of the pigs
 *
 * @param pigId The id of the pig replying
 * @param status The hit status of the pig replying
 * @param trav The current level of the reverse traversal through the P2P network
 */
case class WasHit(pigId: Int, status: Boolean, trav: Array[Int])
/**
 * Actor message: Tell the master that the round has ended and the amount of pigs that were hit in the round
 *
 * @param numHit The number of pigs hit during round
 */
case class Done(numHit: Int)
/**
 * Actor message: Tell the pigs that all the other pigs are up and ready and to connect to them
 *
 * @param pigs The pigs configuration information for pigs that are connected to the network
 */
case class ConnectToAll(pigs : Array[PigConfig])
/**
 * A Pig class. This class is a scala remote actor that sends information on the pig-to-pig network to avoid being hit by birds sent by game.
 *
 * @constructor create a new pig, connect to the master, and try connect to the pig before it on the ring.
 * @param computer the hostname of the computer the process is started on
 */
class Pig(val computer: String) extends Actor {
  /** The current game state as seen by an individual pig **/
  var pigBoard = new Array[Int](Config.game.size)
  /** The configuration information of the pig before in the ring **/
  var pConfig: PigConfig = null
  /** The configuration information of the pig after in the ring **/
  var nConfig: PigConfig = null
  /** The configuration information of the current pig **/
  var me: PigConfig = null
  /** The number that is a stone column on the map **/
  val stone = Config.N + 1
  /** Current location of the pig on game map **/
  var myLocation = 0
  /** Contains unique IDs of messages seen already on the network **/
  val seenMessages = new HashMap[UUID, Boolean]()
  /** Stores the information of if a pig already took shelter **/
  var sheltered = false
  /** Stores the information of if a pig has been hit by a bird **/
  var statusHit = false
  /** Stores the information of if a pig is the first one on the map **/
  var isFirst = false
  /** The location the bird is actually going to hit **/
  var landedLoc: Int = 0
  /** The number of pigs hit, used by first pig to update master about state. **/
  var numHit = 0
  /** The number of statusAll responses the first pig has seen **/
  var responses = 0
  /** The information about other pigs **/
  val otherPigs = new ArrayBuffer[Pigs]()
  /** The connections to other pigs **/
  val connections = new ArrayBuffer[AbstractActor]()

  /**
   * Determines if the estimated location of bird hitting will lead to this pig being the primary one hit
   *
   * @param location The location that the bird is estimated hitting
   * @return boolean true if pig is primary pig to be hit by estimated bird
   */
  def simpleWillHitMe(location: Int): Boolean = {
    if (location < 0 || location >= pigBoard.size) return false
    if (location == myLocation) return true
    var m = location
    while (m >= 1 && (pigBoard(m) == stone)) m -= 1
    if (m == myLocation) return true
    else if (pigBoard(m) > 0 && pigBoard(m) < stone) return false

    m = location
    while (m < pigBoard.size && (pigBoard(m) == stone)) m += 1
    (m == myLocation)
  }

  /**
   * Will both move the pig to safety based on the estimated location of bird landing, and gather information about neighbors that should be told to move
   *
   * @param location The location that the bird is estimated hitting
   * @return array of neighbor ids that should be told to move
   */
  def moveToSafety(location: Int): Array[Int] = {
    val neighbors = ArrayBuffer[Int]()
    var ns = ArrayBuffer[Int]()
    var l = myLocation
    if (myLocation - 1 >= 0 && pigBoard(myLocation - 1) > 0 && pigBoard(myLocation - 1) < stone) ns += pigBoard(myLocation - 1)
    if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) > 0 && pigBoard(myLocation + 1) < stone) ns += pigBoard(myLocation + 1)
    while (l >= 0 && pigBoard(l) > 0) {
      if (pigBoard(l) != stone) ns += pigBoard(l)
      l -= 1
    }
    l = myLocation
    while (l < pigBoard.size && pigBoard(l) > 0) {
      if (pigBoard(l) != stone) ns += pigBoard(l)
      l += 1
    }
    if (location < myLocation) {
      if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) != stone) myLocation += 1
    } else if (location > myLocation) {
      if (myLocation - 1 > 0 && pigBoard(myLocation - 1) != stone) myLocation -= 1
    } else {
      if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) != stone) myLocation += 1
      else if (myLocation - 1 > 0 && pigBoard(myLocation - 1) != stone) myLocation -= 1
    }
    ns.toSet.toArray
  }

  def act() {
    val master = select(Node(Config.master.address, Config.master.port), Symbol(Config.master.address))
    val p: PigConfig = (master !? Connect(computer))
    me = p
    println("Pig: " + me.name + " reporting for duty on " + computer + " at port: " + me.port)
    alive(me.port)
    register(Symbol(me.name), self)
    master ! Ready
    loop {
      react {
        case ConnectToAll(pigs) {
          pigs.filter( _ != me).foreach{ otherPigs += pig; connections += select(Node(pig.address, pig.port), Symbol(pig.name); }
          master ! Connected
        }
        case Hit(landing) => {
          println("I know where it hit!")
          sender ! (me.idNumber, myLocation)
          landedLoc = landing
        }
        case BirdApproaching(location, messageId, hopcount) => {
          if (!seenMessages.contains(messageId)) {
            println("accepting BirdApproaching message")
            seenMessages(messageId) = true
            if (simpleWillHitMe(location)) {
              println("It will probably hit me! Taking evasive action.")
              val pigsToMove = moveToSafety(location)
              for (p <- pigsToMove) {
                println("Sending TakeShelter")
                val messageId = UUID.randomUUID
                seenMessages(messageId) = true
                Actor.actor {
                  Thread.sleep(Config.game.messageDelay)
                  neighbor ! TakeShelter(p, location, messageId)
                  next ! TakeShelter(p, location, messageId)
                }
              }
            }
            if (hopcount > 1) {
              println("Proprogating BirdApproaching")
              Actor.actor {
                Thread.sleep(Config.game.messageDelay)
                neighbor ! BirdApproaching(location, messageId, hopcount - 1)
                next ! BirdApproaching(location, messageId, hopcount - 1)
              }
            }
          }
        }
        case TakeShelter(pigId, loc, messageId) => {
          if (!seenMessages.contains(messageId)) {
            seenMessages(messageId) = true
            if (me.idNumber == pigId && sheltered == false) {
              println("accepting TakeShelter message, attempting to take evasive action.")
              sheltered = true
              if (loc < myLocation) {
                if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) != stone) myLocation += 1
              } else if (loc > myLocation) {
                if (myLocation - 1 > 0 && pigBoard(myLocation - 1) != stone) myLocation -= 1
              } else {
                if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) != stone) myLocation += 1
                else if (myLocation - 1 > 0 && pigBoard(myLocation - 1) != stone) myLocation -= 1
              }
            } else {
              Actor.actor {
                Thread.sleep(Config.game.messageDelay)
                neighbor ! TakeShelter(pigId, loc, messageId)
                next ! TakeShelter(pigId, loc, messageId)
              }
            }
          }
        }
        case Final(status) => {
          println("And the final word is?")
          statusHit = status //wasIHit()
          if (statusHit) println("Im hit!") else println("I'm safe!")
          if (isFirst) {
            val messageId = UUID.randomUUID
            seenMessages(messageId) = true
            println("Querying statusAll")
            Actor.actor {
              Thread.sleep(Config.game.messageDelay)
              neighbor ! StatusAll(Config.N / 2 + 1, Array(me.idNumber), messageId)
              next ! StatusAll(Config.N / 2 + 1, Array(me.idNumber), messageId)
            }
          }
        }
        case WasHit(pigId, status, trav) => {
          println("Got Was Hit message with trav: " + trav.mkString(","))
          if (trav.length == 0) {
            if (status) numHit += 1
            responses += 1
            if (responses == Config.N - 1) {
              if (statusHit) numHit += 1
              master ! Done(numHit)
            }
          } else {
            if (pConfig.idNumber == trav.last) {
              Actor.actor {
                Thread.sleep(Config.game.messageDelay)
                neighbor ! WasHit(pigId, status, trav.take(trav.length - 1))
              }
            }
            if (nConfig.idNumber == trav.last) {
              Actor.actor {
                Thread.sleep(Config.game.messageDelay)
                next ! WasHit(pigId, status, trav.take(trav.length - 1))
              }
            }
          }
        }
        case StatusAll(hopcount, trav, messageId) => {
          println("Got StatusAll message: with trav " + trav.mkString(","))
          if (!seenMessages.contains(messageId)) {
            println("accepting StatusAll message")
            seenMessages(messageId) = true
            if (pConfig.idNumber == trav.last) {
              Actor.actor {
                Thread.sleep(Config.game.messageDelay)
                neighbor ! WasHit(me.idNumber, statusHit, trav.take(trav.length - 1))
              }
            }
            if (nConfig.idNumber == trav.last) {
              Actor.actor {
                Thread.sleep(Config.game.messageDelay)
                next ! WasHit(me.idNumber, statusHit, trav.take(trav.length - 1))
              }
            }
            Actor.actor {
              Thread.sleep(Config.game.messageDelay)
              neighbor ! StatusAll(hopcount - 1, trav ++ Array(me.idNumber), messageId)
              next ! StatusAll(hopcount - 1, trav ++ Array(me.idNumber), messageId)
            }
          }
        }
        case SendGame(board) => {
          sheltered = false
          statusHit = false
          pigBoard = board
          landedLoc = 0
          Game.printBoard(pigBoard)
          var firstPig = 0
          while (pigBoard(firstPig) == 0 || pigBoard(firstPig) == stone) firstPig += 1
          myLocation = 0
          numHit = 0
          responses = 0
          while (pigBoard(myLocation) != me.idNumber) myLocation += 1
          isFirst = me.idNumber == pigBoard(firstPig)
          if (me.idNumber == pigBoard(firstPig)) {
            println("I'm the closest pig to the launch pad! Secret information gathering session commencing!")
            var hitLocation: Int = (master !? Where).toString.toInt
            println("The probable hit location: " + hitLocation)
            println("Proprogating")
            val messageId = UUID.randomUUID
            //seenMessages(messageId) = true
            Actor.actor {
              Thread.sleep(Config.game.messageDelay)
              neighbor ! BirdApproaching(hitLocation, messageId, Config.N / 2 + 1)
              next ! BirdApproaching(hitLocation, messageId, Config.N / 2 + 1)
            }
          }
        }
      }
    }
  }
}

object Pig {
  def main(args: Array[String]) {
    Config.fromFile(args(0))
    RemoteActor.classLoader = getClass().getClassLoader()
    val pig = new Pig(args(1))
    pig.start()
  }
}