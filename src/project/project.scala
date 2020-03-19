/*
   ╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║ Fury, version 0.8.0. Copyright 2018-20 Jon Pretty, Propensive OÜ.                                         ║
   ║                                                                                                           ║
   ║ The primary distribution site is: https://propensive.com/                                                 ║
   ║                                                                                                           ║
   ║ Licensed under  the Apache License,  Version 2.0 (the  "License"); you  may not use  this file  except in ║
   ║ compliance with the License. You may obtain a copy of the License at                                      ║
   ║                                                                                                           ║
   ║     http://www.apache.org/licenses/LICENSE-2.0                                                            ║
   ║                                                                                                           ║
   ║ Unless required  by applicable law  or agreed to in  writing, software  distributed under the  License is ║
   ║ distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. ║
   ║ See the License for the specific language governing permissions and limitations under the License.        ║
   ╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════╝
*/
package fury

import fury.strings._, fury.io._, fury.core._, fury.model._

import guillotine._
import optometry._
import mercator._
import scala.util._

import scala.collection.immutable.SortedSet

case class ProjectCli(cli: Cli)(implicit log: Log) {
  import Args._

  def select: Try[ExitStatus] = for {
    layout      <- cli.layout
    conf        <- Layer.readFuryConf(layout)
    layer       <- Layer.retrieve(conf)
    cli         <- cli.hint(ProjectArg, layer.projects)
    cli         <- cli.hint(ForceArg)
    call        <- cli.call()
    projectId   <- ~cli.peek(ProjectArg)
    projectId   <- projectId.asTry
    force       <- ~call(ForceArg).isSuccess
    layer       <- ~(Lenses.layer.lens(_.main)(layer) = Some(projectId))
    _           <- Layer.commit(layer, conf, layout)
  } yield log.await()

  def list: Try[ExitStatus] = for {
    layout      <- cli.layout
    conf        <- Layer.readFuryConf(layout)
    layer       <- Layer.retrieve(conf)
    cli         <- cli.hint(ProjectArg, layer.projects)
    cli         <- cli.hint(RawArg)
    table       <- ~Tables().projects(layer.main)
    cli         <- cli.hint(ColumnArg, table.headings.map(_.name.toLowerCase))
    call        <- cli.call()
    projectId   <- ~cli.peek(ProjectArg)
    col         <- ~cli.peek(ColumnArg)
    raw         <- ~call(RawArg).isSuccess
    rows        <- ~layer.projects.to[List]
    table       <- ~Tables().show(table, cli.cols, rows, raw, col, projectId, "project")
    _           <- ~log.infoWhen(!raw)(conf.focus())
    _           <- ~log.rawln(table)
  } yield log.await()

  def add: Try[ExitStatus] = for {
    layout         <- cli.layout
    conf           <- Layer.readFuryConf(layout)
    layer          <- Layer.retrieve(conf)
    cli            <- cli.hint(ProjectNameArg, List(layout.baseDir.name))
    cli            <- cli.hint(LicenseArg, License.standardLicenses)
    cli            <- cli.hint(DefaultCompilerArg, ModuleRef.JavaRef :: layer.compilerRefs(layout, false))
    call           <- cli.call()
    compilerId     <- ~cli.peek(DefaultCompilerArg)
    
    optCompilerRef <- compilerId.to[List].map { v =>
                        ModuleRef.parseFull(v, true).ascribe(InvalidValue(v))
                      }.sequence.map(_.headOption)

    projectId      <- call(ProjectNameArg)
    license        <- Success(call(LicenseArg).toOption.getOrElse(License.unknown))
    project        <- ~Project(projectId, license = license, compiler = optCompilerRef)
    layer          <- ~(Lenses.layer.projects.modify(layer)((_: SortedSet[Project]) + project))
    layer          <- ~(Lenses.layer.mainProject(layer) = Some(project.id))
    _              <- Layer.commit(layer, conf, layout)
    _              <- ~log.info(msg"Set current project to ${project.id}")
  } yield log.await()

  def remove: Try[ExitStatus] = for {
    layout      <- cli.layout
    conf        <- Layer.readFuryConf(layout)
    layer       <- Layer.retrieve(conf)
    cli         <- cli.hint(ProjectArg, layer.projects)
    cli         <- cli.hint(ForceArg)
    call        <- cli.call()
    projectId   <- call(ProjectArg)
    project     <- layer.projects.findBy(projectId)
    force       <- ~call(ForceArg).isSuccess
    layer       <- ~(Lenses.layer.projects.modify(layer)((_: SortedSet[Project]).filterNot(_.id == project.id)))
    layer       <- ~(Lenses.layer.mainProject.modify(layer) { v => if(v == Some(projectId)) None else v })
    _           <- Layer.commit(layer, conf, layout)
  } yield log.await()

  def update: Try[ExitStatus] = for {
    layout         <- cli.layout
    conf           <- Layer.readFuryConf(layout)
    layer          <- Layer.retrieve(conf)
    cli            <- cli.hint(ProjectArg, layer.projects)
    cli            <- cli.hint(DescriptionArg)
    cli            <- cli.hint(DefaultCompilerArg, ModuleRef.JavaRef :: layer.compilerRefs(layout, false))
    cli            <- cli.hint(ForceArg)
    projectId      <- ~cli.peek(ProjectArg).orElse(layer.main)
    cli            <- cli.hint(LicenseArg, License.standardLicenses)
    cli            <- cli.hint(ProjectNameArg, ProjectId(layout.baseDir.name) :: projectId.to[List])
    call           <- cli.call()
    projectId      <- projectId.asTry
    project        <- layer.projects.findBy(projectId)
    force          <- ~call(ForceArg).isSuccess
    licenseArg     <- ~call(LicenseArg).toOption
    layer          <- ~licenseArg.fold(layer)(layer.lens(_.projects(on(project.id)).license)(layer) = _)
    descriptionArg <- ~call(DescriptionArg).toOption
    layer          <- ~descriptionArg.fold(layer)(layer.lens(_.projects(on(project.id)).description)(layer) = _)
    compilerArg    <- ~call(DefaultCompilerArg).toOption.flatMap(ModuleRef.parseFull(_, true))
    layer          <- ~compilerArg.map(Some(_)).fold(layer)(layer.lens(_.projects(on(project.id)).compiler)(layer) = _)
    nameArg        <- ~call(ProjectNameArg).toOption
    newId          <- ~nameArg.flatMap(layer.projects.unique(_).toOption)
    layer          <- ~newId.fold(layer)(layer.lens(_.projects(on(project.id)).id)(layer) = _)
    
    layer          <- if(newId.isEmpty || layer.main != Some(project.id)) ~layer
                      else ~(layer.lens(_.main)(layer) = newId)

    _              <- Layer.commit(layer, conf, layout)
  } yield log.await()
}
