package org.vitrivr.adampro.entity

import org.vitrivr.adampro.datatypes.FieldTypes.FieldType
import org.vitrivr.adampro.exception.GeneralAdamException
import org.vitrivr.adampro.main.AdamContext
import org.vitrivr.adampro.storage.StorageHandler

import scala.collection.mutable.ListBuffer

/**
  * adamtwo
  *
  * Ivan Giangreco
  * May 2016
  *
  * @param name      name of attribute
  * @param fieldtype type of field
  * @param pk        is primary key
  * @param storagehandlername
  * @param params
  */
case class AttributeDefinition(name: String, fieldtype: FieldType, pk: Boolean = false, storagehandlername: String, params: Map[String, String] = Map()) {
  def this(name: String, fieldtype: FieldType, pk: Boolean, params: Map[String, String] = Map())(implicit ac: AdamContext) {
    this(name, fieldtype, pk, ac.storageHandlerRegistry.value.get(fieldtype).get.name, params)
  }


  /**
    * Returns the storage handler for the given attribute (it possibly uses a fallback, if no storagehandlername is specified by using the fieldtype)
    */
  def storagehandler()(implicit ac: AdamContext): StorageHandler = {
    val handler = ac.storageHandlerRegistry.value.get(storagehandlername)

    if (handler.isDefined) {
      handler.get
    } else {
      throw new GeneralAdamException("no handler found for " + storagehandlername)
    }
  }


  /**
    * Returns a map of properties to the entity. Useful for printing.
    */
  def propertiesMap: Map[String, String] = {
    val lb = ListBuffer[(String, String)]()

    lb.append("fieldtype" -> fieldtype.name)
    lb.append("pk" -> pk.toString)

    if (!pk) {
      lb.append("storagehandler" -> storagehandlername)
    }

    lb.append("parameters" -> params.toString())

    lb.toMap
  }
}

