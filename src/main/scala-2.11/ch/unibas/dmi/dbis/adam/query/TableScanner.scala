package ch.unibas.dmi.dbis.adam.query

import ch.unibas.dmi.dbis.adam.datatypes.Feature._
import ch.unibas.dmi.dbis.adam.main.SparkStartup
import ch.unibas.dmi.dbis.adam.query.distance.DistanceFunction
import ch.unibas.dmi.dbis.adam.table.Table
import ch.unibas.dmi.dbis.adam.table.Tuple._

import scala.collection.immutable.HashSet
import scala.collection.mutable.ListBuffer

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object TableScanner {

  /**
   *
   * @param table
   * @param q
   * @param distance
   * @param k
   * @param filter
   * @return
   */
  def apply(table : Table, q: WorkingVector, distance : DistanceFunction, k : Int, filter: Option[HashSet[TupleID]], queryID : Option[String] = None): Seq[Result] = {
    SparkStartup.sc.setLocalProperty("spark.scheduler.pool", "table")
    SparkStartup.sc.setJobGroup(queryID.getOrElse(""), table.tablename, true)

    val data = if(filter.isDefined) {
      table.tuplesForKeys(filter.get).collect()
    } else {
      table.tuples.collect()
    }

    val it = data.par.iterator

    val ls = ListBuffer[Result]()
    while(it.hasNext){
      val tuple = it.next
      val f : WorkingVector = tuple.value
      ls += Result(distance(q, f), tuple.tid, null)
    }

    val finalResult = ls.sortBy(_.distance).take(k)

    val metadata = table.getMetadata.filter(table.getMetadata("__adam_id") isin (finalResult.map(_.tid) : _*)).map(r => r.getAs[Long]("__adam_id") -> r).collectAsMap()

    finalResult.map(r => r.metadata = metadata(r.tid))

    finalResult
  }
}
