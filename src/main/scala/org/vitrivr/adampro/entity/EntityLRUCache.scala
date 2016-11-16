package org.vitrivr.adampro.entity

import java.util.concurrent.TimeUnit

import org.vitrivr.adampro.config.AdamConfig
import org.vitrivr.adampro.entity.Entity.EntityName
import org.vitrivr.adampro.main.SparkStartup
import com.google.common.cache.{CacheBuilder, CacheLoader}
import org.vitrivr.adampro.utils.Logging

import scala.util.{Failure, Success, Try}

/**
  * adampro
  *
  * Ivan Giangreco
  * April 2016
  */
class EntityLRUCache extends Logging {

  private val maximumCacheSize = AdamConfig.maximumCacheSizeEntity
  private val expireAfterAccess = AdamConfig.expireAfterAccessEntity

  private val entityCache = CacheBuilder.
    newBuilder().
    maximumSize(maximumCacheSize).
    expireAfterAccess(expireAfterAccess, TimeUnit.MINUTES).
    build(
      new CacheLoader[EntityName, Entity]() {
        def load(entityname: EntityName): Entity = {
          log.trace("cache miss for entity " + entityname + "; loading and caching")
          val entity = Entity.loadEntityMetaData(entityname)(SparkStartup.mainContext)
          entity.get
        }
      }
    )

  /**
    * Gets entity from cache. If entity is not yet in cache, it is loaded.
    *
    * @param entityname name of entity
    */
  def get(entityname: EntityName): Try[Entity] = {
    try {
      log.trace("getting entity " + entityname + " from cache")
      Success(entityCache.get(entityname))
    } catch {
      case e: Exception =>
        Failure(e)
    }
  }

  /**
    * Invalidates the cache entry for entity.
    *
    * @param entityname name of entity
    */
  def invalidate(entityname: EntityName): Unit = {
    entityCache.invalidate(entityname)
  }
}

