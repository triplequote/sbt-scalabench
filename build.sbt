initialize := {
  initialize.value
  val required = "1.8"
  val current = sys.props("java.specification.version")
  if (current != required)
    sys.error(s"Found JDK $current but JDK $required is required.")
}

lazy val `sbt-scalabench` = project in file(".")

name := "sbt-scalabench"
organization := "com.triplequote"
organizationName := "Triplequote LLC"

homepage := Some(url("https://github.com/triplequote/sbt-scalabench"))
organizationHomepage := Some(url("https://triplequote.com"))
startYear := Some(2019)
licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo := Some(ScmInfo(url("https://github.com/triplequote/sbt-scalabench"), "scm:git:git@github.com:triplequote/sbt-scalabench.git"))

description := "Sbt plugin to benchmark Scala compilation."
sbtPlugin := true
crossSbtVersions := Vector("1.0.0", "0.13.13")
publishMavenStyle := false

// Release
import ReleaseTransformations._
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  releaseStepCommandAndRemaining("^test"),
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("^publish"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
