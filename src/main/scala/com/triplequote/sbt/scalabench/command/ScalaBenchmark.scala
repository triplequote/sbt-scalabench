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

package com.triplequote.sbt.scalabench.command

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import scala.collection.immutable
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.SECONDS

import com.triplequote.internal.sbt.scalabench.compat.SbtCompatImpl
import com.triplequote.sbt.scalabench.ScalaBenchmarkPlugin

import sbt._
import sbt.complete.DefaultParsers._
import sbt.complete.Parser.token

object ScalaBenchmark {
  private val ScalaBenchmarkCommand = "scalabench"
  private val DefaultNumberOfRuns = 10
  private val numberOfRunsDescription = "<number-of-runs>"

  private lazy val input = Space ~> numberOfRuns.examples(DefaultNumberOfRuns.toString)
  private lazy val numberOfRuns = token(NatBasic, numberOfRunsDescription)

  private def scalaBenchmarkDetailed = s"""$ScalaBenchmarkCommand $numberOfRunsDescription
  Benchmarks Scala compilation by running $numberOfRunsDescription full compilation cycles.

  For each aggregate or dependent project of the current project, run $numberOfRunsDescription full
  compilation cycles of both main and test sources. When finished, a comprehensive summary is printed.
"""

  private def scalaBenchmarkHelp: Help = Help.more(ScalaBenchmarkCommand, scalaBenchmarkDetailed)

  def command: Command = Command(ScalaBenchmarkCommand, scalaBenchmarkHelp)(_ => input){ (state, runs) =>
    val sortedProjects = topologicallySortedProjects(state)

    if (sortedProjects.isEmpty) {
      state.log.error("Benchmark couldn't start because there are no Scala projects in this build.")
    }
    else {
      printBenchmarkPreamble(state)

      val scalaStats = runBenchmark(state, sortedProjects, runs)

      val scalaVersion = scalaStats.keys.map(_.scalaVersion).toSet

      val outputDir = (ScalaBenchmarkPlugin.autoImport.scalabenchResultDirectory).get(Project.extract(state).structure.data).getOrElse(file("."))

      if (scalaVersion.size > 1)
        state.log.info(s"More than one Scala version detected: $scalaVersion, see full json report in " + outputDir)

      val report = showReports(state, runs, scalaStats, scalaVersion.head)
      state.log.info(report)

      val timestamp = localTimeFormatter.format(Instant.now)
      IO.write(outputDir / s"scalabench-result-$timestamp.txt", report)
      IO.write(outputDir / s"scalabench-result-$timestamp.json", jsonReport(scalaStats))

      // 5) Log all collected statistics
      logStatistics(state, scalaStats)
    }

    // returns the initial state, unchanged (i.e., this command's execution produces no side-effect to the build's state)
    state
  }

  private val localTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd@HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

  private def jsonReport(scalaStats: TreeMap[BenchmarkId, Statistics]): String = {
    def jsonify(stats: Map[BenchmarkId, Statistics]) = for ((id, st) <- stats) yield id.toJSON(st)

    s"""
       |{
       |  "timestamp": "${localTimeFormatter.format(Instant.now())}",
       |  "scala_runs": [${jsonify(scalaStats).mkString(",\n")}]
       |}
     """.stripMargin
  }

  /**
    * Inspects the current project and returns a topologically sorted sequence of all the project's classpath dependencies
    * together with the current project's aggregates.
    */
  private def topologicallySortedProjects(state: State): Vector[ProjectRef] = {
    val ctx = Project.extract(state)
    val bd = SbtCompatImpl.BuildUtil.dependencies(ctx.structure.units)

    // Get all the aggregates for the current project
    val aggregates = bd.aggregateTransitive.getOrElse(ctx.currentRef, Nil) :+ ctx.currentRef

    // Collect all classpath dependencies' projects for each aggregate (transitive closure). The returned projects
    // are topologically sorted and contain no duplicate.
    val cpDeps = aggregates.foldLeft(Vector.empty[ProjectRef]) { (acc, aggregateRef) =>
      // This returns a topologically sorted transitive closure
      val depRefs = bd.classpathTransitive.getOrElse(aggregateRef, Nil)
      val intersect = acc.intersect(depRefs)
      if (depRefs.nonEmpty && intersect.length < depRefs.length)
        acc ++ (depRefs diff intersect)
      else acc
    }

    // Append the aggregates to the collected classpath dependencies' projects (again, ensuring there are no duplicate)
    cpDeps ++ (aggregates diff cpDeps)
  }

  private def printBenchmarkPreamble(state: State): Unit = {
    state.log.info("Please, make sure all applications that might be eating up on CPU (e.g., IDEs, browsers, VMs, and similar) are closed. Also, try not to use this computer while the benchmark is running, as it might skew the reported results.")

    // Allowing some time so that the user can read the above message
    val waitTimeInSeconds = 5
    state.log.info(s"Benchmark starting in $waitTimeInSeconds seconds.")
    Thread.sleep(waitTimeInSeconds * 1000)
    state.log.info("Benchmark started.")
  }

  private def windowed(msg: String): String = {
    val width = 80
    val buf = new StringBuffer()
    val indent = (width - msg.length)/2
    buf.append("-" * 80 + "\n")
    buf.append(" " * indent + msg + "\n")
    buf.append("-" * 80 + "\n")
    buf.toString
  }

  /**
    * Running the benchmark consists in:
    * 1) Warming up the JVM.
    * 2) Collecting the times it takes to compile the passed `projects` (this step is executed `numberOfRuns` times).
    * 3) Create a `Statistics` instance for each compiled project+configuration.
    */
  private def runBenchmark(state: State, projects: Vector[ProjectRef], numberOfRuns: Int): immutable.TreeMap[BenchmarkId, Statistics] = {
    state.log.info(windowed("Warming up JVM."))
    warmUpJVM(state, projects)

    state.log.info(windowed("Running benchmark."))
    val times = collectCompileTimes(state, projects, numberOfRuns)
    computeStatistics(times)
  }

  /**
    * Warming up the JVM by executing at least three full compilation of both main and test sources.
    * @note There is an implicit dependency between `warmUpJVM` and `collectCompileTimes`. The latter relies on the
    *       former to execute all tasks depending on `sbt.Keys.compile`. The reason is that we want `collectCompileTimes`
    *       to collect the time it takes to carry out compilation alone. In particular, the time used for resolving
    *       dependencies (sbt.Keys.update), source generation, formatting sources, or enforcing code styling, must not be
    *       added to the compilation times collected in `collectCompileTimes`.
    * @postcondition All `projects` main and test sources are compiled at least once.
    */
  private def warmUpJVM(state: State, projects: Vector[ProjectRef]): Unit = {
    def runTaskOnProjects(task: TaskKey[_], projects: Seq[ProjectRef]): Unit = {
      projects.foreach { project =>
        val ctx = Project.extract(state)
        ctx.runTask(task in project, state)
      }
    }

    // start by cleaning target dirs (not calling `ctx.runTask(Keys.clean in project, state)` because of https://github.com/sbt/sbt/issues/2156)
    deleteProjectsFolder(state, projects)(Keys.target)

    // If there are more than 3 projects, then it should be enough to compile each of them just once to warm up the JVM.
    // Each project is likely to have tests, hence it's expected that 6 full compile cycles are run.
    val compilationRuns = if (projects.length > 3) 1 else 3
    for { _ <- Vector.range(0, compilationRuns)} {
      // only deleting the class directory to avoid that expensive tasks such as `update` are ran again
      cleanProjectsClassDirectory(state, projects)
      runTaskOnProjects(Keys.compile in Compile, projects)
      runTaskOnProjects(Keys.compile in Test, projects)
    }
  }

  /**
    * Compile all `projects` main and test sources and return the recorded compile times.
    * @precondition `warmUpJVM` was called right before this method.
    */
  private def collectCompileTimes(state: State, projects: Vector[ProjectRef], numRuns: Int): Vector[Vector[TimedCompilation]] = {

    val extracted = Project.extract(state)
    def scalaVersion(project: ProjectRef) = (Keys.scalaVersion in project).get(extracted.structure.data).getOrElse("unknown")
    def timedCompilation(state: State, projects: Vector[ProjectRef], configurations: Seq[Configuration]): Vector[Vector[TimedCompilation]] = {

      def timed(body: => Unit): Duration = {
        val start = System.currentTimeMillis()
        body
        val time = System.currentTimeMillis() - start
        Duration(time, MILLISECONDS)
      }

      def timedCompile(project: ProjectRef, configuration: Configuration): TimedCompilation = {
        val compileTask = Keys.compile
        val time = timed(runTaskWithoutTriggers(compileTask in (project, configuration), state))
        val id = BenchmarkId(project.project, configuration.name, compileTask.key.label, scalaVersion(project))
        state.log.info(s" >>> Compiled ${id.fullText}, time: ${formatTime(time).trim()}")
        TimedCompilation(id, time)
      }

      for {
        project <- projects
        configuration <- configurations
      } yield for (_ <- (1 to numRuns).toVector) yield {

        // Only deleting the class directory because the `compile` task alone (i.e., without executing its dependencies) is
        // executed when calling `timedCompilation`. For instance, generated sources must not be deleted, as otherwise
        // compilation may fail.
        deleteProjectFolder(state, project, Some(configuration))(Keys.classDirectory)
        timedCompile(project, configuration)
      }
    }

    timedCompilation(state, projects, Seq(Compile, Test))
  }

  private def cleanProjectsClassDirectory(state: State, projects: Vector[ProjectRef]): Unit = {
    deleteProjectsFolder(state, projects, Seq(Compile, Test))(Keys.classDirectory)
  }

  private def deleteProjectsFolder(state: State, projects: Vector[ProjectRef], configurations: Seq[Configuration] = Nil)(key: SettingKey[File]): Unit = {
    for (project <- projects) {
      if (configurations.isEmpty) deleteProjectFolder(state, project, None)(key)
      else {
        for (config <- configurations) deleteProjectFolder(state, project, Some(config))(key)
      }
    }
  }

  private def deleteProjectFolder(state: State, project: ProjectRef, configuration: Option[Configuration])(key: SettingKey[File]): Unit = {
    val ctx = Project.extract(state)
    if (configuration.isEmpty) {
      val dir = key in project get ctx.structure.data
      IO.delete(dir)
    } else {
      for {
        config <- configuration
        dir <- key in (project, config) get ctx.structure.data
      } IO.delete(dir)
    }
  }

  /**
    * The passed `dataset` should be seen as a table where each column is a benchmark run, and each row is a
    * `BenchmarkId`. A cell contains the compilation time for the specific project and run.
    * The `dataset` table is processed row by row, and the result of each benchmark run is added into a
    * `Statistics` instance, which can later be used for creating the final report printed to the user.
    */
  private def computeStatistics(dataset: Vector[Vector[TimedCompilation]]): immutable.TreeMap[BenchmarkId, Statistics] = {
    (for {
      (id, times) <- dataset.flatten.groupBy(_.id)
    } yield {
      val compileTimes = times.map(_.time)
      (id, new Statistics(compileTimes))
    })(collection.breakOut)
  }

  /** Takes the benchmarks' results in argument and prints the final report to the user. */
  private def showReports(state: State,
                          numberOfRuns: Int,
                          scalaStats: immutable.TreeMap[BenchmarkId, Statistics],
                          scalaVersion: String): String = {
    val idMaxLength = maxIdLength(scalaStats)

    def createSummary(summaryMsg: String, stats: immutable.TreeMap[BenchmarkId, Statistics]): String = {
      stats.foldLeft(summaryMsg) { case (acc, (id, stat)) =>
        acc + "\n\t" + s"${id.fullText.padTo(idMaxLength, " ").mkString} - median: ${formatTime(stat.median)}, min: ${formatTime(stat.min)}, max: ${formatTime(stat.max)}"
      }
    }

    val tooShortCompileTime = for {
      (id, stats) <- scalaStats
      scalaCompileTime <- scalaStats.get(id)
      medianCompileTime =  scalaCompileTime.median
    } yield id


    val buffer = new StringBuilder(1024) // initial capacity

    buffer ++= createSummary(s"\nScala ${scalaVersion} compilation statistics summary (#runs = $numberOfRuns):", scalaStats)

    def showAggregateCompileTime(state: State, scalaStats: immutable.TreeMap[BenchmarkId, Statistics]): String = {
      def aggregateDuration(stats: immutable.TreeMap[BenchmarkId, Statistics]): Duration = {
        stats.foldLeft(FiniteDuration(0, SECONDS): Duration) { case (acc, (_, stat)) =>
          acc + stat.median
        }
      }
      val scalaAggregateTime = aggregateDuration(scalaStats)

      s"\nThe aggregate compile time is ${formatTime(scalaAggregateTime).trim()}."
    }

    buffer ++= showAggregateCompileTime(state, scalaStats)
    buffer.toString
  }

  /**
    * Runs the passed task without executing any of its dependencies (triggers are set to empty).
    * Note: Implementation heavily inspired by sbt.Extracted#runTask (https://github.com/sbt/sbt/blob/0.13/main/src/main/scala/sbt/Extracted.scala#L39-L47)
    */
  private def runTaskWithoutTriggers[T](key: TaskKey[T], state: State): (State, T) = {
    // copy of sbt.Extracted#resolve (https://github.com/sbt/sbt/blob/0.13/main/src/main/scala/sbt/Extracted.scala#L91-L92)
    def resolve[A](ctx: Extracted, key: ScopedKey[A]): ScopedKey[A] =
      Project.mapScope(Scope.resolveScope(GlobalScope, ctx.currentRef.build, ctx.rootProject))(key.scopedKey)

    import EvaluateTask._
    val ctx = Project.extract(state)
    val rkey = resolve(ctx, key.scopedKey)
    val config = extractedTaskConfig(ctx, ctx.structure, state)
    val value: Option[(State, Result[T])] = {
      val noTriggers = new Triggers[Task](Map.empty, Map.empty, idFun)
      withStreams(ctx.structure, state) { str =>
        for ((task, toNode) <- getTask(ctx.structure, rkey.scopedKey, state, str, ctx.currentRef)) yield runTask(task, state, str, noTriggers, config)(toNode)
      }
    }
    val (newS, result) = SbtCompatImpl.getOrError(rkey.scope, rkey.key, value)(ctx.showKey)
    (newS, processResult(result, newS.log))
  }

  private def formatTime(time: Duration): String = {
    def formatSecondsWithMilliseconds(time: Duration) = f"${time.toSeconds}%.0f.${time.toMillis % 1000}%03ds"
    def formatSeconds(value: Double) = f"$value%.0fs"
    def formatMinutes(value: Double) = f"${Math.floor(value/60)}%.0fm" + (if (value % 60 == 0) "" else formatSeconds(value % 60))
    if(time < FiniteDuration(30, SECONDS)) formatSecondsWithMilliseconds(time)
    else if (time < FiniteDuration(60, SECONDS)) formatSeconds(time.toSeconds)
    else formatMinutes(time.toSeconds)
  }.padTo(7, " ").mkString

  private def maxIdLength(stats: immutable.TreeMap[BenchmarkId, Statistics]): Int = stats.foldLeft(0) { (maxLength, entry) =>
    val (id, _) = entry
    Math.max(maxLength, id.fullText.length)
  }

  private def logStatistics(state: State, scalaStats: immutable.TreeMap[BenchmarkId, Statistics]): Unit = {
    def logSummary(summaryMsg: String, stats: immutable.TreeMap[BenchmarkId, Statistics]): String = {
      val idMaxLength = maxIdLength(stats)
      stats.foldLeft(summaryMsg) { case (acc, (id, stat)) =>
        acc + "\n\t" + s"${id.fullText.padTo(idMaxLength, " ").mkString} - collected times: ${stat.times.map(formatTime).mkString(", ")}"
      }
    }
    state.log.debug(logSummary(s"Scala full compilation statistics:", scalaStats))
  }

  private case class BenchmarkId(projectName: String, configuration: String, task: String, scalaVersion: String) extends Ordered[BenchmarkId] {
    override def compare(that: BenchmarkId): Int =  {
      val nameComp = this.projectName.compareTo(that.projectName)
      if (nameComp != 0) return nameComp
      val configComp = this.configuration.compareTo(that.configuration)
      if (configComp != 0) return configComp
      this.task.compareTo(that.task)
    }

    def shortText: String = s"$projectName/$configuration"
    def fullText: String = s"$shortText:$task"
    override def toString: String = fullText

    def toJSON(stats: Statistics): String =
      s"""
         |{
         |  "project": "$projectName",
         |  "configuration": "$configuration",
         |  "task": "$task",
         |  "scala_version": "$scalaVersion"
         |  "durations": [ ${stats.times.map(_.toMillis).mkString(", ")} ]
         |}
       """.stripMargin
  }
  private case class TimedCompilation(id: BenchmarkId, time: Duration)

  private class Statistics(val times: Vector[Duration]) {
    require(times.nonEmpty, "Expected non empty set of times")
    private val incrementallySorted = times.sorted
    def min: Duration = incrementallySorted.head
    def max: Duration = incrementallySorted.last
    def median: Duration = incrementallySorted(incrementallySorted.length / 2)
  }
}

