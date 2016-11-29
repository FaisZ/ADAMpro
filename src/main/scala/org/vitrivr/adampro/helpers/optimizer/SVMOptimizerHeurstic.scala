package org.vitrivr.adampro.helpers.optimizer

import breeze.linalg.DenseVector
import org.vitrivr.adampro.api.QueryOp
import org.vitrivr.adampro.catalog.CatalogOperator
import org.vitrivr.adampro.datatypes.feature.FeatureVectorWrapper
import org.vitrivr.adampro.entity.Entity
import org.vitrivr.adampro.index.structures.ecp.ECPIndex
import org.vitrivr.adampro.index.structures.lsh.LSHIndex
import org.vitrivr.adampro.index.structures.mi.MIIndex
import org.vitrivr.adampro.index.structures.pq.PQIndex
import org.vitrivr.adampro.index.structures.sh.SHIndex
import org.vitrivr.adampro.index.structures.va.{VAIndex, VAPlusIndex, VAPlusIndexMetaData}
import org.vitrivr.adampro.index.{Index, IndexingTaskTuple}
import org.vitrivr.adampro.main.AdamContext
import org.vitrivr.adampro.main.SparkStartup.Implicits._
import org.vitrivr.adampro.ml.{PegasosSVM, TrainingSample}
import org.vitrivr.adampro.query.query.NearestNeighbourQuery

import scala.collection.mutable.ListBuffer

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * November 2016
  */
private[optimizer] class SVMOptimizerHeurstic()(@transient implicit override val ac: AdamContext) extends OptimizerHeurstic("svm") {
  /**
    *
    * @param indexes
    * @param queries
    */
  override def train(indexes: Seq[Index], queries: Seq[NearestNeighbourQuery]): Unit = {
    val entity = indexes.head.entity.get

    val trainData = queries.flatMap { nnq =>
      val rel = QueryOp.sequential(entity.entityname, nnq, None).get.get.select(entity.pk.name).collect().map(_.getAs[Any](0)).toSet

      indexes.map { index =>
        performMeasurement(index, nnq, rel).map(time => (index.indextypename, buildFeature(index, nnq), time))
      }.flatten
    }.groupBy(_._1).mapValues(_.map(x => (x._2, x._3)))

    trainData.foreach { case (indextypename, trainDatum) =>
      if (trainDatum.nonEmpty && !CatalogOperator.containsOptimizerOptionMeta(name, "svm-index-" + indextypename.name).getOrElse(false)) {
        CatalogOperator.createOptimizerOption(name, "svm-index-" + indextypename.name, new PegasosSVM(trainDatum.head._1.length))
      }

      val svm = CatalogOperator.getOptimizerOptionMeta(name, "svm-index-" + indextypename.name).get.asInstanceOf[PegasosSVM]
      svm.train(trainDatum.map { case (x, y) => TrainingSample(x, y.time) })
    }
  }

  /**
    *
    * @param entity
    * @param queries
    */
  override def train(entity: Entity, queries: Seq[NearestNeighbourQuery]): Unit = {
    val trainDatum = queries.flatMap { nnq =>
      performMeasurement(entity, nnq).map(time => (buildFeature(entity, nnq), time))
    }

    if (trainDatum.nonEmpty && !CatalogOperator.containsOptimizerOptionMeta(name, "svm-entity").getOrElse(false)) {
      CatalogOperator.createOptimizerOption(name, "svm-entity", new PegasosSVM(trainDatum.head._1.length))
    }

    val svm = CatalogOperator.getOptimizerOptionMeta(name, "svm-entity").get.asInstanceOf[PegasosSVM]
    svm.train(trainDatum.map { case (x, y) => TrainingSample(x, y.time) })
  }

  /**
    *
    * @param index
    * @param nnq
    */
  override def test(index: Index, nnq: NearestNeighbourQuery): Double = getScore("svm-index-" + index.indextypename.name, buildFeature(index, nnq))


  /**
    *
    * @param entity
    * @param nnq
    * @return
    */
  override def test(entity: Entity, nnq: NearestNeighbourQuery): Double = getScore("svm-entity", buildFeature(entity, nnq))


  /**
    *
    * @param key
    * @param f
    * @return
    */
  private def getScore(key: String, f : DenseVector[Double]): Double = {
    if (CatalogOperator.containsOptimizerOptionMeta(name, key).getOrElse(false)) {
      val metaOpt = CatalogOperator.getOptimizerOptionMeta(name, key)

      if (metaOpt.isSuccess) {
        val svm = metaOpt.get.asInstanceOf[PegasosSVM]
        svm.test(f)
      } else {
        0.toDouble
      }
    } else {
      0.toDouble
    }
  }

  /**
    *
    * @param index
    * @param nnq
    */
  private def buildFeature(index: Index, nnq: NearestNeighbourQuery): DenseVector[Double] = DenseVector[Double]((buildFeature(index) ++ buildFeature(nnq)).toArray)

  /**
    *
    * @param entity
    * @param nnq
    */
  private def buildFeature(entity: Entity, nnq: NearestNeighbourQuery): DenseVector[Double] = DenseVector[Double]((buildFeature(entity, nnq.attribute) ++ buildFeature(nnq)).toArray)


  /**
    *
    * @param nnq
    * @return
    */
  private def buildFeature(nnq: NearestNeighbourQuery): Seq[Double] = {
    val lb = new ListBuffer[Double]()

    lb += nnq.k

    lb.toSeq
  }

  /**
    *
    * @param entity
    * @param attribute
    * @return
    */
  private def buildFeature(entity: Entity, attribute: String): Seq[Double] = {
    val lb = new ListBuffer[Double]()

    lb += entity.getAttributeData(attribute).get.map { x => IndexingTaskTuple(x.getAs[Any](entity.pk.name), x.getAs[FeatureVectorWrapper](attribute).vector) }.first.feature.length
    lb += entity.count

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: Index): Seq[Double] = {
    val lb = new ListBuffer[Double]()

    lb += index.count

    lb ++= (index match {
      case idx: ECPIndex => buildFeature(idx)
      case idx: LSHIndex => buildFeature(idx)
      case idx: MIIndex => buildFeature(idx)
      case idx: PQIndex => buildFeature(idx)
      case idx: SHIndex => buildFeature(idx)
      case idx: VAPlusIndex => buildFeature(idx)
      case idx: VAIndex => buildFeature(idx)
      case _ => Seq()
    })

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: ECPIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta

    lb += meta.leaders.length

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: LSHIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta

    lb += meta.hashTables.length
    lb += meta.m
    lb += meta.radius
    lb += meta.hashTables.head.functions.size

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: MIIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta

    lb += meta.ki
    lb += meta.ks
    lb += meta.refs.length

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: PQIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta

    lb += meta.models.length
    lb += meta.nsq

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: SHIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta

    lb += meta.modes.size

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: VAIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta

    lb += meta.marks.length

    lb.toSeq
  }

  /**
    *
    * @param index
    * @return
    */
  private def buildFeature(index: VAPlusIndex): Seq[Double] = {
    val lb = new ListBuffer[Double]()
    val meta = index.meta.asInstanceOf[VAPlusIndexMetaData]

    lb += meta.marks.length
    lb += meta.pca.k

    lb.toSeq
  }
}