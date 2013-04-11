import com.typesafe.sbt.SbtStartScript

name := "lab1"

organization := "dos"

version := "0.1"

scalaVersion := "2.9.1"

libraryDependencies += "net.liftweb" %% "lift-json" % "2.4"

resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"

seq(SbtStartScript.startScriptForClassesSettings: _*)

//seq(assemblySettings: _*)

fork in run := true

fork in runMain := true

javaOptions in run += "-Xmx8G"

javaOptions in runMain += "-Xmx8G"
