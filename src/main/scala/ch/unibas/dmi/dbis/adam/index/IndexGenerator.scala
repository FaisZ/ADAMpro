package ch.unibas.dmi.dbis.adam.index

import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.index.Index.IndexName
import ch.unibas.dmi.dbis.adam.index.structures.IndexTypes
import org.apache.spark.Logging
import org.apache.spark.rdd.RDD

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
trait IndexGenerator extends Serializable with Logging {
  def indextypename: IndexTypes.IndexType
  def index(indexname : IndexName, entityname : EntityName, data: RDD[IndexingTaskTuple[_]]):  Index
}

object IndexGenerator {
  private[index] val MINIMUM_NUMBER_OF_TUPLE = 10
}