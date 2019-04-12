package sparkIntegration

import leapfrogTriejoin.{TrieIterable, TrieIterator}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.{OneToOneDependency, Partition, TaskContext}

class TrieIterableRDD[S <: TrieIterable](val trieIterableRDD: RDD[S])
  extends RDD[InternalRow](trieIterableRDD.context, List(new OneToOneDependency(trieIterableRDD))) {

  override def compute(split: Partition, context: TaskContext): Iterator[InternalRow] = {
    trieIterableRDD.compute(split, context).flatMap(t => t.map(x => {
      val gr = new GenericInternalRow(2)
      gr.update(0, x._1)
      gr.update(1, x._2)
      gr
    }))
  }

  override protected def getPartitions: Array[Partition] = trieIterableRDD.partitions

  def trieIterables: RDD[S] = {
    trieIterableRDD
  }
}