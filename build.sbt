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
    // A forked test JVM does NOT inherit SBT_OPTS -- that only sizes the sbt
    // launcher. Without these the tests ran on the JVM's default heap (~7.6 GB
    // here) no matter what SBT_OPTS said, and a large `testOnly` selection died
    // with an OutOfMemoryError a few hundred tests in, repeatedly, while the
    // person running it believed the documented -Xmx had been applied.
    // Override per run with e.g. TEST_XMX=-Xmx16g when a machine has room.
    Test / javaOptions ++= Seq(
      sys.env.getOrElse("TEST_XMX", "-Xmx10g"),
      sys.env.getOrElse("TEST_XSS", "-Xss16m")
    ),
    fastTest := (Test / testOnly)
      .toTask(" * -- -l m68k040.SlowTest -l m68k040.VerilatorTest -l m68k040.BoardTest")
      .value
  )
