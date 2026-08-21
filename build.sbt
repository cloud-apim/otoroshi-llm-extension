import Dependencies._
import sbt.Package.ManifestAttributes

ThisBuild / scalaVersion     := "3.8.4"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val langchain4jVersion = "1.15.0" //"0.34.0"
// kept in sync with otoroshi/build.sbt so the copy we ship in the assembly is the very same one
// otoroshi already has on its classpath at runtime
lazy val jacksonVersion = "2.22.2"
lazy val jacksonAnnotationVersion = "2.22" // jackson-annotations is versioned at the minor level only
lazy val nettyVersion = "4.2.17.Final"
lazy val luceneVersion = "9.11.1"
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
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      // `-Wunused:all` minus explicits/implicits/params: most of the chat/audio/image/ocr model
      // client APIs in models.scala are traits whose default method bodies answer "not supported"
      // and legitimately ignore every argument, and the AdminExtension*Route handlers have to
      // keep the 4-argument shape otoroshi calls them with. Everything that catches real dead
      // code is on.
      "-Wunused:imports,privates,locals,patvars",
      // the wasm4s "bundle" jar (transitive, provided) vendors an older scala 3 stdlib where
      // `scala.caps` is an object while scala-library 3.8.4 declares it as a package. otoroshi
      // itself silences the very same warning.
      "-Wconf:msg=package scala contains object and package with same name:s",
    ),
    resolvers ++= Seq(
      "jitpack" at "https://jitpack.io",
      "spring-milestones" at "https://repo.spring.io/milestone",
      "spring-snapshots" at "https://repo.spring.io/snapshot"
    ),
    // netty 4.2 moved classes around (netty-codec -> netty-codec-base) and renamed the quic/http3
    // incubator packages. langchain4j, jlama and kreuzberg still ask for 4.1.x, which ships the
    // same class names with the 4.1 signatures and shadows the 4.2 jars on the classpath.
    // otoroshi runs on nettyVersion, so pin everything there.
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-buffer"    % nettyVersion,
      "io.netty" % "netty-codec"     % nettyVersion,
      "io.netty" % "netty-common"    % nettyVersion,
      "io.netty" % "netty-handler"   % nettyVersion,
      "io.netty" % "netty-resolver"  % nettyVersion,
      "io.netty" % "netty-transport" % nettyVersion,
    ),
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "18.0.0-preview2" % "provided" excludeAll (netty *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonAnnotationVersion,
      "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
      "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "dev.langchain4j" % "langchain4j" % "1.15.0" excludeAll(all *),
      "dev.langchain4j" % "langchain4j-mcp" % "1.15.0-beta25" excludeAll(all *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // for rapid dev purposes, the following 2 are marked as provided. needs to be not "provided" for release ////////
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // "dev.langchain4j" % "langchain4j-embeddings" % langchain4jVersion % "provided" excludeAll(all *),
      // "dev.langchain4j" % "langchain4j-embeddings-all-minilm-l6-v2" % langchain4jVersion % "provided" excludeAll(all *),
      "dev.langchain4j" % "langchain4j-embeddings" % "1.15.0-beta25" excludeAll(all *),
      "dev.langchain4j" % "langchain4j-embeddings-all-minilm-l6-v2" % "1.15.0-beta25" excludeAll(all *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "com.github.tjake" % "jlama-core" % jlamaVersion excludeAll(all *),
      "com.github.tjake" % "jlama-native" % jlamaVersion excludeAll(all *),
      //"com.github.tjake" % "jlama-native" % jlamaVersion classifier "linux-x86_64" classifier "osx-x86_64" classifier "osx-aarch_64" excludeAll(all *),
      "com.github.tjake" % "jlama-native" % jlamaVersion classifier "linux-x86_64" classifier "osx-aarch_64" excludeAll(all *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "dev.kreuzberg" % "kreuzberg" % "4.6.3" excludeAll(jackson *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "org.apache.lucene" % "lucene-core" % luceneVersion excludeAll(all *),
      "org.apache.lucene" % "lucene-analysis-common" % luceneVersion excludeAll(all *),
      "org.apache.lucene" % "lucene-queryparser" % luceneVersion excludeAll(all *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      "io.netty" % "netty-transport-native-kqueue" % nettyVersion % "provided" excludeAll(jackson *),
      "io.netty" % "netty-transport-native-epoll" % nettyVersion % "provided" excludeAll(jackson *),
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // otoroshi ships java-jq as an unmanaged jar in its lib/ directory, so it is absent from the
      // published pom: the test suites that boot a real otoroshi need it on the test classpath
      "com.arakelian" % "java-jq" % "1.3.0" % Test excludeAll(all *),
      munit % Test
    ),
    fork := true,
    Test / parallelExecution := false,
    Test / javaOptions ++= Seq("--add-modules=jdk.incubator.vector", "--enable-preview"),
    assembly / test  := {},
    assembly / assemblyJarName := "otoroshi-llm-extension-assembly_3-dev.jar",
    // otoroshi already provides the exact same scala3-library at runtime, no need to ship a
    // second copy of the whole stdlib in the plugin jar
    assembly / assemblyPackageScala / assembleArtifact := false,
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
