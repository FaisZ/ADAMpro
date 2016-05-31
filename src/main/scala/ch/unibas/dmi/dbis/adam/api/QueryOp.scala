package ch.unibas.dmi.dbis.adam.api

import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.index.Index
import ch.unibas.dmi.dbis.adam.index.Index.{IndexName, IndexTypeName}
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.query.datastructures.ProgressiveQueryStatusTracker
import ch.unibas.dmi.dbis.adam.query.handler.generic.QueryExpression
import ch.unibas.dmi.dbis.adam.query.handler.internal.BooleanFilterExpression.BooleanFilterScanExpression
import ch.unibas.dmi.dbis.adam.query.handler.internal._
import ch.unibas.dmi.dbis.adam.query.progressive.{ProgressiveObservation, ProgressivePathChooser, ProgressiveQueryHandler}
import ch.unibas.dmi.dbis.adam.query.query.{BooleanQuery, NearestNeighbourQuery}
import org.apache.spark.sql.DataFrame

import scala.concurrent.duration.Duration
import scala.util.{Success, Try}

/**
  * adamtwo
  *
  *
  * Ivan Giangreco
  * November 2015
  */
object QueryOp extends GenericOp {
  /**
    * Executes a query expression.
    *
    * @param q
    * @return
    */
  def apply(q: QueryExpression)(implicit ac: AdamContext): Try[Option[DataFrame]] = {
    execute("query execution operation") {
      Success(q.prepareTree().evaluate())
    }
  }


  /**
    * Performs a sequential query, i.e., without using any index structure.
    *
    * @param entityname   name of entity
    * @param nnq          information for nearest neighbour query
    * @param bq           information for boolean query
    * @return
    */
  def sequential(entityname: EntityName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery])(implicit ac: AdamContext): Try[Option[DataFrame]] = {
    execute("sequential query operation") {
      var scan : Option[QueryExpression] = None

      if (bq.isDefined) {
        log.trace("boolean query is defined")
        scan = Some(BooleanFilterScanExpression(entityname)(bq.get)(scan))
      }

      scan =  Some(SequentialScanExpression(entityname)(nnq)(scan))

      return Success(scan.get.prepareTree().evaluate())
    }
  }

  /**
    * Performs an index-based query.
    *
    * @param indexname    name of index
    * @param nnq          information for nearest neighbour query
    * @return
    */
  def index(indexname: IndexName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery])(implicit ac: AdamContext): Try[Option[DataFrame]] = {
    execute("specified index query operation") {
      val index = Index.load(indexname).get

      var scan : Option[QueryExpression] = None

      if (bq.isDefined) {
        log.trace("boolean query is defined")
        scan = Some(BooleanFilterScanExpression(index.entityname)(bq.get)(scan))
      }

      scan = Some(IndexScanExpression(index)(nnq)(scan))

      Success(scan.get.prepareTree().evaluate())
    }
  }

  /**
    * Performs an index-based query.
    *
    * @param entityname    name of entity
    * @param indextypename name of index type
    * @param nnq           information for nearest neighbour query
    * @return
    */
  def index(entityname: EntityName, indextypename: IndexTypeName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery])(implicit ac: AdamContext): Try[Option[DataFrame]] = {
    execute("index query operation") {
      var scan : Option[QueryExpression] = None

      if (bq.isDefined) {
        log.trace("boolean query is defined")
        scan = Some(BooleanFilterScanExpression(entityname)(bq.get)(scan))
      }

      scan = Some(new IndexScanExpression(entityname, indextypename)(nnq)(scan))

      Success(scan.get.prepareTree().evaluate())
    }
  }

  /**
    * Performs a progressive query, i.e., all indexes and sequential search are started at the same time and results are returned as soon
    * as they are available. When a precise result is returned, the whole query is stopped.
    *
    * @param entityname
    * @param nnq        information for nearest neighbour query
    * @param paths
    * @param onComplete operation to perform as soon as one index returns results
    * @return a tracker for the progressive query
    */
  def progressive[U](entityname: EntityName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], paths: ProgressivePathChooser, onComplete: ProgressiveObservation => U)(implicit ac: AdamContext): Try[ProgressiveQueryStatusTracker] = {
    Success(ProgressiveQueryHandler.progressiveQuery(entityname, nnq, bq, paths, onComplete))
  }


  /**
    * Performs a timed progressive query, i.e., it performs the query for a maximum of the given time limit and returns then the best possible
    * available results.
    *
    * @param entityname
    * @param nnq       information for nearest neighbour query
    * @param paths
    * @param timelimit maximum time to wait
    * @return the results available together with a confidence score
    */
  def timedProgressive(entityname: EntityName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], paths: ProgressivePathChooser, timelimit: Duration)(implicit ac: AdamContext): Try[ProgressiveObservation] = {
    execute("timed progressive query operation") {
      Success(ProgressiveQueryHandler.timedProgressiveQuery(entityname, nnq, bq, paths, timelimit))
    }
  }

  /**
    * Performs a query which uses index compounding for pre-filtering.
    *
    * @param expr
    * @return
    */
  def compoundQuery(expr: QueryExpression)(implicit ac: AdamContext): Try[Option[DataFrame]] = {
    execute("compound query operation") {
      Success(CompoundQueryExpression(expr).evaluate())
    }
  }

  /**
    * Performs a boolean query.
    *
    * @param entityname
    * @param bq
    */
  def booleanQuery(entityname: EntityName, bq: Option[BooleanQuery])(implicit ac: AdamContext): Try[Option[DataFrame]] = {
    execute("boolean query operation") {
      var scan : Option[QueryExpression] = None

      if (bq.isDefined) {
        log.trace("boolean query is defined")
        scan = Some(BooleanFilterScanExpression(entityname)(bq.get)(scan))
      }

      return Success(scan.get.evaluate())
    }
  }
}