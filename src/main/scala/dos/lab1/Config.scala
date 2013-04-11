package dos.lab1
import net.liftweb.json._
import scala.io.Source
import scala.collection.mutable.{ArrayBuffer, HashMap}


/**
  * Configuration information per pig
  *
  * @param name The name of the pig
  * @param address The hostname the pig will connect on
  * @param port The port the pig will connect on
  */
@scala.serializable
class PigConfig(val name : String, val address : String, val port : Int) {
	var connected = false
	var idNumber = -1
}
/**
  * Configuration information for the game state
  *
  * @param size Size of the board. Is a multiplier for the number of pigs in the network
  * @param messageDelay Hop-by-bop delay time in ms
  * @param error 0 if the estimated location is perfect and 1 if it can be off by 1 in either direction
  */
class GameConfig(val size : Int, val messageDelay : Int, val error : Int)
/**
  * Configuration information per hostname connected
  *
  * @param address The hostname the computer
  * @param pig The initial pig to be added to computer
  */
class Computer(val address : String, pig : PigConfig) {
	val pigs = ArrayBuffer[PigConfig]()
	pigs += pig 
}
/**
  * Configuration information for the master node
  *
  * @param address The hostname the computer
  * @param port The port the master will connect on
  */
class MasterConfig(val address : String, val port : Int)

/** Singleton Object containing the configuration information for the PigGame **/
object Config {
	/** Map from hostnames to computers **/
	val computers = HashMap[String,Computer]()
	/** Array of pigs configuration information **/ 
	var pigs : Array[(String, String, Int)] = null
	/** Master configuration information **/
	var master : MasterConfig = null
	/** Game configuration information **/
	var game : GameConfig = null
	/** The amount of pigs in the network **/
	var N = 0
	/** Will load the configuration from json file 
     * @param configFile The name of the configuration file on the filesystem to load.
	*/
	def fromFile(configFile : String) {
		val fileData = Source.fromFile(configFile).mkString
		val json = parse(fileData)
		val p = new ArrayBuffer[(String, String, Int)]()
		(json \ "pigs").children.foreach{ x => 
			val pig = new PigConfig((x \ "name").values.toString, (x \ "address").values.toString, (x \ "port").values.toString.toInt)
			if(computers.contains(pig.address)) computers(pig.address).pigs += pig
			else computers(pig.address) = new Computer(pig.address, pig)
			N += 1
		}
		pigs = p.toArray
		val m = (json \ "master")
		master = new MasterConfig( (m \ "address").values.toString,  (m \ "port").values.toString.toInt )
		val g = (json \ "game")
		game = new GameConfig( (g \ "boardSize").values.toString.toInt*N, (g \ "messageDelay").values.toString.toInt, (g \ "error").values.toString.toInt  )
	}
}