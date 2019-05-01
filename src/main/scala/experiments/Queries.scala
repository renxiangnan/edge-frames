package experiments

import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import sparkIntegration.implicits._

object Queries {
  val FIXED_SEED_1 = 42
  val FIXED_SEED_2 = 220

  def pathQueryNodeSets(rel: DataFrame, selectivity: Double = 0.1): (DataFrame, DataFrame) = {
    (rel.selectExpr("src AS a").sample(selectivity, FIXED_SEED_1),
      rel.selectExpr("dst AS z").sample(selectivity, FIXED_SEED_2))
  }

  /**
    *
    * @param df
    * @param cols
    * @return `df` with filters such that all `cols` hold distinct values per row.
    */
  def withDistinctColumns(df: DataFrame, cols: Seq[String]): DataFrame = {
    var r = df
    for (c <- cols.combinations(2)) {
      r = r.where(new Column(c(0)) !== new Column(c(1)))
    }
    r
  }

  def pathBinaryJoins(size: Int, ds: DataFrame, ns1: DataFrame, ns2: DataFrame): DataFrame = {
    size match {
      case 2 => twoPathBinaryJoins(ds, ns1, ns2)
      case 3 => threePathBinaryJoins(ds, ns1, ns2)
      case 4 => fourPathBinaryJoins(ds, ns1, ns2)
    }
  }

  def pathPattern(size: Int, ds: DataFrame, ns1: DataFrame, ns2: DataFrame): DataFrame = {
    size match {
      case 2 => twoPathPattern(ds, ns1, ns2)
      case 3 => threePathPattern(ds, ns1, ns2)
      case 4 => fourPathBinaryJoins(ds, ns1, ns2)
    }
  }

  def twoPathBinaryJoins(rel: DataFrame, nodeSet1: DataFrame, nodeSet2: DataFrame): DataFrame = {
    val relLeft = rel.selectExpr("src AS a", "dst AS b").join(nodeSet1, Seq("a"), "left_semi")
    val relRight = rel.selectExpr("dst AS z", "src AS b").join(nodeSet2, Seq("z"), "left_semi")

    withDistinctColumns(relLeft.join(relRight, Seq("b")).selectExpr("a", "b", "z"), Seq("a", "b", "z"))
  }

  def twoPathPattern(rel: DataFrame, nodeSet1: DataFrame, nodeSet2: DataFrame): DataFrame = {
    val twoPath = rel.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (z)
      """.stripMargin, Seq("a", "z", "b"))
    // TODO should be done before the join
    val filtered = twoPath.join(nodeSet1, Seq("a"), "left_semi")
      .join(nodeSet2, Seq("z"), "left_semi")
      .select("a", "b", "z")
    withDistinctColumns(filtered, Seq("a", "b", "z"))
  }

  def threePathBinaryJoins(rel: DataFrame, nodeSet1: DataFrame, nodeSet2: DataFrame): DataFrame = {
    val relLeft = rel.selectExpr("src AS a", "dst AS b").join(nodeSet1, Seq("a"), "left_semi")
    val relRight = rel.selectExpr("dst AS z", "src AS c").join(nodeSet2, Seq("z"), "left_semi")

    val middleLeft = relLeft.join(rel.selectExpr("src AS b", "dst AS c"), Seq("b")).selectExpr("a", "b", "c")
    relRight.join(middleLeft, "c").select("a", "b", "c", "z")
  }

  def threePathPattern(rel: DataFrame, nodeSet1: DataFrame, nodeSet2: DataFrame): DataFrame = {
    val threePath = rel.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (c);
        |(c) - [] -> (z)
      """.stripMargin, Seq("a", "z", "c", "b"))
    threePath.join(nodeSet1, Seq("a"), "left_semi")
      .join(nodeSet2, Seq("z"), "left_semi")
      .select("a", "b", "c", "z")
  }

  def fourPathBinaryJoins(rel: DataFrame, nodeSet1: DataFrame, nodeSet2: DataFrame): DataFrame = {
    val relLeft = rel.selectExpr("src AS a", "dst AS b").join(nodeSet1, Seq("a"), "left_semi")
    val relRight = rel.selectExpr("dst AS z", "src AS d").join(nodeSet2, Seq("z"), "left_semi")

    val middleLeft = relLeft.join(rel.selectExpr("src AS b", "dst AS c"), Seq("b")).selectExpr("a", "b", "c")
    val middleRight = relRight.join(rel.selectExpr("src AS c", "dst AS d"), Seq("d")).selectExpr("c", "d", "z")
    withDistinctColumns(middleRight.join(middleLeft, "c").select("a", "b", "c", "d", "z"), Seq("a", "b", "c", "d", "z"))
  }

  def fourPathPattern(rel: DataFrame, nodeSet1: DataFrame, nodeSet2: DataFrame) = {
    val leftRel = rel.join(nodeSet1.selectExpr("a AS src"), Seq("src"), "left_semi")
    val rightRel = rel.join(nodeSet2.selectExpr("z AS dst"), Seq("dst"), "left_semi")
      .select("src", "dst") // Necessary because Spark reorders the columns

    val fourPath = rel.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (c);
        |(c) - [] -> (d);
        |(d) - [] -> (z)
      """.stripMargin, Seq("a", "z", "b", "d", "c"),
      Seq(leftRel,
        rel.alias("edges_2"),
        rel.alias("edges_3"),
        rightRel
      )
    )

    withDistinctColumns(fourPath.join(nodeSet1, Seq("a"), "left_semi")
      .join(nodeSet2, Seq("z"), "left_semi")
      .select("a", "b", "c", "d", "z"), Seq("a", "b", "c", "d", "z"))
  }

  def triangleBinaryJoins(spark: SparkSession, rel: DataFrame): DataFrame = {
    import spark.implicits._

    val duos = rel.as("R")
      .joinWith(rel.as("S"), $"R.dst" === $"S.src")
    val triangles = duos.joinWith(rel.as("T"),
      condition = $"_2.dst" === $"T.dst" && $"_1.src" === $"T.src")

    triangles.selectExpr("_2.src AS a", "_1._1.dst AS b", "_2.dst AS c")
  }

  def cliqueBinaryJoins(size: Int, sp: SparkSession, ds: DataFrame): DataFrame = {
    size match {
      case 3 => triangleBinaryJoins(sp, ds)
      case 4 => fourCliqueBinaryJoins(sp, ds)
      case 5 => fiveCliqueBinaryJoins(sp, ds)
      case 6 => sixCliqueBinaryJoins(sp, ds)
    }
  }

  def trianglePattern(rel: DataFrame): DataFrame = {

    rel.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (c);
        |(a) - [] -> (c)
        |""".stripMargin, List("a", "b", "c"))
  }

  def diamondPattern(rel: DataFrame): DataFrame = {
    withDistinctColumns(rel.findPattern(
      """
        |(a) - [] -> (b);
        |(b) - [] -> (c);
        |(c) - [] -> (d);
        |(d) - [] -> (a)
        |""".stripMargin, List("a", "b", "c", "d")), List("a", "b", "c", "d"))
  }

  def diamondBinaryJoins(rel: DataFrame): DataFrame = {
    withDistinctColumns(rel.selectExpr("src AS a", "dst AS b")
      .join(rel.selectExpr("src AS b", "dst AS c"), "b")
      .join(rel.selectExpr("src AS c", "dst AS d"), "c")
      .join(rel.selectExpr("src AS d", "dst AS a"), Seq("a", "d"), "left_semi"), Seq("a", "b", "c", "d"))
  }

  def fourCliqueBinaryJoins(sp: SparkSession, rel: DataFrame): DataFrame = {
    import sp.implicits._
    val triangles = triangleBinaryJoins(sp, rel)

    val fourClique =
      triangles.join(rel.alias("ad"), $"src" === $"a")
        .selectExpr("a", "b", "c", "dst AS d")
        .join(rel.alias("bd"), $"src" === $"b" && $"dst" === $"d", "left_semi")
        .join(rel.alias("cd"), $"src" === $"c" && $"dst" === $"d", "left_semi")
    fourClique.select("a", "b", "c", "d")
  }

  def cliquePattern(size: Int, rel: DataFrame): DataFrame = {
    val alphabet = 'a' to 'z'
    val verticeNames = alphabet.slice(0, size).map(_.toString)

    val pattern = verticeNames.combinations(2).filter(e => e(0) < e(1))
      .map(e => s"(${e(0)}) - [] -> (${e(1)})")
      .mkString(";")
    rel.findPattern(pattern, verticeNames)
  }

  def houseBinaryJoins(sp: SparkSession, rel: DataFrame): DataFrame =  {
    import sp.implicits._

    withDistinctColumns(fourCliqueBinaryJoins(sp, rel)
      .join(rel.alias("ce"), $"src" === $"c")
      .selectExpr("a", "b", "c", "d", "dst AS e")
      .join(rel.alias("de"), $"src" === $"d" && $"dst" === $"e", "left_semi")
      .select("a", "b", "c", "d", "e"),
      Seq("a", "b", "c", "d", "e"))
  }

  def housePattern(rel: DataFrame): DataFrame = {
    withDistinctColumns(rel.findPattern("""
      |(a) - [] -> (b);
      |(b) - [] -> (c);
      |(c) - [] -> (d);
      |(a) - [] -> (d);
      |(a) - [] -> (c);
      |(b) - [] -> (d);
      |(c) - [] -> (e);
      |(d) - [] -> (e)
      |""".stripMargin, List("a", "b", "c", "d", "e")), List("a", "b", "c", "d", "e"))
  }

  def fiveCliqueBinaryJoins(sp: SparkSession, rel: DataFrame): DataFrame = {
    import sp.implicits._


    fourCliqueBinaryJoins(sp, rel)
      .join(rel.alias("ae"), $"src" === $"a")
      .selectExpr("a", "b", "c", "d", "dst AS e")
      .join(rel.alias("be"), $"src" === $"b" && $"dst" === $"e", "left_semi")
      .join(rel.alias("ce"), $"src" === $"c" && $"dst" === $"e", "left_semi")
      .join(rel.alias("de"), $"src" === $"d" && $"dst" === $"e", "left_semi")
      .select("a", "b", "c", "d", "e")
  }

  def sixCliqueBinaryJoins(sp: SparkSession, rel: DataFrame): DataFrame = {
    import sp.implicits._


    fiveCliqueBinaryJoins(sp, rel)
      .join(rel.alias("af"), $"src" === $"a")
      .selectExpr("a", "b", "c", "d", "e", "dst AS f")
      .join(rel.alias("bf"), $"src" === $"b" && $"dst" === $"f", "left_semi")
      .join(rel.alias("cf"), $"src" === $"c" && $"dst" === $"f", "left_semi")
      .join(rel.alias("df"), $"src" === $"d" && $"dst" === $"f", "left_semi")
      .join(rel.alias("ef"), $"src" === $"e" && $"dst" === $"f", "left_semi")
      .select("a", "b", "c", "d", "e", "f")
  }

  def cycleBinaryJoins(size: Int, rel: DataFrame): DataFrame = {
    require(3 < size, "Use this method only for cyclics of four nodes and above")

    val verticeNames = ('a' to 'z').toList.map(c => s"$c")

    var ret = rel.selectExpr("src AS a", "dst AS b")
    for (i <- 1 until (size - 1)) {
      ret = ret.join(rel.selectExpr(s"src AS ${verticeNames(i)}", s"dst AS ${verticeNames(i + 1)}"), verticeNames(i))
    }
    ret = ret.join(rel.selectExpr(s"src AS ${verticeNames(size - 1)}", s"dst AS a"), Seq("a", verticeNames(size - 1)), "left_semi")
      .select(verticeNames.head, verticeNames.slice(1, size):_*)
    withDistinctColumns(ret, verticeNames.slice(0, size))
  }

  def cyclePattern(size: Int, rel: DataFrame): DataFrame = {
    require(3 < size, "Use this method only for cyclics of four nodes and above")

    val verticeNames = ('a' to 'z').toList.map(c => s"$c").slice(0, size)

    val pattern = verticeNames.zipWithIndex.map( { case (v1, i) => s"($v1) - [] -> (${verticeNames((i + 1) % size)})"}).mkString(";")

    withDistinctColumns(rel.findPattern(pattern, verticeNames), verticeNames)
  }

}
