package ch.unibas.dmi.dbis.adam.rpc

import java.io.ByteArrayInputStream

import ch.unibas.dmi.dbis.adam.api.EntityOp
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes.FieldType
import ch.unibas.dmi.dbis.adam.datatypes.feature.{FeatureVectorWrapper, FeatureVectorWrapperUDT}
import ch.unibas.dmi.dbis.adam.entity.Entity
import ch.unibas.dmi.dbis.adam.entity.Entity.EntityName
import ch.unibas.dmi.dbis.adam.exception.GeneralAdamException
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.utils.Logging
import com.google.protobuf.CodedInputStream
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.vitrivr.adam.grpc.grpc.InsertMessage.TupleInsertMessage
import org.vitrivr.adam.grpc.grpc._

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * August 2016
  */
@Experimental class ProtoImporterExporter(entityname: EntityName)(implicit ac: AdamContext) extends Logging {
  //TODO: code is hacky; clean!

  private val BATCH_SIZE = 10000

  private val entity = Entity.load(entityname)
  if (entity.isFailure) {
    throw new GeneralAdamException("cannot load entity")
  }
  private val schema = entity.get.schema()


  /**
    *
    * @param data
    * @return
    */
  def importData(data: Array[Byte]): Try[Void] = {
    try {
      val in = CodedInputStream.newInstance(new ByteArrayInputStream(data))

      val batch = new ListBuffer[Row]()
      var counter = 0

      while (!in.isAtEnd) {
        //parse
        val tuple = TupleInsertMessage.parseFrom(in)
        val data = schema.map(field => {
          val datum = tuple.data.get(field.name).getOrElse(null)
          if (datum != null) {
            RPCHelperMethods.prepareDataTypeConverter(field.fieldtype.datatype)(datum)
          } else {
            null
          }
        })
        batch += Row(data: _*)
        counter += 1

        //if BATCH_SIZE elements collected then insert batch
        if (counter % BATCH_SIZE == 0) {
          insertBatch(batch)
          batch.clear()
        }
      }

      //last batch
      insertBatch(batch)

      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }


  /**
    *
    * @param rows
    */
  private def insertBatch(rows: Seq[Row]): Unit = {
    log.debug("inserting batch")

    val rdd = ac.sc.parallelize(rows)
    val df = ac.sqlContext.createDataFrame(rdd, StructType(entity.get.schema().map(field => StructField(field.name, field.fieldtype.datatype))))

    val res = EntityOp.insert(entity.get.entityname, df)

    if (!res.isSuccess) {
      throw res.failed.get
    }
  }


  /**
    *
    * @return
    */
  def exportData(): Try[(Array[Byte], Array[Byte])] = {
    try {
      //data
      val data = entity.get.getData()
      val cols = data.get.schema

      val messages = data.get.map(row => {
        val metadata = cols.map(col => {
          try {
            col.name -> {
              col.dataType match {
                case BooleanType => DataMessage().withBooleanData(row.getAs[Boolean](col.name))
                case DoubleType => DataMessage().withDoubleData(row.getAs[Double](col.name))
                case FloatType => DataMessage().withFloatData(row.getAs[Float](col.name))
                case IntegerType => DataMessage().withIntData(row.getAs[Integer](col.name))
                case LongType => DataMessage().withLongData(row.getAs[Long](col.name))
                case StringType => DataMessage().withStringData(row.getAs[String](col.name))
                case _: FeatureVectorWrapperUDT => DataMessage().withFeatureData(FeatureVectorMessage().withDenseVector(DenseVectorMessage(row.getAs[FeatureVectorWrapper](col.name).toSeq)))
                case _ => DataMessage().withStringData("")
              }
            }
          } catch {
            case e: Exception => col.name -> DataMessage().withStringData("")
          }
        }).toMap

        TupleInsertMessage(metadata)
      })

      //TODO: this will only work for small collections! (improve by replace collect, temporarily write to disk)
      val dataout = new ByteOutputStream()
      messages.collect().foreach(_.writeTo(dataout))
      dataout.flush()

      val datafile = dataout.getBytes


      //catalog
      val catalogout = new ByteOutputStream()
      val attributes = entity.get.schema().map(attribute => {
        val handler = attribute.storagehandler.get.name match {
          case "relational" => HandlerType.RELATIONAL
          case "file" => HandlerType.FILE
          case "solr" => HandlerType.SOLR
          case _ => HandlerType.UNKNOWNHT
        }

        AttributeDefinitionMessage(attribute.name, matchFields(attribute.fieldtype), attribute.pk, attribute.unique, attribute.indexed, handler)
      })

      val createEntity = new CreateEntityMessage(entityname, attributes)

      createEntity.writeTo(catalogout)
      catalogout.flush()

      val catalogfile = catalogout.getBytes

      Success((catalogfile, datafile))
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param ft
    * @return
    */
  private def matchFields(ft: FieldType) = ft match {
    case FieldTypes.BOOLEANTYPE => AttributeType.BOOLEAN
    case FieldTypes.DOUBLETYPE => AttributeType.DOUBLE
    case FieldTypes.FLOATTYPE => AttributeType.FLOAT
    case FieldTypes.INTTYPE => AttributeType.INT
    case FieldTypes.LONGTYPE => AttributeType.LONG
    case FieldTypes.STRINGTYPE => AttributeType.STRING
    case FieldTypes.TEXTTYPE => AttributeType.TEXT
    case FieldTypes.FEATURETYPE => AttributeType.FEATURE
    case _ => AttributeType.UNKOWNAT
  }
}
