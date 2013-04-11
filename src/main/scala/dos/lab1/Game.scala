package dos.lab1
import scala.actors.Actor
import scala.actors.AbstractActor
import scala.actors.Actor._
import scala.actors.remote._
import scala.actors.remote.RemoteActor._
import scala.util.Random
import scala.collection.mutable.HashMap

/** Actor message: Sends board information to Master and then to the pigs 
  * 
  * @param board The representation of the world
  */
case class SendGame(board : Array[Int])
/** Actor message: Sends the hit location to the master and then to the pigs
  * @param landing The final landing information of the bird
*/
case class Hit(landing : Int)
/** Actor message: Tells the master to send information about whether a pig is hit by bird. Master will use this message to send that information. 
  * @param status The status of the pigs being hit or not by the bird
  */
case class Final(status : Boolean)

/** Game class. Runs the game, creates the board, stores statistics.
  * 
  * @param master The master that will send messages to P2P network from game
  */
class Game(val master : Master) {
	master.game = this
    var updates = 0
	val rand = new Random
    /** The state of the world **/
	val board = new Array[Int](Config.game.size)
    /** The final state of the world, as map from birdIds to locations **/
    val finalBoard = new HashMap[Int, Int]()
    /** The final landing location of a bird. **/
	var landing = -1
    while(!master.ready) { Thread.sleep(1000) }
    println("Starting game")
    var success = 0
    var done = false

    /** The number of rounds of games played **/
    var round = 0

    /** Has the game ended yet? **/
    var ended = false

    /** Num hit total is accumulator for number total hit all time **/
    var numHitTotal = 0

    while(true) {
    	round += 1
        updates = 0
        success = 0
        done = false
    	println("Playing round %d".format(round))
    	randomizeGame()
        loadFinalBoard()
    	landing = rand.nextInt(Config.game.size)
        println("Will hit location: " + landing)
    	Game.printBoard(board)
    	(master ! SendGame(board))
    	val speed = rand.nextInt(9900) + 100
        println("Will hit in: " +speed)
    	Thread.sleep(speed)
        println("Hitting!")
    	(master ! Hit(landing))
        println("Waiting for updates!")
        while(updates != Config.N) Thread.sleep(1000)
        println("Lets do it!")
        Game.printBoard(board, finalBoard)
        (master ! Final(true))
        println("Are we done yet?!")
        while(!done) Thread.sleep(1000)
    	println("Game Stats: num hit: " + success)
        numHitTotal += success
        println("Total hit all time: " + numHitTotal)
    }

    /** Randomize game creates a random setup of pigs on the board and stone columns around and next to pigs **/
    def randomizeGame() {
		(0 until board.length).foreach( board(_) = 0 )    	
		val stone = Config.N+1
    	val stones = rand.nextInt(7)
    	for( i <- 0 until stones) {
    		var placed = false
    		while(!placed) {
    			val place = rand.nextInt(Config.game.size)
    			if(board(place) == 0) { board(place) = stone; placed = true }
    		}
    	}
    	for(i <- master.connections) {
    		var placed = false
    		while(!placed) {
    			val place = rand.nextInt(Config.game.size)
    			if(board(place) == 0) { 
    				board(place) = i.idNumber; placed = true; 
    				if(place-1 > 0 && place-1 < Config.N && board(place) == 0 && rand.nextDouble > .25) board(place-1) = stone
    				else if(place+1 > 0 && place+1 < Config.N && board(place+1) == 0 && rand.nextDouble > .25) board(place+1) = stone
    			}
    		}
    	}
    }

    /** Creates final board from board information **/
    def loadFinalBoard() {
        for(i <- 0 until board.size) {
            if(board(i) < Config.N+1 && board(i) > 0) finalBoard(board(i)) = i
        }
    }
}

object Game {
    /** Prints the board to stout **/
	def printBoard(board : Array[Int]) {
    	println( board.map{ x => 
    		if(x==0) "."
    		else if(x==Config.N+1) "[]"
    		else "("+x+")" 
    		}.mkString("") )
    }

    /** Prints the final version of the board to stout **/
    def printBoard(board : Array[Int], fb : HashMap[Int,Int]) {
        println( board.zipWithIndex.map{ x => 
            if(x._1==Config.N+1) "[]"
            else  {
                if(!fb.values.toSet.contains(x._2)) (".")
                else {
                    val as = fb.filter( i => i._2 == x._2 )
                    ("(" + as.keys.mkString("|") + ")")
                }
            } 
            }.mkString("") )
    }


	def main(args : Array[String]) {
		RemoteActor.classLoader = getClass().getClassLoader()
		if(args.length < 1) {
			println("Usage: Game [config file]")
			System.exit(1)
		}
		Config.fromFile(args(0))
		val master = new Master()
		master.start()
		val game = (new Game(master))
	}
}