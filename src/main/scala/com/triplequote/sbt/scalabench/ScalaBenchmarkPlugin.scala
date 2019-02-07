/*
 * Copyright 2019 Triplequote LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.triplequote.sbt.scalabench

import sbt._
import sbt.Keys._

object ScalaBenchmarkPlugin extends AutoPlugin {

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val scalabenchResultDirectory = settingKey[File]("Directory where the benchmark result is stored")
  }

  import autoImport._

  override def buildSettings: Seq[Def.Setting[_]] = Seq(
    scalabenchResultDirectory := baseDirectory.value
  )

  override lazy val projectSettings = Seq(
    commands ++= Seq(command.ScalaBenchmark.command)
  )

}
