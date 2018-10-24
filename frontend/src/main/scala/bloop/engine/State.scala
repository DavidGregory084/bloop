package bloop.engine

import bloop.CompilerCache
import bloop.cli.{CommonOptions, ExitStatus}
import bloop.data.Project
import bloop.engine.caches.{ResultsCache, StateCache}
import bloop.io.Paths
import bloop.logging.{LogContext, Logger}
import monix.eval.Task

/**
 * Represents the state for a given build.
 *
 * An state is aggressively cached by bloop so that operations that target a build can reuse
 * the state that has already been created before. The state is immutable and allows different
 * builds to change common values utilized by all the bloop operations.
 *
 * @param build The build with which the state is associated.
 * @param results The results cache that contains all the analysis file for a build.
 * @param compilerCache The cache that has pointers to hot compilers (shared across other states).
 * @param commonOptions The environment options necessary for input and output.
 * @param status The status in which the state is currently.
 * @param logger The logger that is used and is associated with a given build.
 */
final case class State private (
    build: Build,
    results: ResultsCache,
    compilerCache: CompilerCache,
    pool: ClientPool,
    commonOptions: CommonOptions,
    status: ExitStatus,
    logger: Logger
) {
  /* TODO: Improve the handling and merging of different status. Use the status to report errors. */
  def mergeStatus(newStatus: ExitStatus): State =
    this.copy(status = ExitStatus.merge(status, newStatus))
}

object State {
  private[bloop] val stateCache: StateCache = StateCache.empty
  private var singleCompilerCache: CompilerCache = null
  private def getCompilerCache(logger: Logger): CompilerCache = synchronized {
    if (singleCompilerCache != null) singleCompilerCache
    else {
      import sbt.internal.inc.bloop.ZincInternals
      val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
      val jars = Paths.getCacheDirectory("scala-jars")
      singleCompilerCache = new CompilerCache(provider, jars, logger, Nil)
      singleCompilerCache
    }
  }

  private[bloop] def forTests(build: Build, compilerCache: CompilerCache, logger: Logger): State = {
    val opts = CommonOptions.default
    val results = ResultsCache.load(build, opts.workingPath, logger)
    State(build, results, compilerCache, NoPool, opts, ExitStatus.Ok, logger)
  }

  def apply(build: Build, pool: ClientPool, opts: CommonOptions, logger: Logger): State = {
    val results = ResultsCache.load(build, opts.workingPath, logger)
    val compilerCache = getCompilerCache(logger)
    State(build, results, compilerCache, pool, opts, ExitStatus.Ok, logger)
  }
  import bloop.io.AbsolutePath

  /**
   * Loads an state active for the given configuration directory.
   *
   * @param configDir The configuration directory to load a state for.
   * @param pool The pool of listeners that are connected to this client.
   * @param opts The common options associated with the state.
   * @param logger The logger to be used to instantiate the state.
   * @return An state (cached or not) associated with the configuration directory.
   */
  def loadActiveStateFor(
      configDir: AbsolutePath,
      pool: ClientPool,
      opts: CommonOptions,
      logger: Logger
  ): Task[State] = {
    val cached = State.stateCache.addIfMissing(configDir, path => {
      BuildLoader.load(configDir, logger).map { projects =>
        val build: Build = Build(configDir, projects)
        State(build, pool, opts, logger)
      }
    })

    cached.map(_.copy(pool = pool, commonOptions = opts, logger = logger))
  }

  implicit class XState(val s: State) extends AnyVal {
    def withTrace(t: Throwable): State = { s.logger.trace(t); s }
    def withDebug(msg: String)(implicit log: LogContext): State = { s.logger.debug(msg); s }
    def withInfo(msg: String): State = { s.logger.info(msg); s }
    def withWarn(msg: String): State = { s.logger.warn(msg); s }
    def withError(msg: String): State = withError(msg, ExitStatus.UnexpectedError)
    def withError(msg: String, t: Throwable): State =
      withError(msg, ExitStatus.UnexpectedError).withTrace(t)
    def withError(msg: String, status: ExitStatus): State = {
      s.logger.error(msg)
      s.mergeStatus(status)
    }
  }
}
