package org.vitrivr.adampro.query.handler.external

import org.vitrivr.adampro.entity.Entity
import org.vitrivr.adampro.entity.Entity._
import org.vitrivr.adampro.main.AdamContext
import org.vitrivr.adampro.query.handler.generic.{ExpressionDetails, QueryEvaluationOptions, QueryExpression}
import org.vitrivr.adampro.storage.StorageHandlerRegistry
import org.apache.spark.sql.DataFrame

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * September 2016
  */
case class GisScanExpression(entityname: EntityName, handlername : String, params: Map[String, String], id: Option[String] = None)(@transient implicit val ac: AdamContext) extends QueryExpression(id) {
  override val info = ExpressionDetails(None, Some("Gis Scan Expression"), id, None)

  private val handler = {
    assert(StorageHandlerRegistry.apply(Some(handlername)).isDefined)
    StorageHandlerRegistry.apply(Some(handlername)).get
  }

  private val entity = Entity.load(entityname).get

  override protected def run(options : Option[QueryEvaluationOptions], filter: Option[DataFrame] = None)(implicit ac: AdamContext): Option[DataFrame] = {
    val attributes = entity.schema().filter(a => a.storagehandler.isDefined && a.storagehandler.get.equals(handler))
    var status = handler.read(entityname, attributes, params = params)

    if (status.isFailure) {
      throw status.failed.get
    }

    var df = status.get

    if (filter.isDefined) {
      df = df.join(filter.get, entity.pk.name)
    }

    Some(df)
  }
}