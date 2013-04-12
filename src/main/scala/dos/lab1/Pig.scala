package dos.lab2
import scala.actors.Actor
import scala.actors.AbstractActor
import scala.actors.Actor._
import scala.actors.remote._
import scala.actors.remote.RemoteActor._
import java.util.{Random, UUID}
import scala.collection.mutable.{ ArrayBuffer, HashMap }

/** Actor message: Tells master that it is ready to be connected to **/
case object Ready
/** Actor message: Asks the master to help get information about estimated location of bird hitting **/
case object Where
/**
 * Actor message: floods information to P2P network with information about estimated location bird lands
 *
 * @param position The location the bird might land
 * @param messageId The UUID of the message
 * @param hopcount The number of hops allowed left to be made in the p2p network
 */
case class BirdApproaching(location : Int, l : Int)
/**
 * Actor message: floods information to P2P network with information about estimated location bird lands
 *
 * @param position The location the bird might land
 * @param messageId The UUID of the message
 * @param hopcount The number of hops allowed left to be made in the p2p network
 */
case class Election(ids : List[Int], round : Int, l : Int)
/**
 * Actor message: floods information to P2P network with information about estimated location bird lands
 *
 * @param position The location the bird might land
 * @param messageId The UUID of the message
 * @param hopcount The number of hops allowed left to be made in the p2p network
 */
case class Leader(id : Int, round : Int, l : Int)
/**
 * Actor message: floods the P2P network with requesting the status from all the pigs
 *
 * @param hopcount The number of hops allowed left to be made in the p2p network
 * @param trav The ids indicating the traversal of the message through the p2p network so it can be reversed by the reply message
 * @param messageId The UUID of the message
 */
case class StatusAll(l : Int)
/**
 * Actor message: Tell the master that the round has ended and the amount of pigs that were hit in the round
 *
 * @param numHit The number of pigs hit during round
 */
case class Done(numHit: Int)

case class Inform(leadme: String, l : Int)

/**
 * Actor message: Tell the pigs that all the other pigs are up and ready and to connect to them
 *
 * @param pigs The pigs configuration information for pigs that are connected to the network
 */
case class ConnectToAll(pigs : List[PigConfig])
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
  val otherPigs = new ArrayBuffer[PigConfig]()
  /** The connections to other pigs **/
  val connections = new HashMap[String, AbstractActor]()
  /** Next pig in the network **/
  var nextPig : AbstractActor  = null
  /** The leader election id of the pig **/
  var electionId : Int = -1
  /** lamport **/
  var lamport = 0
  /** Current leader is the lord **/
  var lord = ""
  /** The round number **/
  var round = 0
  /** The round number **/
  var leadRound = 0
  /** Time we moved! **/
  var movedTime = 0
  /** This pig needs to evade **/
  var evading = false
  /** random **/
  val rand = new Random

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
  def moveToSafety(location: Int) {
    var l = myLocation
    if (location < myLocation) {
      if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) != stone) myLocation += 1
    } else if (location > myLocation) {
      if (myLocation - 1 > 0 && pigBoard(myLocation - 1) != stone) myLocation -= 1
    } else {
      if (myLocation + 1 < pigBoard.size && pigBoard(myLocation + 1) != stone) myLocation += 1
      else if (myLocation - 1 > 0 && pigBoard(myLocation - 1) != stone) myLocation -= 1
    }
  }

  def act() {
    val master = select(Node(Config.master.address, Config.master.port), Symbol(Config.master.address))
    var (p: PigConfig, id : Int) = (master !? Connect(computer))
    me = p
    println("Pig: " + me.name + " reporting for duty on " + computer + " at port: " + me.port)
    alive(me.port)
    register(Symbol(me.name), self)
    master ! Ready
    loop {
      react {
        case ConnectToAll(pigs) => {
          var sawMe = false
          id = 0
          for(p <- pigs) {
            id += 1
            if(p.name == me.name) {
              sawMe = true
              electionId = id
            } else {
              otherPigs += p; 
              connections(p.name) = select(Node(p.address, p.port), Symbol(p.name)); 
              if(sawMe) { println("Connecting to " + otherPigs.last.name); nextPig = connections(otherPigs.last.name); sawMe = false }
            }
          }
          if(nextPig == null) {
            nextPig = connections(otherPigs.head.name)
            println("Connecting to otherPigs " + otherPigs.head.name )
          }
          master ! Connected
        }
        case Hit(time,l) => {
          lamport = math.max(l,lamport+1)
          println("\tI know where it hit!")
          println("\t\tat time: " + lamport)
          println("\t\twith hit time: " + time)
          if(evading) {
            val notEvaded = simpleWillHitMe(landedLoc)
            if(notEvaded) statusHit = true
            else {
              println("\t\t\tMy moved time was: " + movedTime)
              if(time <= movedTime) statusHit = true
              else statusHit = false
            }
          }
        }
        case BirdApproaching(location,l) => {
          landedLoc = location
          lamport = math.max(l,lamport+1)
          lamport += rand.nextInt(6)
          println("\taccepting BirdApproaching message")
          println("\t\tat time: " + lamport)
          evading = false
          if (simpleWillHitMe(location)) {
            evading = true
            println("\t\tIt will probably hit me! Taking evasive action.")
            moveToSafety(location)
            movedTime = lamport
          }
        }
        case StatusAll(l) => {
          lamport = math.max(l,lamport+1)
          println("\tGot status message with time: " + lamport)
          if(statusHit) println("\t\tSo.. I was too late!")
          else println("Yes! I moved in time!")
          sender ! (if(statusHit) 1 else 0)
        }
        case Election(ids, r, l) => {
          lamport = math.max(l,lamport+1)
          println("\tElecting leader with ids: " + ids.mkString(" "))
          println("\t\tWith max: " + ids.max)
          println("\t\ton round " + round + " from round: " + r)
          println("\t\tTime: " + lamport)
          if(round == r) {
            if(ids.head == electionId) {
              println("\t\tI gots the informations: " + ids.mkString(" ") + " with max: " + ids.max)
              for(p <- connections.values) p ! Leader(ids.max, r, lamport)
              this ! Leader(ids.max, r, lamport)
            } else {
              nextPig ! Election(ids ++ List(electionId), r, lamport)
            }
          }
        }
        case Leader(leader, r, l) => {
          lamport = math.max(l,lamport+1)
          println("\tI got leader message of:")
          println("\t\t leader " + leader + " round: " + r + " lamp: " + l)
          if(leader == electionId && leadRound != round && r == round) {
            leadRound = round
            lord = me.name
            println("\tI, Lord " + me.name + " am the newly elected leader of PigLandia! On round " + round )
            println("\t\tWith maxid: " + electionId)
            connections.values.foreach{ _ ! Inform(me.name,lamport) }
            println("\t\tDelpoying PigCIA for secret information gathering session!")
            var hitLocation: Int = (master !? Where).toString.toInt
            println("\t\tThe probable hit location: " + hitLocation)
            println("\t\tProprogating")
            println("\t\tat time: " + lamport)
            connections.values.foreach( _ ! BirdApproaching(hitLocation,lamport) )
            this ! BirdApproaching(hitLocation,lamport)
            connections.values.foreach( _ ! Hit(lamport + Config.game.messageDelay,lamport) )
            this ! Hit(lamport + Config.game.messageDelay,lamport) 
            numHit = 0
            for(p <- connections.values) {
              val s : Int = (p !? StatusAll(lamport)).asInstanceOf[Int]
              numHit += s
            }
            master ! Done(numHit)
          }
        }
        case Inform(leader, l) => {
          lamport = math.max(l,lamport+1)
          lord = leader
          println("\tI, " + me.name + " accept " + lord + " as my master! on round " + round )
          println("\t\tat time: " + lamport)
        }
        case SendGame(board, r) => {
          lamport += 1
          round = r
          sheltered = false
          statusHit = false
          pigBoard = board
          landedLoc = 0
          Game.printBoard(pigBoard)
          myLocation = 0
          while (pigBoard(myLocation) != me.idNumber) myLocation += 1
          electionId += 1
          if(electionId > Config.N+1) electionId = 0
          println("Round: " + round)
          println("\tI've got electionId of: " + electionId)
          println("\t\tat time: " + lamport)
          (nextPig ! Election(List(electionId),round,lamport))
        }
      }
    }
  }
}

object Pig {
  def main(args: Array[String]) {
    RemoteActor.classLoader = getClass().getClassLoader()
    Config.fromFile(args(0))
    val pig = new Pig(args(1))
    pig.start()
  }
}