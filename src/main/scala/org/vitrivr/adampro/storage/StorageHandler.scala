package org.vitrivr.adampro.storage

import org.vitrivr.adampro.data.entity.AttributeDefinition
import org.vitrivr.adampro.data.entity.Entity._
import org.vitrivr.adampro.utils.exception.GeneralAdamException
import org.vitrivr.adampro.query.query.Predicate
import org.vitrivr.adampro.storage.engine.Engine
import org.vitrivr.adampro.utils.Logging
import org.apache.spark.sql.{DataFrame, SaveMode}
import org.vitrivr.adampro.process.SharedComponentContext

import scala.util.{Failure, Random, Success, Try}

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * September 2016
  */
class StorageHandler(val engine: Engine, val priority : Int = 0) extends Serializable with Logging {
  private val MAX_STORENAME_LENGTH = 40

  val name = engine.name

  def supports = engine.supports

  def specializes = engine.specializes

  protected val ENTITY_OPTION_NAME = "storing-" + engine.name + "-tablename"

  /**
    * Executes operation.
    *
    * @param desc description to display in log
    * @param op   operation to perform
    * @return
    */
  protected def execute[T](desc: String)(op: => Try[T]): Try[T] = {
    try {
      log.trace("performed storage handler (" + name + ") operation: " + desc)
      val res = op
      res
    } catch {
      case e: Exception =>
        log.error("error in storage handler (" + name + ") operation: " + desc, e)
        Failure(e)
    }
  }

  /**
    *
    * @param entityname
    */
  protected def getStorename(entityname: EntityName)(implicit ac: SharedComponentContext): String = {
    val tablename = ac.catalogManager.getEntityOption(entityname, Some(ENTITY_OPTION_NAME)).get.get(ENTITY_OPTION_NAME)

    if (tablename.isEmpty) {
      log.error("storename missing from catalog for entity " + entityname + "; create method has not been called")
      throw new GeneralAdamException("no storename specified in catalog, no fallback")
    }

    tablename.get
  }

  /**
    *
    * @param entityname
    * @param attributes
    * @param params
    * @return
    */
  def create(entityname: EntityName, attributes: Seq[AttributeDefinition], params: Map[String, String] = Map())(implicit ac: SharedComponentContext): Try[Void] = {
    execute("create") {
      var storename = cleanStorename(entityname)

      while (engine.exists(storename).get) {
        storename = cleanStorename(storename + "_" + Random.nextInt(999)).toString
      }

      val res = engine.create(storename, attributes, params)

      if (res.isSuccess) {
        ac.catalogManager.updateEntityOption(entityname, ENTITY_OPTION_NAME, storename)

        res.get.foreach {
          case (key, value) =>
            ac.catalogManager.updateStorageEngineOption(name, storename, key, value)
        }

        Success(null)
      } else {
        Failure(res.failed.get)
      }
    }
  }

  /**
    *
    * @param entityname
    * @param params
    * @return
    */
  def read(entityname: EntityName, attributes: Seq[AttributeDefinition], predicates: Seq[Predicate] = Seq(), params: Map[String, String] = Map())(implicit ac: SharedComponentContext): Try[DataFrame] = {
    execute("read") {
      val storename = getStorename(entityname)
      val options = ac.catalogManager.getStorageEngineOption(name, storename).get
      val df = engine.read(storename, attributes, predicates, options ++ params)

      df
    }
  }

  /**
    *
    * @param entityname
    * @param df
    * @param attributes
    * @param mode
    * @param params
    * @return
    */
  def write(entityname: EntityName, df: DataFrame, attributes: Seq[AttributeDefinition], mode: SaveMode = SaveMode.Append, params: Map[String, String] = Map())(implicit ac: SharedComponentContext): Try[Void] = {
    execute("write") {
      val storename = getStorename(entityname)
      val options = ac.catalogManager.getStorageEngineOption(name, storename).get

      if (mode == SaveMode.Overwrite) {
        //TODO: on overwrite take over index structures, uniqueness, etc. (possibly call create()!)

        //overwriting
        var newStorename = ""
        do {
          newStorename = if(storename.contains("__ap__")){
            storename.substring(0, storename.lastIndexOf("__ap__")) + "__ap__" + Random.nextInt(999)
          } else {
            storename + "__ap__" + Random.nextInt(999)
          }

          //max 40 characters (use last 40)
          newStorename = newStorename.reverse.substring(0, math.min(newStorename.length, MAX_STORENAME_LENGTH)).reverse
          newStorename = cleanStorename(newStorename)
        } while (engine.exists(newStorename).get)

        engine.create(newStorename, attributes, params)

        val res = engine.write(newStorename, df, attributes, SaveMode.Append, params ++ options)

        if (res.isSuccess) {
          //update name
          ac.catalogManager.updateEntityOption(entityname, ENTITY_OPTION_NAME, newStorename)
          engine.drop(storename)

          updateOptions(newStorename, res.get)
          Success(null)
        } else {
          Failure(res.failed.get)
        }
      } else {
        //other save modes
        val res = engine.write(storename, df, attributes, mode, params ++ options)
        updateOptions(storename, res.get)

        if (res.isSuccess) {
          Success(null)
        } else {
          Failure(res.failed.get)
        }
      }
    }
  }

  private def updateOptions(storename : String, newOptions : Map[String, String])(implicit ac: SharedComponentContext): Unit ={
    val options = ac.catalogManager.getStorageEngineOption(name, storename).get
    ac.catalogManager.deleteStorageEngineOption(name, storename, None)

    newOptions.foreach {
      case (key, value) =>
        ac.catalogManager.updateStorageEngineOption(name, storename, key, value)
    }


  }

  /**
    *
    * @param entityname
    * @param params
    * @return
    */
  def drop(entityname: EntityName, params: Map[String, String] = Map())(implicit ac: SharedComponentContext): Try[Void] = {
    execute("drop") {
      val res = engine.drop(getStorename(entityname))

      if (res.isSuccess) {
        ac.catalogManager.deleteEntityOption(entityname, ENTITY_OPTION_NAME)
        Success(null)
      } else {
        Failure(res.failed.get)
      }
    }
  }


  /**
    *
    * @param entityname
    * @return
    */
  private def cleanStorename(entityname : EntityName) : String = {
    entityname.toString.reverse.substring(0, math.min(MAX_STORENAME_LENGTH, entityname.length)).reverse
  }


  override def equals(other: Any): Boolean =
    other match {
      case that: StorageHandler => this.engine.equals(that.engine)
      case _ => false
    }

  override def hashCode: Int = engine.hashCode
}
