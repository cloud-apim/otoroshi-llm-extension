import Dependencies.*
import sbt.Package.ManifestAttributes

ThisBuild / scalaVersion     := "2.12.18"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val langchain4jVersion = "1.0.0-alpha1" //"0.34.0"
lazy val jacksonVersion = "2.15.3"
lazy val jlamaVersion = "0.8.4"
lazy val jackson = Seq(
  ExclusionRule("com.fasterxml.jackson.core", "jackson-databind"),
  ExclusionRule("io.opentelemetry"),
)

lazy val slf4j = Seq(
  ExclusionRule("org.slf4j"),
  ExclusionRule("ch.qos.logback")
)

lazy val netty = Seq(
  ExclusionRule("io.netty", "netty-transport-native-epoll"),
  ExclusionRule("io.netty", "netty-transport-native-kqueue"),
)

lazy val all = jackson ++ slf4j

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-llm-extension",
    resolvers ++= Seq(
      "jitpack" at "https://jitpack.io",
      "spring-milestones" at "https://repo.spring.io/milestone",
      "spring-snapshots" at "https://repo.spring.io/snapshot"  
    ),
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "17.9.1" % "provided" excludeAll (netty: _*),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
      "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion,
      "com.fasterxml.jackson.module" % "jackson-module-scala_2.12" % jacksonVersion,
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "dev.langchain4j" % "langchain4j" % langchain4jVersion excludeAll(all: _*),
      "dev.langchain4j" % "langchain4j-mcp" % langchain4jVersion excludeAll(all: _*),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // for rapid dev purposes, the following 2 are marked as provided. needs to be not "provided" for release ////////
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // "dev.langchain4j" % "langchain4j-embeddings" % langchain4jVersion % "provided" excludeAll(all: _*),
      // "dev.langchain4j" % "langchain4j-embeddings-all-minilm-l6-v2" % langchain4jVersion % "provided" excludeAll(all: _*),
      "dev.langchain4j" % "langchain4j-embeddings" % langchain4jVersion excludeAll(all: _*),
      "dev.langchain4j" % "langchain4j-embeddings-all-minilm-l6-v2" % langchain4jVersion excludeAll(all: _*),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "com.github.tjake" % "jlama-core" % jlamaVersion excludeAll(all: _*),
      "com.github.tjake" % "jlama-native" % jlamaVersion excludeAll(all: _*),
      //"com.github.tjake" % "jlama-native" % jlamaVersion classifier "linux-x86_64" classifier "osx-x86_64" classifier "osx-aarch_64" excludeAll(all: _*),
      "com.github.tjake" % "jlama-native" % jlamaVersion classifier "linux-x86_64" classifier "osx-aarch_64" excludeAll(all: _*),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "io.netty" % "netty-transport-native-kqueue" % "4.1.107.Final" % "provided" excludeAll(jackson: _*),
      "io.netty" % "netty-transport-native-epoll" % "4.1.107.Final" % "provided" excludeAll(jackson: _*),
      munit % Test
    ),
    fork := true,
    Test / parallelExecution := false,
    Test / javaOptions ++= Seq("--add-modules=jdk.incubator.vector", "--enable-preview"),
    assembly / test  := {},
    assembly / assemblyJarName := "otoroshi-llm-extension-assembly_2.12-dev.jar",
    assembly / packageOptions += ManifestAttributes("Multi-Release" -> "true"),
    assembly / assemblyMergeStrategy := {
      case PathList("scala", xs @ _*) => MergeStrategy.first
      case PathList("com", "sun", "jna", xs @ _*) => MergeStrategy.first
      case PathList("javax", "annotation", xs @ _*) => MergeStrategy.first
      case PathList(ps @ _*) if ps.contains("module-info.class") => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "FastDoubleParser-NOTICE" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "groovy-release-info.properties" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "additional-spring-configuration-metadata.json" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "spring-configuration-metadata.json" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "aot.factories" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "spring.factories" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "okio.kotlin_module" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "libjlama.dylib" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "com.github.tjake.versions.properties" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "org.springframework.boot.autoconfigure.AutoConfiguration.imports" => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
