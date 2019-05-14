package experiments

import java.nio.file.{Files, Path, Paths}

import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.types.{IntegerType, LongType, StringType, StructType}
import org.apache.spark.sql.{DataFrame, DataFrameReader, Row, SparkSession, types}

object Datasets {
  val schema = new StructType()
    .add("src", LongType, false)
    .add("dst", LongType, false)

  private def makeUndirected(rel: DataFrame, sp: SparkSession): DataFrame = {
    import sp.implicits._
    rel.flatMap({
      case Row(src: Long, dst: Long) => {
        Seq((src, dst), (dst, src))
      }
    }).toDF("src", "dst")
      .distinct()
      .repartition(1)
  }

  private def snapDatasetReader(sp: SparkSession): DataFrameReader = {
    sp.read
      .format("csv")
      .option("delimiter", "\t")
      .option("comment", "#")
      .schema(schema)
  }

  def loadAmazonDataset(dataSetPath: String, sp: SparkSession): DataFrame = {
    loadAndCacheAsParquet(dataSetPath,
      snapDatasetReader(sp),
      sp)
  }

  def loadSNBDataset(sp: SparkSession, datasetPath: String): DataFrame = {

    import sp.implicits._
    val parquetFile = datasetPath + ".parquet"
    val d = if (Files.exists(Paths.get(parquetFile.replace("file://", "")))) {
      sp.read.parquet(parquetFile)
    } else {
      println("Parquet file not existing")
      val df =
        sp.read
          .format("csv")
          .option("delimiter", "|")
          //          .option("inferSchema", "true")
          .schema(new types.StructType().add("src", LongType).add("dst", LongType).add("creationDate", StringType))
          .csv(Seq(datasetPath, "csv").mkString("."))
          .drop("creationDate")
          .filter($"src".isNotNull && $"dst".isNotNull)

      val undirected = makeUndirected(df, sp)
      val sorted = undirected.sort("src", "dst")
      println("Caching as parquet file")
      sorted.write.parquet(parquetFile)
      sorted
    }
    d.repartition(1)
  }

  def loadLiveJournalDataset(sp: SparkSession, datasetPath: String): DataFrame = {
    loadAndCacheAsParquet(datasetPath,
      snapDatasetReader(sp),
      sp
    ).repartition(1)
  }

  def loadTwitterSnapEgo(sp: SparkSession, datasetPath: String): DataFrame = {
    import sp.implicits._
    val parquetFile = datasetPath + ".parquet"
    val d = if (Files.exists(Paths.get(parquetFile.replace("file://", "")))) {
      sp.read.parquet(parquetFile)
    } else {
      println("Parquet file not existing")
      val df =
        sp.read
          .format("csv")
          .option("delimiter", " ")
          .option("comment", "#")
          .schema(schema)
          .csv(datasetPath + ".csv")
          .toDF("src", "dst")
          .filter($"src" =!= $"dst")
          .distinct()
          .sort("src", "dst") // TODO so the others were presorted that of course changes my setup time quite a bit
      println("Caching as parquet file")
      df.write.parquet(parquetFile)
      df
    }
    d.repartition(1)
  }

  def loadAndCacheAsParquet(datasetFilePath: String, csvReader: DataFrameReader, sp: SparkSession): DataFrame = {
    val parquetFile = datasetFilePath + ".parquet"
    if (Files.exists(Paths.get(parquetFile.replace("file://", "")))) {
      sp.read.parquet(parquetFile)
    } else {
      println("Parquet file not existing")
      val df = csvReader.csv(datasetFilePath + ".csv")
      println("Caching as parquet file")
      df.write.parquet(parquetFile)
      df
    }
  }

}
