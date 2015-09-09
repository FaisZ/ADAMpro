package ch.unibas.dmi.dbis.adam.main

import ch.unibas.dmi.dbis.adam.cli.CLI
import ch.unibas.dmi.dbis.adam.config.AdamConfig

import scala.tools.nsc.Settings

/**
 * adamtwo
 *
 * Ivan Giangreco
 * September 2015
 */
class CLIStartup(config : AdamConfig) extends Runnable {
  def run() : Unit = {
    val settings = new Settings
    settings.usejavacp.value = true
    settings.deprecation.value = true

    new CLI().process(settings)
  }
}
