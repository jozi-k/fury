/*

    Fury, version 0.16.1. Copyright 2018-20 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
    compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the License is
    distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and limitations under the License.

*/
package fury

import fury.model._, fury.core._, Args._

import scala.util._

case class EnvCli(cli: Cli)(implicit log: Log) {
  def list: Try[ExitStatus] = for {
    layout       <- cli.layout
    conf         <- Layer.readFuryConf(layout)
    layer        <- Layer.retrieve(conf)
    (cli,
     tryProject,
     tryModule)  <- cli.askProjectAndModule(layer)
    cli          <- cli.hint(RawArg)
    table        <- ~Tables().envs
    cli          <- cli.hint(ColumnArg, table.headings.map(_.name.toLowerCase))
    cli          <- cli.hint(EnvArg, tryModule.map(_.environment).getOrElse(Nil))
    call         <- cli.call()
    col          <- ~cli.peek(ColumnArg)
    env          <- ~cli.peek(EnvArg)
    raw          <- ~call(RawArg).isSuccess
    project      <- tryProject
    module       <- tryModule
  } yield {
    val rows = module.environment.to[List].sorted
    log.infoWhen(!raw)(conf.focus(project.id, module.id))
    log.rawln(Tables().show(table, cli.cols, rows, raw, col, env, "id"))
    log.await()
  }

  def remove: Try[ExitStatus] = for {
    layout       <- cli.layout
    conf         <- Layer.readFuryConf(layout)
    layer        <- Layer.retrieve(conf)
    (cli,
     tryProject,
     tryModule)  <- cli.askProjectAndModule(layer)
    cli          <- cli.hint(EnvArg, tryModule.map(_.environment).getOrElse(Nil))
    cli          <- cli.hint(ForceArg)
    call         <- cli.call()
    envArg       <- call(EnvArg)
    project      <- tryProject
    module       <- tryModule
    force        <- ~call(ForceArg).isSuccess
    layer        <- ~Layer(_.projects(project.id).modules(module.id).environment).modify(layer)(_ - envArg)
    _            <- Layer.commit(layer, conf, layout)
  } yield log.await()

  def add: Try[ExitStatus] = for {
    layout       <- cli.layout
    conf         <- Layer.readFuryConf(layout)
    layer        <- Layer.retrieve(conf)
    (cli,
     tryProject,
     tryModule)  <- cli.askProjectAndModule(layer)
    imported      = layer.importedLayers.toOption
    allLayers     = layer :: imported.toList.flatten
    allModules    = allLayers.map(_.moduleRefs).flatten
    cli          <- cli.hint(EnvArg)
    call         <- cli.call()
    project      <- tryProject
    module       <- tryModule
    envArg       <- call(EnvArg)
    layer        <- ~Layer(_.projects(project.id).modules(module.id).environment).modify(layer)(_ + envArg)
    _            <- Layer.commit(layer, conf, layout)
  } yield log.await()
}