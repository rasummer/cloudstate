/*
 * Copyright 2019 Lightbend Inc.
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

package io.cloudstate.operator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

object OperatorMain extends App {

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val namespaces = sys.env.get("NAMESPACES").fold(List("default"))(_.split(",").toList)

  val runner = new OperatorRunner()
  //runner.start(namespaces, new KnativeRevisionOperatorFactory())
  runner.start(namespaces, new JournalOperatorFactory())
  runner.start(namespaces, new StatefulServiceOperatorFactory())
}