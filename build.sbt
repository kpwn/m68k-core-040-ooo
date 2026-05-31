ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "2.13.16"

val spinalVersion = "1.14.1"

lazy val fastTest = taskKey[Unit]("Run fast tests only (exclude slow/verilator/board tags)")

lazy val root = (project in file("."))
  .settings(
    name := "m68k-core-040-ooo",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion,
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
      "com.github.spinalhdl" %% "spinalhdl-sim"  % spinalVersion % Test,
      "org.scalatest"        %% "scalatest"       % "3.2.19"      % Test
    ),
    scalacOptions += "-language:reflectiveCalls",
    fork := true,
    Test / fork := true,
    fastTest := (Test / testOnly)
      .toTask(" * -- -l m68k040.SlowTest -l m68k040.VerilatorTest -l m68k040.BoardTest")
      .value
  )
