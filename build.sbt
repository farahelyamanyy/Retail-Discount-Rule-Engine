ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.13"

scalacOptions ++= Seq("-language:implicitConversions", "-deprecation")
lazy val root = (project in file("."))
  .settings(
    name := "Retail-Discount-Rule-Engine"
  )
libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test
libraryDependencies += "com.oracle.database.jdbc" % "ojdbc8" % "19.3.0.0"
libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"
libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.12"
)
libraryDependencies += "com.typesafe" % "config" % "1.4.3"

testFrameworks += new TestFramework("munit.Framework")
