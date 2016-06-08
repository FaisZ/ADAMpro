package ch.unibas.dmi.dbis.adam.client.grpc

import java.util.concurrent.TimeUnit

import ch.unibas.dmi.dbis.adam.client.web.datastructures.{CompoundQueryDetails, CompoundQueryRequest, EntityField}
import ch.unibas.dmi.dbis.adam.http.grpc.AdamDefinitionGrpc.AdamDefinitionBlockingStub
import ch.unibas.dmi.dbis.adam.http.grpc.AdamSearchGrpc.{AdamSearchBlockingStub, AdamSearchStub}
import ch.unibas.dmi.dbis.adam.http.grpc.DataMessage.Datatype
import ch.unibas.dmi.dbis.adam.http.grpc.DistanceMessage.DistanceType
import ch.unibas.dmi.dbis.adam.http.grpc.FieldDefinitionMessage.FieldType
import ch.unibas.dmi.dbis.adam.http.grpc.RepartitionMessage.PartitionOptions
import ch.unibas.dmi.dbis.adam.http.grpc._
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.apache.log4j.Logger

import scala.util.{Failure, Success, Try}

/**
  * adamtwo
  *
  * Ivan Giangreco
  * March 2016
  */
class RPCClient(channel: ManagedChannel, definer: AdamDefinitionBlockingStub, searcherBlocking: AdamSearchBlockingStub, searcher: AdamSearchStub) {
  val log = Logger.getLogger(getClass.getName)

  /**
    *
    * @param entityname
    * @param fields
    * @return
    */
  def createEntity(entityname: String, fields: Seq[EntityField]): Try[String] = {
    try {
      log.info("creating entity")

      val fieldMessage = fields.map(field =>
        FieldDefinitionMessage(field.name, getFieldType(field.datatype), field.pk, false, field.indexed)
      )

      val res = definer.createEntity(CreateEntityMessage(entityname, fieldMessage))
      if (res.code == AckMessage.Code.OK) {
        return Success(res.message)
      } else {
        return Failure(new Exception(res.message))
      }
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param entityname
    */
  def dropEntity(entityname: String): Try[Void] = {
    try {
      definer.dropEntity(EntityNameMessage(entityname))
      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }

  def benchmarkAndAdjustWeights(entityname : String, column : String) : Try[Void] = {
    try {
      definer.benchmarkAndUpdateScanWeights(WeightMessage(entityname, column))
      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }


  /**
    *
    * @param entityname
    * @param ntuples
    * @param ndims
    * @param fields
    * @return
    */
  def prepareDemo(entityname: String, ntuples: Int, ndims: Int, fields: Seq[EntityField]): Try[Void] = {
    try {
      log.info("preparing demo data")
      val fieldMessage = fields.map(field =>
        FieldDefinitionMessage(field.name, getFieldType(field.datatype), false, false, field.indexed)
      )

      val res = definer.generateRandomData(GenerateRandomDataMessage(entityname, ntuples, ndims))

      if (res.code == AckMessage.Code.OK) {
        return Success(null)
      } else {
        return Failure(new Exception(res.message))
      }
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param s
    * @return
    */
  private def getFieldType(s: String): FieldDefinitionMessage.FieldType = s match {
    case "feature" => FieldType.FEATURE
    case "long" => FieldType.LONG
    case "int" => FieldType.INT
    case "float" => FieldType.FLOAT
    case "double" => FieldType.DOUBLE
    case "string" => FieldType.STRING
    case "boolean" => FieldType.BOOLEAN
    case _ => null
  }


  /**
    *
    * @return
    */
  def listEntities(): Try[Seq[String]] = {
    log.info("listing entities")

    try {
      Success(definer.listEntities(EmptyMessage()).entities)
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @return
    */
  def getDetails(entityname: String): Try[Map[String, String]] = {
    log.info("retrieving entity details")

    try {
      val count = definer.count(EntityNameMessage(entityname))
      val properties = definer.getEntityProperties(EntityNameMessage(entityname)).properties
      Success(properties.+("count" -> definer.count(EntityNameMessage(entityname)).message))
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param entityname
    * @param indextype
    * @param norm
    * @param options
    * @return
    */
  def addIndex(entityname: String, column: String, indextype: IndexType, norm: Int, options: Map[String, String]): Try[String] = {
    log.info("adding index")

    try {
      val indexMessage = IndexMessage(entityname, column, indextype, Some(DistanceMessage(DistanceType.minkowski, Map("norm" -> norm.toString))), options)
      val res = definer.index(indexMessage)

      if (res.code == AckMessage.Code.OK) {
        return Success(res.message)
      } else {
        return Failure(new Exception(res.message))
      }
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param entityname
    * @return
    */
  def addAllIndex(entityname: String, fields: Seq[EntityField], norm: Int): Try[Void] = {
    log.info("adding all index")

    try {
      val fieldMessage = fields.map(field =>
        FieldDefinitionMessage(field.name, getFieldType(field.datatype), false, false, field.indexed)
      ).filter(_.fieldtype == FieldType.FEATURE)

      fieldMessage.map { column =>
        val res = definer.generateAllIndexes(IndexMessage(entity = entityname, column = column.name, distance = Some(DistanceMessage(DistanceType.minkowski, options = Map("norm" -> norm.toString)))))
        if (res.code != AckMessage.Code.OK) {
          return Failure(new Exception(res.message))
        }
      }

      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param entityname
    */
  def preview(entityname: String): Try[Seq[Map[String, String]]] = {
    try {
      val res = searcherBlocking.preview(EntityNameMessage(entityname))

      val readable = res.responses.head.results.map(tuple => {
        tuple.data.map(attribute => {
          val key = attribute._1
          val value = attribute._2.datatype match {
            case Datatype.IntData(x) => x.toInt.toString
            case Datatype.LongData(x) => x.toLong.toString
            case Datatype.FloatData(x) => x.toFloat.toString
            case Datatype.DoubleData(x) => x.toDouble.toString
            case Datatype.StringData(x) => x.toString
            case Datatype.BooleanData(x) => x.toString
            case Datatype.FeatureData(x) => x.feature.denseVector.get.vector.mkString("[", ",", "]")
            case _ => ""
          }
          key -> value
        })
      })

      Success(readable)
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param request
    * @return
    */
  def compoundQuery(request: CompoundQueryRequest): Try[CompoundQueryDetails] = {
    log.info("compound query start")

    try {
      val res = searcherBlocking.doQuery(request.toRPCMessage())
      if (res.ack.get.code == AckMessage.Code.OK) {
        return Success(new CompoundQueryDetails(res))
      } else {
        return Failure(new Exception(res.ack.get.message))
      }

    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    */
  def progressiveQuery(id: String, entityname: String, query: Seq[Float], column: String, hints: Seq[String], k: Int, next: (String, Double, String, Long, Seq[Map[String, String]]) => (Unit), completed: (String) => (Unit)): Try[Void] = {
    log.info("progressive query start")

    try {
      val fv = FeatureVectorMessage().withDenseVector(DenseVectorMessage(query))
      val nnq = NearestNeighbourQueryMessage(column, Some(fv), None, Option(DistanceMessage(DistanceType.minkowski, Map("norm" -> "2"))), k, indexOnly = true)
      val request = QueryMessage(from = Some(FromMessage().withEntity(entityname)), hints = hints, nnq = Option(nnq))

      val so = new StreamObserver[QueryResultsMessage]() {
        override def onError(throwable: Throwable): Unit = {
          log.error(throwable)
        }

        override def onCompleted(): Unit = {
          completed(id)
        }

        override def onNext(qr: QueryResultsMessage): Unit = {
          log.info("new progressive results arrived")

          if (qr.ack.get.code == AckMessage.Code.OK && !qr.responses.isEmpty) {
            val head = qr.responses.head

            val confidence = head.confidence
            val source = head.source
            val time = head.time
            val results = head.results.map(x => x.data.mapValues(x => ""))

            next(id, confidence, source, time, results)
          } else {
            Failure(new Exception(qr.ack.get.message))
          }
        }
      }

      searcher.doProgressiveQuery(request, so)
      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }


  /**
    *
    * @param entity
    * @param partitions
    * @return
    */
  def repartitionEntity(entity: String, partitions: Int, cols: Seq[String] = Seq(), materialize: Boolean, replace: Boolean): Try[String] = {
    log.info("repartitioning entity")

    try {
      val option = if (replace) {
        PartitionOptions.REPLACE_EXISTING
      } else if (materialize) {
        PartitionOptions.CREATE_NEW
      } else if (!materialize) {
        PartitionOptions.CREATE_TEMP
      } else {
        PartitionOptions.CREATE_NEW
      }

      val res = definer.repartitionEntityData(RepartitionMessage(entity, partitions, cols, option))

      if (res.code == AckMessage.Code.OK) {
        Success(res.message)
      } else {
        Failure(throw new Exception(res.message))
      }
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param index
    * @param partitions
    * @return
    */
  def repartitionIndex(index: String, partitions: Int, cols: Seq[String] = Seq(), materialize: Boolean, replace: Boolean): Try[String] = {
    log.info("repartitioning index")

    try {
      val option = if (replace) {
        PartitionOptions.REPLACE_EXISTING
      } else if (materialize) {
        PartitionOptions.CREATE_NEW
      } else if (!materialize) {
        PartitionOptions.CREATE_TEMP
      } else {
        PartitionOptions.CREATE_NEW
      }

      val res = definer.repartitionIndexData(RepartitionMessage(index, partitions, cols, option))

      if (res.code == AckMessage.Code.OK) {
        Success(res.message)
      } else {
        Failure(throw new Exception(res.message))
      }
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    * @param host
    * @param database
    * @param username
    * @param password
    * @return
    */
  def importData(host: String, database: String, username: String, password: String): Try[Void] = {
    try {
      definer.importData(ImportMessage(host, database, username, password))
      Success(null)
    } catch {
      case e: Exception => Failure(e)
    }
  }

  /**
    *
    */
  def shutdown(): Unit = {
    channel.shutdown.awaitTermination(5, TimeUnit.SECONDS)
  }
}


object RPCClient {
  def apply(host: String, port: Int): RPCClient = {
    val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext(true).asInstanceOf[ManagedChannelBuilder[_]].build()

    new RPCClient(
      channel,
      AdamDefinitionGrpc.blockingStub(channel),
      AdamSearchGrpc.blockingStub(channel),
      AdamSearchGrpc.stub(channel)
    )
  }
}