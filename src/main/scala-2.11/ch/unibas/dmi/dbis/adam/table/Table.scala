package ch.unibas.dmi.dbis.adam.table

import ch.unibas.dmi.dbis.adam.cache.RDDCache
import ch.unibas.dmi.dbis.adam.exception.{TableCreationException, TableNotExistingException}
import ch.unibas.dmi.dbis.adam.main.{SparkStartup, Startup}
import ch.unibas.dmi.dbis.adam.storage.catalog.CatalogOperator
import ch.unibas.dmi.dbis.adam.table.Table.TableName
import ch.unibas.dmi.dbis.adam.table.Tuple._
import org.apache.spark.partial.{BoundedDouble, PartialResult}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SaveMode}
import org.apache.spark.storage.StorageLevel

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
case class Table(tablename : TableName, data : DataFrame){
  def count = data.count()

  def show() = data.collect()
  def show(n : Int) = data.take(n)

  def rows = data.rdd
  def tuples = data.rdd.map(row => (row : Tuple))
}

case class CacheableTable(table : Table)

object Table {
  type TableName = String

  private val storage = SparkStartup.tableStorage
  private val sqlContext = SparkStartup.sqlContext


  /**
   *
   * @param tablename
   * @return
   */
  def existsTable(tablename : TableName) : Boolean = {
    CatalogOperator.existsTable(tablename)
  }

  /**
   *
   * @param tablename
   * @param schema
   * @return
   */
  def createTable(tablename : TableName, schema : StructType) : Table = {
    CatalogOperator.createTable(tablename)
    val data = sqlContext.createDataFrame(SparkStartup.sc.emptyRDD[Row], schema)

    val future = Future {
        storage.writeTable(tablename, data, SaveMode.ErrorIfExists)
      }
    future onFailure {case t => new TableCreationException()}

    Await.ready(future, Duration.Inf)

    Table(tablename, data)
  }

  /**
   *
   * @param tablename
   * @param ifExists
   */
  def dropTable(tablename : TableName, ifExists : Boolean = false) : Unit = {
    val indexes = CatalogOperator.getIndexes(tablename)
    CatalogOperator.dropTable(tablename, ifExists)

    storage.dropTable(tablename)
  }

  /**
   *
   * @param tablename
   * @return
   */
  def insertData(tablename : TableName, insertion: DataFrame): Unit ={
    if(!existsTable(tablename)){
      throw new TableNotExistingException()
    }

    //TODO: check schema equality between DF and insertion
    //val schema = data.schema.fields

    //splits DF equally
    val approxCount: PartialResult[BoundedDouble] = insertion.rdd.countApprox(5)
    val splitData = if(approxCount.getFinalValue().mean > 250000){
      insertion.randomSplit(Array.fill((approxCount.getFinalValue().mean / 100000).toInt)(1.0))
    } else {
      Array(insertion)
    }


    val future = Future {
      splitData.foreach{ data =>
        storage.writeTable(tablename, data, SaveMode.Append)
      }
    }

    Await.ready(future, Duration.Inf)
  }

  /**
   *
   * @param tablename
   * @return
   */
  def retrieveTable(tablename : TableName) : Table = {
    if(!existsTable(tablename)){
      throw new TableNotExistingException()
    }

    if(RDDCache.containsTable(tablename)){
      RDDCache.getTable(tablename)
    } else {
      storage.readTable(tablename)
    }
  }

  /**
   *
   * @param tablename
   * @return
   */
  def getCacheable(tablename : TableName) : CacheableTable = {
    val table = Table.retrieveTable(tablename)

    table.tuples
      .repartition(Startup.config.partitions)
      .setName(tablename).persist(StorageLevel.MEMORY_AND_DISK)
      .collect()

    CacheableTable(table)
  }
}
