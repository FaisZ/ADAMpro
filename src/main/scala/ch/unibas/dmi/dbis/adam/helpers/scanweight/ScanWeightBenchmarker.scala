package ch.unibas.dmi.dbis.adam.helpers.scanweight

import ch.unibas.dmi.dbis.adam.catalog.CatalogOperator
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes
import ch.unibas.dmi.dbis.adam.datatypes.feature.FeatureVectorWrapper
import ch.unibas.dmi.dbis.adam.entity.Entity
import ch.unibas.dmi.dbis.adam.entity.Entity.EntityName
import ch.unibas.dmi.dbis.adam.exception.GeneralAdamException
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.query.distance.NormBasedDistance
import ch.unibas.dmi.dbis.adam.query.handler.generic.QueryExpression
import ch.unibas.dmi.dbis.adam.query.handler.internal.{IndexScanExpression, SequentialScanExpression}
import ch.unibas.dmi.dbis.adam.query.query.NearestNeighbourQuery
import ch.unibas.dmi.dbis.adam.utils.Logging
import org.apache.spark.util.random.Sampling

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * June 2016
  */
private class ScanWeightBenchmarker(entityname: EntityName, attribute: String, nqueries : Int, nruns : Int)(@transient implicit val ac: AdamContext) extends Serializable with Logging {
  private val entity = Entity.load(entityname).get

  private val sampleQueries = {
    if (entity.getFeatureData.isEmpty) {
      throw new GeneralAdamException("missing feature data for benchmarker")
    }

    val featureData = entity.getFeatureData.get
    val n = entity.count
    val fraction = Sampling.computeFractionForSampleSize(nqueries, n, withReplacement = false)

    featureData.sample(withReplacement = false, fraction = fraction).map(r => r.getAs[FeatureVectorWrapper](attribute)).collect().toSeq
  }


  private def benchmarkAndUpdate(): Unit = {
    val indexes = entity.indexes
    val measurements = mutable.Map[String, Seq[Long]]()

    val seqExpr = SequentialScanExpression(entity)(_: NearestNeighbourQuery)()
    val seqCost = cost(performMeasurements(sampleQueries, seqExpr))

    val indBenchmarks = indexes.filter(_.isSuccess).map(_.get).map { index =>
      val indExpr = IndexScanExpression(index)(_: NearestNeighbourQuery)()
      val indScore = cost(performMeasurements(sampleQueries, indExpr))
      (index, indScore)
    }

    val sumCost: Float = indBenchmarks.map(_._2).sum + seqCost

    CatalogOperator.updateAttributeOption(entityname, attribute, ScanWeightInspector.SCANWEIGHT_OPTION_NAME, ((1 + 1 - (seqCost / sumCost)) * ScanWeightInspector.DEFAULT_WEIGHT).toString)

    indBenchmarks.foreach { case (index, indCost) =>
      CatalogOperator.updateIndexOption(index.indexname, ScanWeightInspector.SCANWEIGHT_OPTION_NAME, ((1 + 1 - (seqCost / sumCost)) * ScanWeightInspector.DEFAULT_WEIGHT).toString)
    }
  }


  /**
    * Measures time.
    *
    * @param thunk
    * @tparam T
    * @return
    */
  private def time[T](thunk: => T): Long = {
    val t1 = System.currentTimeMillis
    val x = thunk
    val t2 = System.currentTimeMillis

    t2 - t1
  }


  /**
    * Performs measurement.
    *
    * @param qs
    * @param fexpr
    * @return
    */
  private def performMeasurements(qs: Seq[FeatureVectorWrapper], fexpr: (NearestNeighbourQuery) => QueryExpression): Seq[Long] = {
    val lb = new ListBuffer[Long]()

    qs.foreach { q =>
      val nnq: NearestNeighbourQuery = NearestNeighbourQuery(attribute, q.vector, None, NormBasedDistance(1), 100, false)
      val expr = fexpr(nnq).prepareTree()

      (0 to nruns).foreach { i =>
        lb += time(expr.evaluate())
      }
    }

    lb.toList
  }

  /**
    * Computes a score given the measurements. The larger the score, the slower the scan.
    *
    * @param measurements
    */
  private def cost(measurements: Seq[Long]): Float = {
    val mean = measurements.sum.toFloat / measurements.length.toFloat
    val stdev = math.sqrt(measurements.map { measure => (measure - mean) * (measure - mean) }.sum / measurements.length.toFloat).toFloat

    //remove measurements > or < 3 * stdev
    val clean = measurements.filterNot(m => m > mean + 3 * stdev).filterNot(m => m < mean - 3 * stdev)

    //average time
    clean.sum.toFloat / clean.length.toFloat
  }


}

object ScanWeightBenchmarker {
  private val NUMBER_OF_QUERIES = 5
  private val NUMBER_OF_RUNS = 2


  /**
    * Benchmarks all indexes and the attributes of the given entity and updates weight.
    *
    * @param entityname name of entity
    * @param attribute name of attribute
    * @param nqueries number of queries to perform for benchmarking query time
    * @param nruns number of runs per query to perform for benchmarking query time
    */
  def apply(entityname: EntityName, attribute: String, nqueries : Int = NUMBER_OF_QUERIES, nruns : Int = NUMBER_OF_RUNS)(implicit ac: AdamContext): Unit = {
    new ScanWeightBenchmarker(entityname, attribute, nqueries, nruns).benchmarkAndUpdate()
  }

  /**
    * Resets all weights.
    *
    * @param entityname name of entity
    * @param attribute name of attribute
    */
  def resetWeights(entityname: EntityName, attribute: Option[String] = None)(implicit ac: AdamContext): Unit = {
    val entity = Entity.load(entityname).get
    val indexes = entity.indexes.filter(_.isSuccess).map(_.get)

    val cols = if (attribute.isEmpty) {
      entity.schema().filter(_.fieldtype == FieldTypes.FEATURETYPE).map(_.name)
    } else {
      Seq(attribute.get)
    }

    cols.foreach { col =>
      CatalogOperator.updateAttributeOption(entityname, col, ScanWeightInspector.SCANWEIGHT_OPTION_NAME, ScanWeightInspector.DEFAULT_WEIGHT.toString)
    }

    indexes.filter(_.attribute == attribute.get).foreach { index =>
      CatalogOperator.updateIndexOption(index.indexname, ScanWeightInspector.SCANWEIGHT_OPTION_NAME, ScanWeightInspector.DEFAULT_WEIGHT.toString)
    }
  }
}
