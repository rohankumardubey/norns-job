package io.github.nornslab.norns.core.api


import java.io.File
import java.net.{URI, URL}
import java.util.Properties

import com.typesafe.config.ConfigFactory._
import com.typesafe.config.impl.ConfigImpl
import com.typesafe.config.{ConfigFactory, _}
import com.typesafe.scalalogging.Logger

import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

/** https://github.com/playframework/playframework/blob/master/core/play/src/main/scala/play/api/Configuration.scala
  *
  * This object provides a set of operations to create `NornsConfig` values.
  *
  * For example, to load a `NornsConfig` in a running application:
  * {{{
  * val config = NornsConfig.load()
  * val foo = config.getString("foo").getOrElse("boo")
  * }}}
  *
  * The underlying implementation is provided by https://github.com/typesafehub/config.
  */
object NornsConfig {

  private[this] lazy val dontAllowMissingConfigOptions = ConfigParseOptions.defaults().setAllowMissing(false)

  private[this] lazy val dontAllowMissingConfig = ConfigFactory.load(dontAllowMissingConfigOptions)

  def load(classLoader: ClassLoader = this.getClass.getClassLoader,
           properties: Properties = new Properties(),
           directSettings: Map[String, AnyRef] = Map.empty[String, AnyRef],
           applicationConfig: Option[String] = None
          ): NornsConfig = {
    try {
      val userDefinedProperties = if (properties eq System.getProperties) ConfigFactory.empty()
      else parseProperties(properties)

      val combinedConfig: Config = Seq(
        userDefinedProperties,
        ConfigImpl.systemPropertiesAsConfig(),
        parseMap(directSettings.asJava),
        if (applicationConfig.isDefined) parseFile(new File(applicationConfig.get)) else ConfigFactory.empty(),
        parseResources(classLoader, "norns/reference-overrides.conf"),
        parseResources(classLoader, "reference.conf")
      ).reduceLeft(_.withFallback(_))

      val resolvedConfig = combinedConfig.resolve

      NornsConfig(resolvedConfig)
    } catch {
      case e: ConfigException => throw configError(e.getMessage, Option(e.origin), Some(e))
    }
  }

  /**
    * Returns an empty NornsConfig object.
    */
  def empty: NornsConfig = NornsConfig(ConfigFactory.empty())

  /**
    * Returns the reference configuration object.
    */
  def reference: NornsConfig = NornsConfig(defaultReference())

  /**
    * Create a new NornsConfig from the data passed as a Map.
    */
  def from(data: Map[String, Any]): NornsConfig = {

    def toJava(data: Any): Any = data match {
      case map: Map[_, _] => map.mapValues(toJava).toMap.asJava
      case iterable: Iterable[_] => iterable.map(toJava).asJava
      case v => v
    }

    NornsConfig(parseMap(toJava(data).asInstanceOf[java.util.Map[String, AnyRef]]))
  }

  /**
    * Create a new NornsConfig from the given key-value pairs.
    */
  def apply(data: (String, Any)*): NornsConfig = from(data.toMap)

  private[api] def configError(message: String,
                               origin: Option[ConfigOrigin] = None,
                               e: Option[Throwable] = None
                              ): Exception = {
    val originLine = origin.map(_.lineNumber: java.lang.Integer).orNull
    val originSourceName = origin.map(_.filename).orNull
    val originUrlOpt = origin.flatMap(o => Option(o.url))
    // todo 待完善异常信息
    new Exception(s"todo config error ${message}")
  }

  private[NornsConfig] def asScalaList[A](l: java.util.List[A]): Seq[A] = asScalaBufferConverter(l).asScala.toList

  private[NornsConfig] val logger = Logger(getClass)
}

/*
    * For example:
    * {{{
    * val configuration = NornsConfig.load()
    * get[Int]
    * get[Boolean]
    * get[Double]
    * get[Long]
    * get[Number]
    * get[NornsConfig]
    * get[ConfigList]
    *
    * get[Seq[Boolean]]
    * get[Seq[Double]]
    * get[Seq[Int]]
    * get[Seq[Long]]
    * get[Seq[Duration]].map(_.toMillis)
    * get[Seq[Duration]].map(_.toMillis)
    * get[Seq[Number]]
    * get[Seq[String]]
    * get[ConfigObject]
    *
    * underlying.getBytes
    * underlying.getBooleanList
    * underlying.getBytesList
    * underlying.getConfigList
    * underlying.getDoubleList
    * underlying.getIntList
    * underlying.getLongList
    * underlying.getMillisecondsList
    * underlying.getNanosecondsList
    * underlying.getNumberList
    * underlying.getObjectList
    * underlying.getStringList
    *
    * A configuration error will be thrown if the configuration value is not a valid `Int`.
    *
    * @return a configuration value
    */
/**
  * A full configuration set.
  *
  * The underlying implementation is provided by https://github.com/typesafehub/config.
  *
  * @param underlying the underlying Config implementation
  */
case class NornsConfig(underlying: Config) {

  def ++(other: NornsConfig): NornsConfig = NornsConfig(other.underlying.withFallback(underlying))

  private def readValue[T](path: String, v: => T): Option[T] = try {
    if (underlying.hasPathOrNull(path)) Some(v) else None
  } catch {
    case NonFatal(e) => throw reportError(path, e.getMessage, Some(e))
  }

  def has(path: String): Boolean = underlying.hasPath(path)

  def get[A](path: String)(implicit loader: ConfigLoader[A]): A = loader.load(underlying, path)

  def getAndValidate[A](path: String, values: Set[A])(implicit loader: ConfigLoader[A]): A = {
    val value = get(path)
    if (!values(value)) {
      throw reportError(path, s"Incorrect value, one of (${values.mkString(", ")}) was expected.")
    }
    value
  }

  def getOptional[A](path: String)(implicit loader: ConfigLoader[A]): Option[A] = {
    try {
      if (underlying.hasPath(path)) Some(get[A](path)) else None
    } catch {
      case NonFatal(e) => throw reportError(path, e.getMessage, Some(e))
    }
  }

  def getPrototypedSeq(path: String, prototypePath: String = "prototype.$path"): Seq[NornsConfig] = {
    val prototype = underlying.getConfig(prototypePath.replace("$path", path))
    get[Seq[Config]](path).map { config =>
      NornsConfig(config.withFallback(prototype))
    }
  }

  def getPrototypedMap(path: String, prototypePath: String = "prototype.$path"): Map[String, NornsConfig] = {
    val prototype = if (prototypePath.isEmpty) underlying
    else underlying.getConfig(prototypePath.replace("$path", path))
    get[Map[String, Config]](path).map {
      case (key, config) => key -> NornsConfig(config.withFallback(prototype))
    }
  }

  /**
    * Retrieves a configuration value as `Milliseconds`.
    * {{{
    * engine.timeout = 1 second
    * }}}
    */
  def getMillis(path: String): Long = get[Duration](path).toMillis

  /**
    * Retrieves a configuration value as `Milliseconds`.
    * {{{
    * engine.timeout = 1 second
    * }}}
    */
  def getNanos(path: String): Long = get[Duration](path).toNanos

  /**
    * Returns available keys.
    *
    * For example:
    * {{{
    * val configuration = NornsConfig.load()
    * val keys = configuration.keys
    * }}}
    *
    * @return the set of keys available in this configuration
    */
  def keys: Set[String] = underlying.entrySet.asScala.map(_.getKey).toSet

  /**
    * Returns sub-keys.
    *
    * For example:
    * {{{
    * val configuration = NornsConfig.load()
    * val subKeys = configuration.subKeys
    * }}}
    *
    * @return the set of direct sub-keys available in this configuration
    */
  def subKeys: Set[String] = underlying.root().keySet().asScala.toSet

  /**
    * Returns every path as a set of key to value pairs, by recursively iterating through the
    * config objects.
    */
  def entrySet: Set[(String, ConfigValue)] = underlying.entrySet().asScala.map(e => e.getKey -> e.getValue).toSet

  /**
    * Creates a configuration error for a specific configuration key.
    *
    * For example:
    * {{{
    * val configuration = NornsConfig.load()
    * throw configuration.reportError("engine.connectionUrl", "Cannot connect!")
    * }}}
    *
    * @param path    the configuration key, related to this error
    * @param message the error message
    * @param e       the related exception
    * @return a configuration exception
    */
  def reportError(path: String, message: String, e: Option[Throwable] = None): Exception = {
    val origin = Option(if (underlying.hasPath(path)) underlying.getValue(path).origin else underlying.root.origin)
    NornsConfig.configError(message, origin, e)
  }

  /**
    * Creates a configuration error for this configuration.
    *
    * For example:
    * {{{
    * val configuration = NornsConfig.load()
    * throw configuration.globalError("Missing configuration key: [yop.url]")
    * }}}
    *
    * @param message the error message
    * @param e       the related exception
    * @return a configuration exception
    */
  def globalError(message: String, e: Option[Throwable] = None): Exception = {
    NornsConfig.configError(message, Option(underlying.root.origin), e)
  }

  val defaultRenderOptions: ConfigRenderOptions = ConfigRenderOptions.defaults
    .setComments(false).setOriginComments(false).setFormatted(true).setJson(true)

  def show: String = underlying.root().render(defaultRenderOptions)

  def show(renderOptions: ConfigRenderOptions): String = underlying.root().render(renderOptions)
}

/**
  * A config loader
  */
trait ConfigLoader[A] {
  self =>
  def load(config: Config, path: String = ""): A

  def map[B](f: A => B): ConfigLoader[B] = (config: Config, path: String) => f(self.load(config, path))

}

object ConfigLoader {

  def apply[A](f: Config => String => A): ConfigLoader[A] = (config: Config, path: String) => f(config)(path)

  import scala.collection.JavaConverters._

  implicit val stringLoader: ConfigLoader[String] = ConfigLoader(_.getString)
  implicit val seqStringLoader: ConfigLoader[Seq[String]] = ConfigLoader(_.getStringList).map(_.asScala.toSeq)

  implicit val intLoader: ConfigLoader[Int] = ConfigLoader(_.getInt)
  implicit val seqIntLoader: ConfigLoader[Seq[Int]] = ConfigLoader(_.getIntList).map(_.asScala.map(_.toInt).toSeq)

  implicit val booleanLoader: ConfigLoader[Boolean] = ConfigLoader(_.getBoolean)
  implicit val seqBooleanLoader: ConfigLoader[Seq[Boolean]] =
    ConfigLoader(_.getBooleanList).map(_.asScala.map(_.booleanValue).toSeq)

  implicit val finiteDurationLoader: ConfigLoader[FiniteDuration] =
    ConfigLoader(_.getDuration).map(javaDurationToScala)

  implicit val seqFiniteDurationLoader: ConfigLoader[Seq[FiniteDuration]] =
    ConfigLoader(_.getDurationList).map(_.asScala.map(javaDurationToScala).toSeq)

  implicit val durationLoader: ConfigLoader[Duration] = ConfigLoader { config =>
    path =>
      if (config.getIsNull(path)) Duration.Inf
      else if (config.getString(path) == "infinite") Duration.Inf
      else finiteDurationLoader.load(config, path)
  }

  // Note: this does not support null values but it added for convenience
  implicit val seqDurationLoader: ConfigLoader[Seq[Duration]] =
    seqFiniteDurationLoader.map(identity[Seq[Duration]])

  implicit val doubleLoader: ConfigLoader[Double] = ConfigLoader(_.getDouble)
  implicit val seqDoubleLoader: ConfigLoader[Seq[Double]] =
    ConfigLoader(_.getDoubleList).map(_.asScala.map(_.doubleValue).toSeq)

  implicit val numberLoader: ConfigLoader[Number] = ConfigLoader(_.getNumber)
  implicit val seqNumberLoader: ConfigLoader[Seq[Number]] = ConfigLoader(_.getNumberList).map(_.asScala.toSeq)

  implicit val longLoader: ConfigLoader[Long] = ConfigLoader(_.getLong)
  implicit val seqLongLoader: ConfigLoader[Seq[Long]] =
    ConfigLoader(_.getLongList).map(_.asScala.map(_.longValue).toSeq)

  implicit val bytesLoader: ConfigLoader[ConfigMemorySize] = ConfigLoader(_.getMemorySize)
  implicit val seqBytesLoader: ConfigLoader[Seq[ConfigMemorySize]] =
    ConfigLoader(_.getMemorySizeList).map(_.asScala.toSeq)

  implicit val configLoader: ConfigLoader[Config] = ConfigLoader(_.getConfig)
  implicit val configListLoader: ConfigLoader[ConfigList] = ConfigLoader(_.getList)
  implicit val configObjectLoader: ConfigLoader[ConfigObject] = ConfigLoader(_.getObject)
  implicit val seqConfigLoader: ConfigLoader[Seq[Config]] = ConfigLoader(_.getConfigList).map(_.asScala.toSeq)

  implicit val configurationLoader: ConfigLoader[NornsConfig] = configLoader.map(NornsConfig(_))
  implicit val seqConfigurationLoader: ConfigLoader[Seq[NornsConfig]] = seqConfigLoader.map(_.map(NornsConfig(_)))

  implicit val urlLoader: ConfigLoader[URL] = ConfigLoader(_.getString).map(new URL(_))
  implicit val uriLoader: ConfigLoader[URI] = ConfigLoader(_.getString).map(new URI(_))

  private def javaDurationToScala(javaDuration: java.time.Duration): FiniteDuration =
    Duration.fromNanos(javaDuration.toNanos)

  /**
    * Loads a value, interpreting a null value as None and any other value as Some(value).
    */
  implicit def optionLoader[A](implicit valueLoader: ConfigLoader[A]): ConfigLoader[Option[A]] =
    (config: Config, path: String) => {
      if (config.getIsNull(path)) None else Some(valueLoader.load(config, path))
    }

  implicit def mapLoader[A](implicit valueLoader: ConfigLoader[A]): ConfigLoader[Map[String, A]] =
    (config: Config, path: String) => {
      val obj = config.getObject(path)
      val conf = obj.toConfig

      obj
        .keySet()
        .asScala
        .iterator
        .map { key =>
          // quote and escape the key in case it contains dots or special characters todo path 转义
          // val path = "\"" + StringEscapeUtils.escapeEcmaScript(key) + "\""
          key -> valueLoader.load(conf, path)
        }
        .toMap
    }
}