import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Source
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.rdd.RDD
import spray.json.RootJsonFormat

import scala.util.Try
import org.apache.spark.sql.{DataFrameReader, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}

import scala.concurrent.Future
import scala.io.StdIn


object Main extends App with akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
  with spray.json.DefaultJsonProtocol {
  val app = "spark-podcasts1"

  // HTTP settings:
  val host = "localhost"
  val port = 8080

  // PSQL settings:
  val db = "podcasts1"
  val socket = "/var/run/postgresql"
  val url = s"jdbc:postgresql://localhost/$db?user=postgres&password=postgres" +
    "&socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=" + socket + "/.s.PGSQL.5432"
  println(s"Using PSQL JDBC url=$url")

  implicit val system = ActorSystem(Behaviors.empty, app)
  implicit val executionContext = system.executionContext

  val conf = new SparkConf().setMaster(sys.env.getOrElse("PODCASTS1_SPARK", "local[*]")).setAppName(app)
  implicit val spark: SparkSession = SparkSession.builder().config(conf).getOrCreate()
  val sc: SparkContext = spark.sparkContext
  sc.setLogLevel("ERROR")
  println(s"Using Scala ${util.Properties.versionString} Apache Spark version ${spark.version}")

  /* try */ Class.forName("org.postgresql.Driver")

  def dbReader(): DataFrameReader =
    spark.sqlContext.read.format("jdbc").option("driver", "org.postgresql.Driver").option("url", url)

  val fProf = Future {
    val profiles = dbReader.option("dbtable", "x_profiles").load
    profiles.select("inode", "name").rdd.map(r => (r.getLong(0), r.getString(1)))
  }
  val fRel = Future {
    val rel12 = dbReader.option("dbtable", "rel12").load
    // CREATE VIEW rel12 as SELECT t1.profile_inode as p1, t2.profile_inode as p2, t1.episode_inode,
    // t1.time_created as time1, t2.time_created as time2
    // FROM g_episode_features_profile as t1 JOIN g_episode_features_profile as t2 ON t1.episode_inode=t2.episode_inode
    // WHERE t1.role=12 AND t2.role=13;

    val result = rel12.rdd.flatMap { r =>
      val tr = Try {
        Iterable(Edge(r.getLong(0), r.getLong(1), 12 -> r.getLong(2)), Edge(r.getLong(1), r.getLong(0), 13 -> r.getLong(2)))
      }
      tr.failed.foreach { th =>
        println(s"Problem with row $r : ${th.getMessage}")
      }
      tr.toOption.toIterable.flatten
    }

    println(s"Note: ${rel12.count()} of ${result.count()} records loaded.")

    result
  }
  val fRanks = for {
    prof <- fProf
    rel <- fRel
    gr = Graph(prof, rel)
    } yield gr.pageRank(0.0001).vertices.join(prof).sortBy(_._2._1, false)

  case class Rank(rank: Double, profId: Long, name: String)

  implicit val rankFormat: RootJsonFormat[Rank] = jsonFormat3(Rank.apply)
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  val route =
    path("pagerank") {
      get {
        parameters("skip".as[Int].optional, "take".as[Int].optional) { (skipOp, takeOp) =>
          val skip = skipOp.getOrElse(0)
          val take = takeOp.getOrElse(50) // (Int.MaxValue)

          /*
          val source: Source[Rank, NotUsed] = Source.fromIterator(() =>
            ranks.take(skip + take).drop(skip).map {
              case (vid, (rank, name)) => Rank(rank, vid, name)
            }.toIterator
          )
          */

          val future = fRanks.map(_.take(skip + take).drop(skip).map {
            case (vid, (rank, name)) => Rank(rank, vid, name)
          })

          complete(future)
        }
      }
    }

  val bindingFuture = Http().newServerAt(host, port).bind(route)

  println(s"Server now online. Please navigate to http://$host:$port/pagerank\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done

  spark.close()
  println("Bye!")
}
