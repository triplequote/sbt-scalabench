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

package com.triplequote.internal.sbt.scalabench.compat

import sbt._

object SbtCompatImpl extends SbtCompat {
  val BuildUtil = sbt.BuildUtil
  val InternalSettingCompletions = sbt.InternalSettingCompletions

  override def getOrError[T](scope: Scope, key: AttributeKey[_], value: Option[T])(implicit display: Show[ScopedKey[_]]): T =
    value getOrElse sys.error(display(Def.ScopedKey(scope, key)) + " is undefined.")
}
