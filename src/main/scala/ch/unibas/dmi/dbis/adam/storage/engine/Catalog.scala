package ch.unibas.dmi.dbis.adam.storage.engine

import slick.driver.H2Driver.api._
import slick.lifted.ForeignKeyQuery


/**
  * adamtwo
  *
  * Ivan Giangreco
  * August 2015
  */
private[engine] class EntitiesCatalog(tag: Tag) extends Table[(String, String, String, Boolean)](tag, "ADAMTWO_ENTITIES") {
  def entityname = column[String]("ENTITYNAME", O.PrimaryKey)

  def featurepath = column[String]("FEATUREPATH")

  def metadatapath = column[String]("METADATAPATH")

  def hasMeta = column[Boolean]("HASMETA")

  def * = (entityname, featurepath, metadatapath, hasMeta)
}

private[engine] class EntityFieldsCatalog(tag: Tag) extends Table[(String, String, Boolean, Boolean, Boolean, String, Int)](tag, "ADAMTWO_FIELDS") {
  def fieldname = column[String]("FIELDNAME")

  def fieldtype = column[String]("FIELDTYPE")

  def pk = column[Boolean]("PRIMARYKEY")

  def unique = column[Boolean]("UNIQUE")

  def indexed = column[Boolean]("INDEXED")

  def entityname = column[String]("ENTITYNAME")

  def featurelength = column[Int]("FEATURELENGTH")

  def * = (fieldname, fieldtype, pk, unique, indexed, entityname, featurelength)

  def supplier: ForeignKeyQuery[EntitiesCatalog, (String, String, String, Boolean)] =
    foreignKey("ENTITYNAME", entityname, TableQuery[EntitiesCatalog])(_.entityname)
}

private[engine] class IndexesCatalog(tag: Tag) extends Table[(String, String, String, String, String, String, Boolean, Float)](tag, "ADAMTWO_INDEXES") {
  def indexname = column[String]("INDEXNAME", O.PrimaryKey)

  def fieldname = column[String]("FIELDNAME")

  def entityname = column[String]("ENTITYNAME")

  def indextypename = column[String]("INDEXTYPENAME")

  def indexpath = column[String]("INDEXPATH")

  def indexmetapath = column[String]("INDEXMETAPATH")

  def uptodate = column[Boolean]("ISUPTODATE")

  def indexweight = column[Float]("WEIGHT")

  def * = (indexname, entityname, fieldname, indextypename, indexpath, indexmetapath, uptodate, indexweight)

  def supplier: ForeignKeyQuery[EntitiesCatalog, (String, String, String, Boolean)] =
    foreignKey("ENTITYNAME", entityname, TableQuery[EntitiesCatalog])(_.entityname)
}

private[engine] object Catalog {
  def apply() = List(
    ("ADAMTWO_ENTITIES", TableQuery[EntitiesCatalog]),
    ("ADAMTWO_FIELDS", TableQuery[EntityFieldsCatalog]),
    ("ADAMTWO_INDEXES", TableQuery[IndexesCatalog])
  )
}