/**
 * @Author ZYJ
 * @Description //TODO
 * @Date 9:13 2021/3/2
 **/

package recommender

import java.net.InetAddress

import com.mongodb.casbah.{MongoClient, MongoClientURI}
import com.mongodb.casbah.commons.MongoDBObject
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.transport.client.PreBuiltTransportClient


case class Movie(mid: Int, name: String, descri: String, timelong: String, issue: String,
                 shoot: String, language: String, genres: String, actors: String, directors: String)

case class Rating(uid: Int, mid: Int, score: Double, timestamp: Int)

case class Tag(uid: Int, mid: Int, tag: String, timestamp: Int)

//把mongo和es的配置封装成样例类
case class MongoConfig(uri: String, db: String)

case class ESConfig(httpHosts: String, transportHosts: String, index: String, clustername: String)

object DataLoader {

  //定义常量
  val MOVIE_DATA_PATH = "D:\\Program\\workspace_idea\\MovieRecommendSystem\\recommender\\DataLoader\\src\\main\\resources\\movies.csv"
  val RATING_DATA_PATH = "D:\\Program\\workspace_idea\\MovieRecommendSystem\\recommender\\DataLoader\\src\\main\\resources\\ratings.csv"
  val TAG_DATA_PATH = "D:\\Program\\workspace_idea\\MovieRecommendSystem\\recommender\\DataLoader\\src\\main\\resources\\tags.csv"

  val MONGODB_MOVIE_COLLECTION = "Movie"
  val MONGODB_RATING_COLLECTION = "Rating"
  val MONGODB_TAG_COLLECTION = "Tag"
  val ES_MOVIE_INDEX = "Movie"

  def main(args: Array[String]): Unit = {

    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://localhost:27017/recommender",
      "mongo.db" -> "recommender",
      "es.httpHosts" -> "localhost:9200",
      "es.transportHosts" -> "localhost:9300",
      "es.index" -> "recommender",
      "es.cluster.name" -> "elasticsearch"
    )

    //创建一个sparkConf
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("DataLoader")

    //创建一个SparkSession
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    //在对 DataFrame 和 Dataset 进行操作许多操作都需要这个包进行支持
    import spark.implicits._

    //将Movie、Rating、Tag 数据集加载进来
    val movieRDD = spark.sparkContext.textFile(MOVIE_DATA_PATH)
    //将MovieRDD 装换为 DataFrame
    val movieDF = movieRDD.map(item => {
      val attr = item.split("\\^")
      Movie(attr(0).toInt, attr(1).trim, attr(2).trim, attr(3).trim, attr(4).trim,
        attr(5).trim, attr(6).trim, attr(7).trim, attr(8).trim, attr(9).trim)
    }).toDF()

    val ratingRDD = spark.sparkContext.textFile(RATING_DATA_PATH)
    //将ratingRDD 转换为 DataFrame
    val ratingDF = ratingRDD.map(item => {
      val attr = item.split(",")
      Rating(attr(0).toInt, attr(1).toInt, attr(2).toDouble, attr(3).toInt)
    }).toDF()

    val tagRDD = spark.sparkContext.textFile(TAG_DATA_PATH)
    //将tagRDD 装换为 DataFrame
    val tagDF = tagRDD.map(item => {
      val attr = item.split(",")
      Tag(attr(0).toInt, attr(1).toInt, attr(2).trim, attr(3).toInt)
    }).toDF()

    //声明一个隐式的配置对象
    implicit val mongoConfig = MongoConfig(config("mongo.uri"), config("mongo.db"))

    //将数据保存到MongoDB
    storeDataInMongoDB(movieDF, ratingDF, tagDF)

    //数据预处理，把movie对应的tag信息添加进去，加一列 tag1|tag2|tag3...
    import org.apache.spark.sql.functions._

    //mid, tags(tag1|tag2|tag3...)
    val newTag = tagDF.groupBy($"mid")
      .agg(concat_ws("|", collect_set($"tag")).as("tags"))
      .select("mid", "tags")

    //需要将处理后的 Tag 数据，和 Moive 数据融合，产生新的 Movie 数据
    val movieWithTagsDF = movieDF.join(newTag, Seq("mid"), "left")

    //声明了一个 ES 配置的隐式参数
    implicit val esConfig = ESConfig(config("es.httpHosts"), config("es.transportHosts"), config("es.index"), config("es.cluster.name"))

    //需要将新的 Movie 数据保存到 ES 中
    storeDataInES(movieWithTagsDF)

    //关闭Spark
    spark.stop()
  }

  def storeDataInMongoDB(movieDF: DataFrame, ratingDF: DataFrame, tagDF: DataFrame)(implicit mongoConfig: MongoConfig): Unit = {
    //新建一个到 MongoDB 的连接
    val mongoClient = MongoClient(MongoClientURI(mongoConfig.uri))
    //如果 MongoDB 中有对应的数据库，那么应该删除
    mongoClient(mongoConfig.db)(MONGODB_MOVIE_COLLECTION).dropCollection()
    mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION).dropCollection()
    mongoClient(mongoConfig.db)(MONGODB_TAG_COLLECTION).dropCollection()
    //将DF数据写入到 MongoDB
    movieDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_MOVIE_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()
    ratingDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_RATING_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()
    tagDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_TAG_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()
    //对数据表建索引
    mongoClient(mongoConfig.db)(MONGODB_MOVIE_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_TAG_COLLECTION).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_TAG_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    //关闭 MongoDB 的连接
    mongoClient.close()
  }

  def storeDataInES(movieDF: DataFrame)(implicit eSConfig: ESConfig): Unit = {
    //新建es配置
    val settings: Settings = Settings.builder().put("cluster.name", eSConfig.clustername).build()
    //新建一个es的客户端
    val esClient = new PreBuiltTransportClient(settings)
    val REGEX_HOST_PORT = "(.+):(\\d+)".r
    eSConfig.transportHosts.split(",").foreach {
      case REGEX_HOST_PORT(host: String, port: String) => {
        esClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port.toInt))
      }
    }
    //需要清除掉es中遗留的数据
    if (esClient.admin().indices().exists(new IndicesExistsRequest(eSConfig.index)).actionGet().isExists) {
      esClient.admin().indices().delete(new DeleteIndexRequest(eSConfig.index))
    }
    esClient.admin().indices().create(new CreateIndexRequest(eSConfig.index))
    //将数据写入到es中
    movieDF.write
      .option("es.nodes", eSConfig.httpHosts)
      .option("es.http.timeout", "100m")
      .option("es.mapping.id", "mid")
      .mode("overwrite")
      .format("org.elasticsearch.spark.sql")
      .save(eSConfig.index + "/" + ES_MOVIE_INDEX)
  }

}
