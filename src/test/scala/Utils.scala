package testing

import leapfrogTriejoin.TrieIterator
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import sparkIntegration.{ToTrieIterableRDD2ToTrieIterableRDDExec, WCOJ2WCOJExec}

import scala.collection.mutable

object Utils {

  def traverseTrieIterator(iter: TrieIterator): Seq[(Int, Int)] = {
    if (iter.atEnd) {
      return List()
    }
    var ret: mutable.MutableList[(Int, Int)] = mutable.MutableList()
    iter.open()
    do {
      val outer: Int = iter.key
      iter.open()
      do {
        ret += ((outer, iter.key))
        iter.next()
      } while (!iter.atEnd)
      iter.up()
      iter.next()
    } while (!iter.atEnd)
    ret
  }


}

object TestSparkSession {
  val conf = new SparkConf()
    .setMaster("local[1]")
    .setAppName("Spark test")
    .set("spark.executor.memory", "2g")
    .set("spark.driver.memory", "2g")

  val spark = SparkSession.builder().config(conf).getOrCreate()

  spark.experimental.extraStrategies = Seq(ToTrieIterableRDD2ToTrieIterableRDDExec, WCOJ2WCOJExec) ++ spark.experimental.extraStrategies
}

trait SparkTest {
  val sp = TestSparkSession.spark
}