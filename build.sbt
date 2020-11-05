ThisBuild / scalaVersion := "2.13.3"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "nodes-event-watch",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      ("com.wavesplatform" % "protobuf-schemas" % "1.2.8" classifier "proto") % "protobuf",
      "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.10.1",
      "org.scalatest" %% "scalatest" % "3.2.2" % Test
    ),
    inConfig(Compile)(Seq(
      PB.deleteTargetDirectory := false,
      PB.protoSources += PB.externalIncludePath.value,
      PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value / "scalapb"
    ))
  )
