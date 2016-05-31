package ch.unibas.dmi.dbis.adam.index.structures.va

import ch.unibas.dmi.dbis.adam.config.FieldNames
import ch.unibas.dmi.dbis.adam.datatypes.bitString.BitStringUDT
import ch.unibas.dmi.dbis.adam.datatypes.feature.Feature.{FeatureVector, VectorBase}
import ch.unibas.dmi.dbis.adam.entity.Entity
import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.exception.QueryNotConformException
import ch.unibas.dmi.dbis.adam.index.Index.{IndexName, IndexTypeName}
import ch.unibas.dmi.dbis.adam.index._
import ch.unibas.dmi.dbis.adam.index.structures.IndexTypes
import ch.unibas.dmi.dbis.adam.index.structures.va.marks.{EquidistantMarksGenerator, EquifrequentMarksGenerator, MarksGenerator}
import ch.unibas.dmi.dbis.adam.index.structures.va.signature.FixedSignatureGenerator
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.query.distance.{DistanceFunction, MinkowskiDistance}
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.util.random.ADAMSamplingUtils


/**
  *
  */
class VAFIndexer(maxMarks: Int = 64, marksGenerator: MarksGenerator, bitsPerDimension: Int, trainingSize: Int, distance: MinkowskiDistance)(@transient implicit val ac: AdamContext) extends IndexGenerator {
  override val indextypename: IndexTypeName = IndexTypes.VAFINDEX

  /**
    *
    */
  override def index(indexname: IndexName, entityname: EntityName, data: RDD[IndexingTaskTuple[_]]): Index = {
    val entity = Entity.load(entityname).get

    val n = entity.count
    val fraction = ADAMSamplingUtils.computeFractionForSampleSize(math.max(trainingSize, IndexGenerator.MINIMUM_NUMBER_OF_TUPLE), n, false)
    var trainData = data.sample(false, fraction).collect()
    if(trainData.length < IndexGenerator.MINIMUM_NUMBER_OF_TUPLE){
      trainData = data.take(IndexGenerator.MINIMUM_NUMBER_OF_TUPLE)
    }

    val indexMetaData = train(trainData)

    log.debug("VA-File (fixed) indexing...")

    val indexdata = data.map(
      datum => {
        val cells = getCells(datum.feature, indexMetaData.marks)
        val signature = indexMetaData.signatureGenerator.toSignature(cells)
        Row(datum.id, signature)
      })

    val schema = StructType(Seq(
      StructField(entity.pk.name, entity.pk.fieldtype.datatype, false),
      StructField(FieldNames.featureIndexColumnName, new BitStringUDT, false)
    ))

    val df = ac.sqlContext.createDataFrame(indexdata, schema)

    new VAIndex(indexname, entityname, df, indexMetaData)
  }

  /**
    *
    * @param trainData
    * @return
    */
  private def train(trainData: Array[IndexingTaskTuple[_]]): VAIndexMetaData = {
    log.trace("VA-File (fixed) started training")

    val dim = trainData.head.feature.length

    val signatureGenerator = new FixedSignatureGenerator(dim, bitsPerDimension)
    val marks = marksGenerator.getMarks(trainData, maxMarks)

    log.trace("VA-File (fixed) finished training")

    VAIndexMetaData(marks, signatureGenerator, distance)
  }


  /**
    *
    */
  @inline private def getCells(f: FeatureVector, marks: Seq[Seq[VectorBase]]): Seq[Int] = {
    f.toArray.zip(marks).map {
      case (x, l) =>
        val index = l.toArray.indexWhere(p => p >= x, 1)
        if (index == -1) l.length - 1 - 1 else index - 1
    }
  }
}

object VAFIndexer {
  lazy val log = Logger.getLogger(getClass.getName)

  /**
    *
    * @param properties
    */
  def apply(distance: DistanceFunction, properties: Map[String, String] = Map[String, String]())(implicit ac: AdamContext): IndexGenerator = {
    val maxMarks = properties.getOrElse("nmarks", "64").toInt

    if (!distance.isInstanceOf[MinkowskiDistance]) {
      log.error("only Minkowski distances allowed for VAF Indexer")
      throw new QueryNotConformException()
    }

    val marksGeneratorDescription = properties.getOrElse("marktype", "equifrequent")
    val marksGenerator = marksGeneratorDescription.toLowerCase match {
      case "equifrequent" => EquifrequentMarksGenerator
      case "equidistant" => EquidistantMarksGenerator
    }

    val fixedNumBitsPerDimension = properties.getOrElse("signature-nbits-dim", math.ceil(scala.math.log(maxMarks) / scala.math.log(2)).toInt.toString).toInt
    val trainingSize = properties.getOrElse("ntraining", "5000").toInt

    new VAFIndexer(maxMarks, marksGenerator, fixedNumBitsPerDimension, trainingSize, distance.asInstanceOf[MinkowskiDistance])
  }
}