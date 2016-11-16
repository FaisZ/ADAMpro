package org.vitrivr.adampro.helpers.partition

import org.vitrivr.adampro.catalog.CatalogOperator
import org.vitrivr.adampro.datatypes.feature.Feature.FeatureVector
import org.vitrivr.adampro.entity.EntityNameHolder
import org.vitrivr.adampro.index.Index
import org.vitrivr.adampro.main.AdamContext
import org.vitrivr.adampro.utils.Logging
import org.apache.spark.Partitioner
import org.apache.spark.sql.DataFrame

import scala.util.Random

/**
  * ADAMpar
  *
  * Silvan Heller
  * June 2016
  */
class RandomPartitioner(nPart: Int) extends Partitioner with Logging with Serializable {
  override def numPartitions: Int = nPart

  /**
    * Maps each key to a random partition ID, from 0 to `numPartitions - 1`.
    */
  override def getPartition(key: Any): Int = {
    (Random.nextFloat() * nPart).toInt
  }
}

object RandomPartitioner extends CustomPartitioner {
  override def partitionerName = PartitionerChoice.RANDOM

  /**
    * Throws each key in a random partition
    *
    * @param data        DataFrame you want to partition
    * @param cols        Does not matter in this mode
    * @param indexName   will be used to store the partitioner in the Catalog
    * @param nPartitions how many partitions shall be created
    * @return the partitioned DataFrame
    */
  override def apply(data: DataFrame, cols: Option[Seq[String]], indexName: Option[EntityNameHolder], nPartitions: Int, options: Map[String, String] = Map[String, String]())(implicit ac: AdamContext): DataFrame = {
    val schema = data.schema
    CatalogOperator.dropPartitioner(indexName.get)
    CatalogOperator.createPartitioner(indexName.get, nPartitions, null, RandomPartitioner)
    val toPartition = if (cols.isDefined) data.map(r => (r.getAs[Any](cols.get.head), r)) else data.map(r => (r.getAs[Any](Index.load(indexName.get).get.pk.name), r))
    ac.sqlContext.createDataFrame(toPartition.partitionBy(new RandomPartitioner(nPartitions)).mapPartitions(r => r.map(_._2), true), schema)
  }

  /** Returns the partitions to be queried for a given Feature vector
    * Returns Random Partitions
    * */
  override def getPartitions(q: FeatureVector, dropPercentage: Double, indexName: EntityNameHolder)(implicit ac: AdamContext): Seq[Int] = {
    val nPart = CatalogOperator.getNumberOfPartitions(indexName).get
    Random.shuffle(Seq.tabulate(nPart)(el => el)).drop((nPart * dropPercentage).toInt)
  }
}