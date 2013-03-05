import sbt._
import Keys._

object Generators {
  // Generates a scala file that contains the play version for use at runtime.
  def PlayVersion(dir: File): Seq[File] = {
      val file = dir / "PlayVersion.scala"
      IO.write(file,
        """|package play.core
            |
            |object PlayVersion {
            |    val current = "%s"
            |    val scalaVersion = "%s"
            |}
          """.stripMargin.format(BuildSettings.buildVersion, BuildSettings.buildScalaVersion))
      Seq(file)
  }
}

object Tasks {

  import BuildSettings._

  // ----- Generate Distribution
  lazy val generateDist = TaskKey[Unit]("create-dist")
  val generateDistTask: Setting[_] = 
    generateDist <<= (/*generateAPIDocs,*/ RepositoryBuilder.localRepoCreated in PlayBuild.RepositoryProject, baseDirectory in ThisBuild, target) map {
      (/*_,*/ repo, bd, t) =>
        generateDistribution(repo, bd, t)
    }

  def generateDistribution(repo: File, bd: File, target: File): File = {
    // Go down to the play checkout and get rid of the dumbness.
    val playBase = bd.getParentFile
    // Assert if we have the right directory...
    assert(playBase / ".git" isDirectory, "%s is not play's home directory" format(playBase))
    
    val coreFiles = Seq("play", "play.bat", "README.md", "CONTRIBUTING.md") map (playBase /)
    
    val dist = target / "dist"
    IO.createDirectory(dist)
    IO.createDirectory(dist / "repository")
    
    // First, let's do the dangerously fun copy of our current sources.
    def copyDist(): Unit = {
      val code = playBase / "framework"
      val distCode = dist / "framework"
      object files extends Traversable[File] {
        val badNames = Seq(".git", ".lock", ".history", "target", ".gitmodules", ".DS_store", "bin")
        def foreach[U](f: File => U): Unit = {
          @annotation.tailrec
          def drive(files: Seq[File]): Unit = files match {
            // Don't go down any of the "bad" directory names.
            case Seq(head, tail @ _*) if badNames contains head.getName => drive(tail)
            case Seq(head, tail @ _*) =>
              f(head)
              drive(IO.listFiles(head) ++ tail)
            case Nil => ()
          }
          drive(IO.listFiles(code))
        }
      }
      val toCopy =
        for {
          (file, name) <- files.toSeq x relativeTo(code)
        } yield file -> (distCode / name)
      IO.copy(toCopy)
    }
    copyDist()


    def copyDistFiles(name: String) = IO.copyDirectory(playBase / name, dist / name, true, false)
    copyDistFiles("documentation")
    copyDistFiles("samples")
    // TODO - Don't be stupid about this
    //copyDistFiles("framework")
    // Copy the core files
    IO.copy(coreFiles map (f => f -> (dist / f.getName)))
    IO.copyDirectory(repo, dist / "repository" / "local", true, false)
    bd
  }
  // ----- Generate API docs

  lazy val generateAPIDocs = TaskKey[Unit]("api-docs")
  val generateAPIDocsTask = TaskKey[Unit]("api-docs") <<= (dependencyClasspath in Test, compilers, streams, baseDirectory, scalaBinaryVersion) map {
    (classpath, cs, s, base, sbv) =>

      val allJars = (file("src") ** "*.jar").get

      IO.delete(file("../documentation/api"))

      // Scaladoc
      val sourceFiles =
        (file("src/play/src/main/scala/play/api") ** "*.scala").get ++
          (file("src/iteratees/src/main/scala") ** "*.scala").get ++
          (file("src/play-test/src/main/scala") ** "*.scala").get ++
          (file("src/play/src/main/scala/views") ** "*.scala").get ++
          (file("src/anorm/src/main/scala") ** "*.scala").get ++
          (file("src/play-filters-helpers/src/main/scala") ** "*.scala").get ++
          (file("src/play-jdbc/src/main/scala") ** "*.scala").get ++
          (file("src/play/target/scala-" + sbv + "/src_managed/main/views/html/helper") ** "*.scala").get
      val options = Seq("-sourcepath", base.getAbsolutePath, "-doc-source-url", "https://github.com/playframework/Play20/tree/" + BuildSettings.buildVersion + "/framework€{FILE_PATH}.scala")
      new Scaladoc(10, cs.scalac)("Play " + BuildSettings.buildVersion + " Scala API", sourceFiles, classpath.map(_.data) ++ allJars, file("../documentation/api/scala"), options, s.log)

      // Javadoc
      val javaSources = Seq(
        file("src/play/src/main/java"),
        file("src/play-test/src/main/java"),
        file("src/play-java/src/main/java"),
        file("src/play-java-ebean/src/main/java"),
        file("src/play-java-jdbc/src/main/java"),
        file("src/play-java-jpa/src/main/java")).mkString(":")
      val javaApiTarget = file("../documentation/api/java")
      val javaClasspath = classpath.map(_.data).mkString(":")
      """javadoc -windowtitle playframework -doctitle Play&nbsp;""" + BuildSettings.buildVersion + """&nbsp;Java&nbsp;API  -sourcepath %s -d %s -subpackages play -exclude play.api:play.core -classpath %s""".format(javaSources, javaApiTarget, javaClasspath) ! s.log

  }

  // ----- Compile templates

  val ScalaTemplates = {
    (classpath: Seq[Attributed[File]], templateEngine: File, sourceDirectory: File, generatedDir: File, streams: sbt.std.TaskStreams[sbt.Project.ScopedKey[_]]) =>
      val classloader = new java.net.URLClassLoader(classpath.map(_.data.toURI.toURL).toArray, this.getClass.getClassLoader)
      val compiler = classloader.loadClass("play.templates.ScalaTemplateCompiler")
      val generatedSource = classloader.loadClass("play.templates.GeneratedSource")

      (generatedDir ** "*.template.scala").get.foreach {
        source =>
          val constructor = generatedSource.getDeclaredConstructor(classOf[java.io.File])
          val sync = generatedSource.getDeclaredMethod("sync")
          val generated = constructor.newInstance(source)
          try {
            sync.invoke(generated)
          } catch {
            case e: java.lang.reflect.InvocationTargetException => {
              val t = e.getTargetException
              t.printStackTrace()
              throw t
            }
          }
      }

      (sourceDirectory ** "*.scala.html").get.foreach {
        template =>
          val compile = compiler.getDeclaredMethod("compile", classOf[java.io.File], classOf[java.io.File], classOf[java.io.File], classOf[String], classOf[String], classOf[String])
          try {
            compile.invoke(null, template, sourceDirectory, generatedDir, "play.api.templates.Html", "play.api.templates.HtmlFormat", "import play.api.templates._\nimport play.api.templates.PlayMagic._")
          } catch {
            case e: java.lang.reflect.InvocationTargetException => {
              streams.log.error("Compilation failed for %s".format(template))
              throw e.getTargetException
            }
          }
      }

      (generatedDir ** "*.scala").get.map(_.getAbsoluteFile)
  }

  def scalaTemplateSourceMappings = (excludeFilter in unmanagedSources, unmanagedSourceDirectories in Compile, baseDirectory) map {
    (excludes, sdirs, base) =>
      val scalaTemplateSources = sdirs.descendantsExcept("*.scala.html", excludes)
      ((scalaTemplateSources --- sdirs --- base) pair (relativeTo(sdirs) | relativeTo(base) | flat)) toSeq
  }

}