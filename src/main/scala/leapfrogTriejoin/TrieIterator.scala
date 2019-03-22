package leapfrogTriejoin

import scala.collection.immutable.TreeMap
import Ordering.Implicits._
// TODO needs to be changed to a multimap
class TrieIterator(val relationship: EdgeRelationship) extends LinearIterator {
  def this (tuples: Array[(Int, Int)]) {
    this(new EdgeRelationship(("a", "b"), tuples))
  }

  val values = relationship.tuples

  val HIGHEST_LEVEL = -1
  var map = new TreeMap[Vector[Int], Int]()  // TODO do I want a mutable tree map?

  for ((t, i) <- values.zipWithIndex) {
    map = map.updated(Vector(t._1, t._2), i)
  }

  var depth = HIGHEST_LEVEL
  var isAtEnd = map.isEmpty
  var isAtTotalEnd = map.isEmpty
  val maxDepth = 1
  var triePath = Vector.fill(maxDepth + 1){-1}

  def up(): Unit = {
    if(depth == HIGHEST_LEVEL) {
      throw new IllegalStateException("Cannot go up in TrieIterator at root level.")
    }
    triePath = triePath.updated(depth, -1)
    depth -= 1
    isAtEnd = false
  }

  def open(): Unit = {
    if (depth == maxDepth) {
      throw new IllegalStateException("Cannot go down in TrieIterator at lowest level.")
    } else{
      depth += 1
      map = map.from(triePath)
      triePath = triePath.updated(depth, map.firstKey(depth))
      isAtEnd = false
    }
  }

  override def key: Int = triePath(depth)

  override def next(): Unit = {
    // TODO can we simply proceed along the map instead of seeking?
    seek(triePath(depth) + 1)
  }

  override def atEnd: Boolean = isAtEnd

  override def seek(key: Int): Unit = {
    if (atEnd) {
      throw new IllegalStateException("Cannot move to next at end of branch.")
    }

    val temp = map
    var possiblyNewNextVector = triePath.updated(depth, key)
    map = map.from(possiblyNewNextVector)
    if (map.isEmpty) {
      isAtEnd = true
      isAtTotalEnd = true
    } else {
      possiblyNewNextVector = map.firstKey
      if (0 < depth && possiblyNewNextVector(depth - 1) != triePath(depth - 1)) {
        isAtEnd = true
        map = temp  // TODO is that necessary? Yep, if one wants to have a linear and a tree part one could potentially go with a linear iterator changing depth on it's own
      } else {
        triePath = triePath.updated(depth, possiblyNewNextVector(depth))
      }
    }
  }
}
