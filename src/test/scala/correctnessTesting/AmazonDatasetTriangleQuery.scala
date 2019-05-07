package correctnessTesting

import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import sparkIntegration.implicits._
import testing.{SparkTest, Utils}
import experiments.Queries._
import experiments.Datasets.loadAmazonDataset
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row}

import scala.reflect.ClassTag

class AmazonDatasetTriangleQuery extends FlatSpec with Matchers with SparkTest {
  val OFFICIAL_NUMBERS_OF_TRIANGLES = 717719L
  val DATASET_PATH = "/home/per/workspace/master-thesis/datasets/amazon-0302"

  val FAST = true
  if (FAST) {
    System.err.println("Running correctness test in fast mode")
  }

  val ds = if (FAST) {
    loadAmazonDataset(DATASET_PATH, sp).limit(1000).cache()
  } else {
    loadAmazonDataset(DATASET_PATH, sp).cache()
  }
  println(s"Testing on the first ${ds.count()} edges of the Amazon set")

  val goldStandardTriangles = cliqueBinaryJoins(3, sp, ds).cache()
  val actualResultTriangles = cliquePattern(3, ds).cache()

  val (nodeSet1, nodeSet2) = pathQueryNodeSets(ds)
  nodeSet1.cache()
  nodeSet2.cache()

  private def assertRDDEqual(a: RDD[Row], e: RDD[Row]) = {
    val aExtras = a.subtract(e)
    val eExtras = e.subtract(a)

    val aExtrasEmpty = aExtras.isEmpty()
    val eExtrasEmpty = eExtras.isEmpty()

    if (!(aExtrasEmpty && eExtrasEmpty)) {
      Utils.printSeqRDD(50, aExtras.map(r => r.toSeq))
      Utils.printSeqRDD(50, eExtras.map(r => r.toSeq))
    }

    aExtrasEmpty should be(true)
    eExtrasEmpty should be(true)
  }

  private def assertRDDSetEqual(rdd1: RDD[Row], rdd2: RDD[Row], setSize: Int) = {
    val rdd1Set = rdd1.map(r => r.toSeq.toSet)
    val rdd2Set = rdd2.map(r => r.toSeq.toSet)

    rdd1Set.filter(_.size != setSize).isEmpty() should be (true)
    rdd2Set.filter(_.size != setSize).isEmpty() should be (true)


    val diff1 = rdd1Set.subtract(rdd2Set)
    val diff2 = rdd2Set.subtract(rdd1Set)

    val empty1 = diff1.isEmpty()
    val empty2 = diff2.isEmpty()

    if (!(empty1 && empty2)) {
      Utils.printSetRDD(50, diff1)
      Utils.printSetRDD(50, diff2)
    }

    empty1 should be (true)
    empty2 should be (true)
  }

  private def getPathQueryDataset(): DataFrame = {
    // TODO remove once path queries are fast enough
    if (FAST) {
      ds
    } else {
      ds.limit(1000)
    }
  }

  "WCOJ implementation" should "find the same two-paths as Spark's original joins" in {
    val ds = getPathQueryDataset()

    val e = pathBinaryJoins(2, ds, nodeSet1, nodeSet2).cache()
    val a = pathPattern(2, ds, nodeSet1, nodeSet2).cache()

    assertRDDEqual(a.rdd, e.rdd)
    a.isEmpty should be(false)
    e.isEmpty should be(false)
  }

  "WCOJ implementation" should "find the same three-paths as Spark's original joins" in {
    val ds = getPathQueryDataset()

    val e = pathBinaryJoins(3, ds, nodeSet1, nodeSet2).cache()
    val a = pathPattern(3, ds, nodeSet1, nodeSet2).cache()

    assertRDDEqual(a.rdd, e.rdd)
    a.isEmpty should be(false)
    e.isEmpty should be(false)
  }

  "WCOJ implementation" should "find the same four-paths as Spark's original joins" in {
    val ds = getPathQueryDataset()

    val e = pathBinaryJoins(4, ds, nodeSet1, nodeSet2)
    val a = pathPattern(4, ds, nodeSet1, nodeSet2)

    assertRDDEqual(a.rdd, e.rdd)

    a.isEmpty should be(false)
    e.isEmpty should be(false)
  }

  "WCOJ implementation" should "find same triangles as Spark's original joins" in {
    assertRDDEqual(actualResultTriangles.rdd, goldStandardTriangles.rdd)
  }

  "WCOJ implementation" should "produce roughly as many triangles as on the official website" in {
    if (!FAST) {
      val distinct = actualResultTriangles.rdd.map(r => r.toSeq.toSet).distinct(1).count()
      distinct should equal(OFFICIAL_NUMBERS_OF_TRIANGLES +- (OFFICIAL_NUMBERS_OF_TRIANGLES * 0.01).toLong)
    } else {
      succeed
    }
  }

  "The variable ordering" should "not matter" in {
    val otherVariableOrdering = ds.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (c);
        |(a) - [] -> (c)
        |""".stripMargin, List("c", "a", "b"))

    val otherReordered = otherVariableOrdering.select("a", "b", "c")

    assertRDDEqual(otherReordered.rdd, actualResultTriangles.rdd)
  }

  "Circular triangles" should "be found correctly" in {
    import sp.implicits._

    val circular = ds.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (c);
        |(c) - [] -> (a)
        |""".stripMargin, List("a", "b", "c"))

    val duos = ds.as("R")
      .joinWith(ds.as("S"), $"R.dst" === $"S.src")
    val triangles = duos.joinWith(ds.as("T"),
      condition = $"_2.dst" === $"T.src" && $"_1.src" === $"T.dst")

    val goldStandard = triangles.selectExpr("_2.dst AS a", "_1._1.dst AS b", "_2.src AS c")

    assertRDDEqual(circular.rdd, goldStandard.rdd)
  }

  "Four clique" should "be the same" in {
    val a = cliquePattern(4, ds)
    val e = cliqueBinaryJoins(4, sp, ds)

    assertRDDEqual(a.rdd, e.rdd)
  }

  "Diamond query" should "be the same" in {
    val a = diamondPattern(ds)
    val e = diamondBinaryJoins(ds)

    assertRDDSetEqual(a.rdd, e.rdd, 4)
  }

  "House query" should "be the same" in {
    val a = housePattern(ds)
    val e = houseBinaryJoins(sp, ds)

    assertRDDSetEqual(a.rdd, e.rdd, 5)
  }

  "5-clique query" should "be the same" in {
    val a = cliquePattern(5, ds)
    val e = cliqueBinaryJoins(5, sp, ds)

    assertRDDEqual(a.rdd, e.rdd)
  }

  "6-clique query" should "be the same" in {
    val a = cliquePattern(6, ds)
    val e = cliqueBinaryJoins(6, sp, ds)

    assertRDDEqual(a.rdd, e.rdd)
  }

  "4-cylce" should "be the same" in {
    val a = cyclePattern(4, ds)
    val e = cycleBinaryJoins(4, ds)

    assertRDDSetEqual(a.rdd, e.rdd, 4)
  }

  "5-cylce" should "be the same" in {
    val a = cyclePattern(5, ds)
    val e = cycleBinaryJoins(5, ds)

    assertRDDSetEqual(a.rdd, e.rdd, 5)
  }

  "6-cylce" should "be the same" in {
    val a = cyclePattern(6, ds)
    val e = cycleBinaryJoins(6, ds)

    assertRDDSetEqual(a.rdd, e.rdd, 6)
  }

}
