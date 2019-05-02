package sparkIntegration

import org.apache.spark.sql.catalyst.expressions.GenericInternalRow

/**
  * Performance optimization, InternalRow type that allows to set an array
  * of ints directly, read those and does not support null values.
  *
  * Careful, only use when the client is guaranteed to use only `getInt` and
  * does not check for null values.
  */
class WCOJInternalRow(var row: Array[Int]) extends GenericInternalRow {
  override def getInt(offset: Int): Int = row(offset)
}
