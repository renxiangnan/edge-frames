package sparkIntegration.wcoj

import experiments.metrics.Metrics
import experiments.{BinaryJoins, GraphWCOJ}
import leapfrogTriejoin.TrieIterable
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeSet, BoundReference, UnsafeProjection}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.execution.{RowIterator, SparkPlan}
import org.slf4j.LoggerFactory
import sparkIntegration.{JoinSpecification, WCOJConfiguration, WCOJInternalRow}
import sparkIntegration.implicits._

import scala.reflect.ClassTag

case class WCOJExec(outputVariables: Seq[Attribute], joinSpecification: JoinSpecification, children: Seq[SparkPlan]) extends SparkPlan {
  private val logger = LoggerFactory.getLogger(classOf[WCOJExec])

  val JOIN_TIME_METRIC = "wcoj_join_time"
  val COPY_OUTPUT_TIME_METRIC = "copy_time"
  val BEFORE_AFTER_TIME = "algorithm_end"
  val UNTIL_SCHEDULED = "scheduled"

  override lazy val metrics = Map(
    JOIN_TIME_METRIC -> SQLMetrics.createTimingMetric(sparkContext, "wcoj time"),
    COPY_OUTPUT_TIME_METRIC -> SQLMetrics.createTimingMetric(sparkContext, "copy")
  )

  override def output: Seq[Attribute] = {
    outputVariables ++ Seq()
  }

  override def references: AttributeSet = AttributeSet(children.flatMap(c => c.output.filter(a => List("src", "dst").contains(a.name))))

  override protected def doExecute(): RDD[InternalRow] = {
    val config = WCOJConfiguration.get(sparkContext)

    val joinTime = longMetric(JOIN_TIME_METRIC)
    val copyTime = longMetric(COPY_OUTPUT_TIME_METRIC)

    val joinTimer = Metrics.getTimer(sparkContext, JOIN_TIME_METRIC)
    val copyTimer = Metrics.getTimer(sparkContext, COPY_OUTPUT_TIME_METRIC)
    val algorithmEndTime = Metrics.getTimer(sparkContext, BEFORE_AFTER_TIME)
    val scheduledTime = Metrics.getTimer(sparkContext, UNTIL_SCHEDULED)

    var copyTimeAcc: Long = 0L
    var joinTimeAcc: Long = 0L

    val beforeTime = System.currentTimeMillis()

    Metrics.masterTimers.update("algorithmStart", beforeTime)

    val childRDDs = children.map(_.execute())

    // TODO ask Bogdan if we can enforce that the child needs a specific RDD type
//    require(childRDDs.forall(_.isInstanceOf[TrieIterableRDD[TrieIterable]]))

    def zipPartitions(is : List[Iterator[TrieIterable]]): Iterator[InternalRow] = {
      scheduledTime.add(0, System.currentTimeMillis())

      val toUnsafeProjection = UnsafeProjection.create(output.zipWithIndex.map( {
        case (a, i) => BoundReference(i, a.dataType, a.nullable)
      }))

      val zippedIters: Iterator[List[TrieIterable]] = generalZip(is)

      zippedIters.flatMap( a => {
        val join = joinSpecification.build(a, 0)

        val iter = new RowIterator {
          var row: Array[Long] = null
          val rowSize = joinSpecification.allVariables.size
          val internalRowBuffer = new WCOJInternalRow(new Array[Long](rowSize))

          override def advanceNext(): Boolean = {
            if (join.atEnd) {
              joinTime.set(joinTimeAcc / 1000000)
              copyTime.set(copyTimeAcc / 1000000)

              joinTimer.add(0, joinTimeAcc)
              copyTimer.add(0, copyTimeAcc)
              algorithmEndTime.add(0, System.currentTimeMillis())
              false
            } else {
//              val start = System.nanoTime()
              row = join.next()
//              joinTimeAcc += (System.nanoTime() - start)
              true
            }
          }

          override def getRow: InternalRow = {
//            val start = System.nanoTime()
            internalRowBuffer.row = row
            val ur = toUnsafeProjection(internalRowBuffer)
//            copyTimeAcc += (System.nanoTime() - start)
            ur
          }
        }
        iter.toScala
      }
      )
    }

    Metrics.masterTimers.update("algorithmStart", System.currentTimeMillis())

    config.getJoinAlgorithm match {
      case experiments.WCOJ => {
        val trieIterableRDDs = childRDDs.map(_.asInstanceOf[TrieIterableRDD[TrieIterable]].trieIterables)
        trieIterableRDDs match {
          case Nil => throw new UnsupportedOperationException("Cannot join without any child.")
          case c1 :: Nil => c1.mapPartitions(i => zipPartitions(List(i)))
          case c1 :: c2 :: t => generalZipPartitions(c1 :: c2 :: t)(zipPartitions)
        }
      }
      case GraphWCOJ => {
        throw new UnsupportedOperationException("Use GraphWCOJExec to execute a GraphWCOJ join.")
      }
    }
  }

  private def generalZip[A](s : List[Iterator[A]]): Iterator[List[A]] = s match {
    case Nil => Iterator.empty
    case h1 :: Nil => h1.map(_ :: Nil)
    case h1 :: h2 :: Nil => h1.zip(h2).map( { case (l, r) => List(l, r)})
    case h1 :: t => h1.zip(generalZip(t)).map ( { case (l, r) => l :: r})
  }

  private def generalZipPartitions[A: ClassTag, V: ClassTag](rdds: List[RDD[A]])(f: (List[Iterator[A]]) => Iterator[V]): RDD[V] = {
    rdds.head.generalZippedPartitions(sparkContext, rdds.tail)(f)
  }
}
