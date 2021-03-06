package org.vitrivr.adampro.utils.importer

import java.sql.{Connection, DriverManager}

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.jdbc.AdamDialectRegistrar
import org.apache.spark.sql.types.DataTypes
import org.vitrivr.adampro.communication.api.EntityOp
import org.vitrivr.adampro.data.datatypes.AttributeTypes
import org.vitrivr.adampro.data.datatypes.AttributeTypes.VECTORTYPE
import org.vitrivr.adampro.data.datatypes.vector.Vector
import org.vitrivr.adampro.data.entity.AttributeDefinition
import org.vitrivr.adampro.process.SharedComponentContext
import org.vitrivr.adampro.utils.Logging

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success, Try}

/**
  * adamtwo
  *
  * Ivan Giangreco
  * May 2016
  *
  * Imports data (specifically for the OSVC database) from ADAM to ADAMpro.
  */
@Experimental
class AdamImporter(url: String, user: String, password: String) extends Logging {
  AdamDialectRegistrar.register(url)

  private def openConnection(): Connection = {
    DriverManager.getConnection(url, user, password)
  }

  private val attributeRenamingRules = Seq(("shotid" -> "id"), ("video" -> "multimediaobject"), ("startframe" -> "segmentstart"), ("endframe" -> "segmentend"), ("frames" -> "framecount"), ("seconds" -> "duration"), ("number" -> "sequencenumber"))
  private val attributeCasting = Seq(("id" -> DataTypes.StringType), ("multimediaobject" -> DataTypes.StringType))

  def importTable(schemaname: String, tablename: String, newtablename: String)(implicit ac: SharedComponentContext) {
    try {
      log.info("importing table " + tablename)

      val entityname = schemaname + "_" + newtablename

      if (EntityOp.exists(entityname).get) {
        log.warn("table " + entityname + " exists already, not importing")
        return
      }

      val df = ac.sqlContext.read.format("jdbc").options(
        Map("url" -> url, "dbtable" -> (schemaname + "." + tablename), "user" -> user, "password" -> password, "driver" -> "org.postgresql.Driver")
      ).load()

      val conn = openConnection()

      val featureResult = conn.createStatement().executeQuery(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = '" + schemaname + "' AND table_name = '" + tablename + "' AND data_type = 'feature';")
      val lb = ListBuffer[String]()
      while (featureResult.next()) {
        lb.append(featureResult.getString(1))
      }
      val featureFields = lb

      var insertDF = df

      import org.apache.spark.sql.functions.udf
      val toVector = udf((c: String) => {
        c.substring(1, c.length - 1).split(",").map(Vector.conv_str2vb)
      })

      df.schema.fields.filter(x => featureFields.contains(x.name)).map { field =>
        val tmpName = field.name + "__" + math.abs(Random.nextInt)
        val newName = if (featureFields.length == 1) {
          "feature"
        } else {
          field.name
        }

        insertDF = insertDF
          .withColumn(tmpName, toVector(insertDF(field.name)))
          .drop(field.name)
          .withColumnRenamed(tmpName, newName)
      }

      attributeRenamingRules.foreach {
        rule => insertDF = insertDF.withColumnRenamed(rule._1, rule._2)
      }


      attributeCasting.foreach {
        rule =>
          if (insertDF.schema.fieldNames.contains(rule._1)) {
            insertDF = insertDF.withColumn(rule._1, insertDF.col(rule._1).cast(rule._2))
          }
      }

      if (tablename == "videos") {
        import org.apache.spark.sql.functions.lit
        insertDF = insertDF.withColumn("type", lit(0))
      }

      var schema = insertDF.schema.fields.map(field => {
        if (featureFields.contains(field.name) || field.name == "feature") {
          val newName = if (featureFields.length == 1) {
            "feature"
          } else {
            field.name
          }

          new AttributeDefinition(newName, VECTORTYPE, storagehandlername = "parquet")
        } else {
          val attributetype = AttributeTypes.fromDataType(field.dataType)
          new AttributeDefinition(field.name, attributetype, storagehandlername = "parquet")
        }
      })


      log.info("creating new entity " + entityname + " with schema: " + schema.map(field => field.name + "(" + field.attributeType.name + ")").mkString(", "))
      val entity = EntityOp.create(entityname, schema, true)

      if (entity.isSuccess) {
        log.info("importing to new entity " + entityname + " with schema: " + insertDF.schema.toString())
        EntityOp.insert(entity.get.entityname, insertDF)

        val insertDFcount = insertDF.count
        val entitycount = EntityOp.count(entityname).get
        if (insertDFcount != entitycount) {
          log.error("missing some tuples in entity " + entityname + "; in df: " + insertDFcount + ", inserted: " + entitycount)
        }
        log.info("successfully imported data into entity " + entityname + "; in df: " + insertDFcount + ", inserted: " + entitycount)
        assert(insertDFcount == entitycount)
      } else {
        log.error("entity not created", entity.failed.get)
      }
    } catch {
      case e: Exception => log.error("error when importing table " + tablename, e)
    }
  }
}

object AdamImporter {
  def apply(host: String, database: String, username: String, password: String)(implicit ac: SharedComponentContext): Try[Void] = {
    try {
      val importer = new AdamImporter("jdbc:postgresql://" + host + "/" + database, username, password)

      val entityRenamingRules = Seq(("shots" -> "segment"), ("videos" -> "multimediaobject")).toMap

      //tables to import
      val cineast = Seq("representativeframes", "resultcachenames", "shots", "videos")
      cineast.map(table => importer.importTable("cineast", table, entityRenamingRules.getOrElse(table, table)))

      val features = Seq("averagecolor", "averagecolorarp44", "averagecolorarp44normalized", "averagecolorcld", "averagecolorcldnormalized", "averagecolorgrid8", "averagecolorgrid8normalized", "averagecolorraster", "averagefuzzyhist", "averagefuzzyhistnormalized", "chromagrid8", "cld", "cldnormalized", "contrast", "dominantcolors", "dominantedgegrid16", "dominantedgegrid8", "edgearp88", "edgearp88full", "edgegrid16", "edgegrid16full", "ehd", "huevaluevariancegrid8", "mediancolor", "mediancolorarp44", "mediancolorarp44normalized", "mediancolorgrid8", "mediancolorgrid8normalized", "mediancolorraster", "medianfuzzyhist", "medianfuzzyhistnormalized", "motionhistogram", "saturationandchroma", "saturationgrid8", "simpleperceptualhash", "stmp7eh", "subdivaveragefuzzycolor", "subdivmedianfuzzycolor", "subdivmotionhistogram2", "subdivmotionhistogram3", "subdivmotionhistogram4", "subdivmotionhistogram5", "surf", "surffull")
      features.map(table => importer.importTable("features", table, entityRenamingRules.getOrElse(table, table)))

      val concepts = Seq("captioned")
      concepts.map(table => importer.importTable("captions", table, entityRenamingRules.getOrElse(table, table)))

      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }
}
