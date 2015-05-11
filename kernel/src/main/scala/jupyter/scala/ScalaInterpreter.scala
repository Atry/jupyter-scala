package jupyter.scala

import java.io.File

import ammonite.pprint
import ammonite.interpreter._
import ammonite.api.{IvyConstructor, ImportData, BridgeConfig}

import jupyter.api._
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.protocol.Output.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage

import com.github.alexarchambault.ivylight.{ClasspathFilter, Ivy, Resolver}
import org.apache.ivy.plugins.resolver.DependencyResolver

object ScalaInterpreter {

  def bridgeConfig(publish: => Option[Publish[Evidence]],
                   currentMessage: => Option[ParsedMessage[_]],
                   startJars: Seq[File] = Nil,
                   startIvys: Seq[(String, String, String)] = Nil,
                   jarMap: File => File = identity,
                   startResolvers: Seq[DependencyResolver] = Seq(Resolver.localRepo, Resolver.defaultMaven),
                   pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
                   colors: ColorSet = ColorSet.Default): BridgeConfig =
    BridgeConfig(
      "object ReplBridge extends jupyter.api.APIHolder",
      "ReplBridge",
      NamesFor[API].map{case (n, isImpl) => ImportData(n, n, "", "ReplBridge.shell", isImpl)}.toSeq ++
        NamesFor[IvyConstructor.type].map{case (n, isImpl) => ImportData(n, n, "", "ammonite.api.IvyConstructor", isImpl)}.toSeq,
      _.asInstanceOf[Iterator[Iterator[String]]].map(_.mkString).foreach(println)
    ) {
        var api: FullAPI = null

        (intp, cls) =>
          if (api == null)
            api = new APIImpl(intp, publish, currentMessage, startJars, startIvys, jarMap, startResolvers, colors, pprintConfig)

          APIHolder.initReplBridge(cls.asInstanceOf[Class[APIHolder]], api)
    }

  val wrap =
    Wrap(l => "Iterator(" + l.map(WebDisplay(_)).mkString(", ") + ")", classWrap = true)

  val scalaVersion = scala.util.Properties.versionNumberString

  val startIvys = Seq(
    ("org.scala-lang", "scala-library", scalaVersion),
    ("com.github.alexarchambault.jupyter", s"jupyter-scala-api_$scalaVersion", BuildInfo.version)
  )

  val startMacroIvys = startIvys ++ Seq(
    ("org.scala-lang", "scala-compiler", scalaVersion)
  ) ++ {
    if (scalaVersion startsWith "2.10.")
      Seq(("org.scalamacros", s"paradise_$scalaVersion", "2.0.1"))
    else
      Seq()
  }

  val startResolvers = Seq(
    Resolver.localRepo,
    Resolver.defaultMaven
  ) ++ {
    if (BuildInfo.version endsWith "-SNAPSHOT")
      Seq(Resolver.sonatypeRepo("snapshots"))
    else
      Seq()
  }

  /*
   * Same hack as in ammonite-shell, see the comment there
   */
  lazy val packJarMap = Classes.defaultClassPath()._1.map(f => f.getName -> f).toMap
  def jarMap(f: File) = packJarMap.getOrElse(f.getName, f)

  lazy val (startJars, startDirs) =
    Ivy.resolve(startIvys, startResolvers).toList
      .map(jarMap)
      .distinct
      .filter(_.exists())
      .partition(_.getName endsWith ".jar")

  lazy val (startMacroJars, startMacroDirs) =
    Ivy.resolve(startMacroIvys, startResolvers).toList
      .map(jarMap)
      .distinct
      .filter(_.exists())
      .partition(_.getName endsWith ".jar")

  lazy val startClassLoader: ClassLoader =
    new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ startJars ++ startDirs).toSet)

  lazy val startMacroClassLoader: ClassLoader =
    new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ startMacroJars ++ startMacroDirs).toSet)

  def apply(startJars: Seq[File] = startJars,
            startDirs: Seq[File] = startDirs,
            startIvys: Seq[(String, String, String)] = startIvys,
            jarMap: File => File = jarMap,
            startResolvers: Seq[DependencyResolver] = startResolvers,
            startClassLoader: ClassLoader = startClassLoader,
            startMacroClassLoader: ClassLoader = startMacroClassLoader,
            pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
            colors: ColorSet = ColorSet.Default,
            filterUnitResults: Boolean = true): interpreter.Interpreter =
    new interpreter.Interpreter {
      var currentPublish = Option.empty[Publish[Evidence]]
      var currentMessage = Option.empty[ParsedMessage[_]]

      val underlying = new Interpreter(
        bridgeConfig = bridgeConfig(
          currentPublish,
          currentMessage,
          startJars = startJars,
          startIvys = startIvys,
          jarMap = jarMap,
          startResolvers = startResolvers,
          pprintConfig = pprintConfig,
          colors = colors
        ),
        wrapper = wrap,
        imports = new ammonite.interpreter.Imports(useClassWrapper = true),
        classes = new Classes(startClassLoader, (startJars, startDirs), startMacroClassLoader = startMacroClassLoader)
      )

      // Displaying results directly, not under Jupyter "Out" prompt
      override def resultDisplay = true

      def interpret(line: String, output: Option[(String => Unit, String => Unit)], storeHistory: Boolean, current: Option[ParsedMessage[_]]) = {
        currentMessage = current

        // ANSI color stripping cut-n-pasted from Ammonite JLineFrontend
        def resFilter(s: String) =
          if (filterUnitResults)
            !s.replaceAll("\u001B\\[[;\\d]*m", "").endsWith(": Unit = ()")
          else
            true

        try {
          underlying(line, _(_), it => new DisplayData.RawData(it.asInstanceOf[Iterator[Iterator[String]]].map(_.mkString).filter(resFilter) mkString "\n"), stdout = output.map(_._1), stderr = output.map(_._2)) match {
            case Res.Buffer(s) =>
              interpreter.Interpreter.Incomplete
            case Res.Exit =>
              interpreter.Interpreter.Error("Close this notebook to exit")
            case Res.Failure(reason) =>
              interpreter.Interpreter.Error(reason)
            case Res.Skip =>
              interpreter.Interpreter.NoValue
            case r @ Res.Success(ev) =>
              underlying.handleOutput(r)
              interpreter.Interpreter.Value(ev.value)
          }
        }
        finally
          currentMessage = None
      }

      override def publish(publish: Publish[ParsedMessage[_]]) = {
        currentPublish = Some(publish.contramap[Evidence](e => e.underlying.asInstanceOf[ParsedMessage[_]]))
      }

      def complete(code: String, pos: Int) = {
        val (pos0, completions, _) = underlying.complete(pos, code)
        (pos0, completions)
      }

      def executionCount = underlying.history.length

      val languageInfo = LanguageInfo(
        name="scala",
        codemirror_mode = "text/x-scala",
        file_extension = "scala",
        mimetype = "text/x-scala"
      )
    }

}
