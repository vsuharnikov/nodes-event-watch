ThisBuild / scalaVersion := "2.13.5"
ThisBuild / version := "1.0.1"
ThisBuild / maintainer := "Vyacheslav Suharnikov"

enablePlugins(JavaServerAppPackaging, JDebPackaging, SystemdPlugin)
dockerBaseImage := "openjdk:11.0.9-jre-slim"
run / fork := true

lazy val root = (project in file("."))
  .settings(
    name := "nodes-event-watch",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "net.logstash.logback" % "logstash-logback-encoder" % "6.6",

      "com.github.pureconfig" %% "pureconfig" % "0.14.1",
      "com.github.scopt" %% "scopt" % "4.0.0",

      ("com.wavesplatform" % "protobuf-schemas" % "1.2.8" classifier "proto") % "protobuf",
      "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,

      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.10.1",

      "com.softwaremill.sttp.client3" %% "core" % "3.1.6",
      "com.softwaremill.sttp.client3" %% "play-json" % "3.1.6"
    ),
    inConfig(Compile)(Seq(
      PB.deleteTargetDirectory := false,
      PB.protoSources += PB.externalIncludePath.value,
      PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value / "scalapb"
    ))
  )
