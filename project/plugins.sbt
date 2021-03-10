addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.0-RC2")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.5")

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.10.8",
  "org.vafer" % "jdeb" % "1.5" artifacts Artifact("jdeb", "jar", "jar")
)
