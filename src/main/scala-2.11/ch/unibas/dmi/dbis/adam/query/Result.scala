package ch.unibas.dmi.dbis.adam.query

import ch.unibas.dmi.dbis.adam.table.Tuple
import Tuple.TupleID
import ch.unibas.dmi.dbis.adam.query.distance.Distance.Distance
import org.apache.spark.sql.Row

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
case class Result (distance : Distance, tid : TupleID, var metadata : Row)  extends Ordered[Result] {
  /**
   *
   * @param that
   * @return
   */
  override def compare(that: Result): Int = distance compare that.distance
}
