package io.univalence.centrifuge

import java.util.UUID
import java.util.function.Supplier

import org.apache.spark.sql.{ Dataset, SparkSession }

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

object CircuitOpenException extends Exception

protected object ThreadLocalExecutor {
  lazy val skipped: ExecutionResult[Nothing] = ExecutionResult(None, Some(ExecutionInfo(0, skipped = true, None)))
}

protected class ThreadLocalExecutor(var circuitClosed: Boolean = true, config: ExecutorConfig) {

  private var nbFailed: Int = 0

  private var startTime: Long = _

  @tailrec
  final def run[T](f: () ⇒ Try[T], nbAttemptRemaining: Int = config.attempt - 1, tried: Int = 0): ExecutionResult[T] = {
    if (!circuitClosed) {
      ThreadLocalExecutor.skipped
    } else {
      config.rateLimitPerSeconds.foreach(_ ⇒ startTime = System.currentTimeMillis())
      val t = f()
      config.rateLimitPerSeconds.foreach(l ⇒ {
        val remaining: Long = 1000 / l - (System.currentTimeMillis() - startTime)
        if (remaining > 0) Thread.sleep(remaining)
      })
      t match {
        case Success(v) ⇒ ExecutionResult(Some(v), None)
        case Failure(e) ⇒

          if (nbAttemptRemaining >= 1) {
            config.backoff.foreach(t ⇒ Thread.sleep(t))
            run(f, nbAttemptRemaining - 1, tried + 1)
          } else {
            nbFailed = nbFailed + 1
            if (config.breakAfterNFailure.exists(_ <= nbFailed)) {
              circuitClosed = false
            }
            ExecutionResult(None, Some(ExecutionInfo(1 + tried, skipped = false, Some(e.getMessage))))
          }

      }
    }
  }
}

case class Executor protected[centrifuge] (config: ExecutorConfig) {
  @transient private lazy val myThreadLocal: ThreadLocal[ThreadLocalExecutor] =
    ThreadLocal.withInitial(
      new Supplier[ThreadLocalExecutor] {
        override def get(): ThreadLocalExecutor = new ThreadLocalExecutor(config = config)
      }
    )

  def run[T](f: () ⇒ Try[T]): ExecutionResult[T] = {
    myThreadLocal.get.run(f)
  }
}

object ExecutionResult {

  def success[O](out: O): ExecutionResult[O] = ExecutionResult(Option(out), None)

  lazy val notExecuted: ExecutionResult[Nothing] = ExecutionResult(None, None)

}

case class ExecutorConfig(
  name:                   String,
  attempt:                Int,
  backoff:                Option[Long] = None,
  withExponentialBackoff: Boolean      = false,
  breakAfterNFailure:     Option[Int]  = None,
  rateLimitPerSeconds:    Option[Int]  = None
)

object Executor {
  def build: ExecutorBuilder = ExecutorBuildRep()
}

case class ExecutorBuildRep(
  name:                       Option[String] = None,
  attempt:                    Option[Int]    = None,
  timeoutAttempt:             Option[Long]   = None,
  backoff:                    Option[Long]   = None,
  activateExponentialBackoff: Boolean        = false,
  breakAfterNbFailure:        Option[Int]    = None,
  maxNbCallPerSeconds:        Option[Int]    = None
) extends ExecutorBuilder {

  def isValid: Boolean = name.isDefined

  import scala.language.implicitConversions

  private implicit def toOp[T](a: T): Option[T] = Option(a)

  override def name(name: String): ExecutorBuilder = copy(name = name)

  override def attempt(n: Int): ExecutorBuilder = copy(attempt = n)

  //override def timeoutAttempt(milliseconds: Long): ExecutorBuilder = copy(timeoutAttempt = timeoutAttempt)

  override def backoff(milliseconds: Long): ExecutorBuilder = copy(backoff = milliseconds)

  override def withExponentialBackoff: ExecutorBuilder = copy(activateExponentialBackoff = true)

  override def breakAfter(nbFailure: Int): ExecutorBuilder = copy(breakAfterNbFailure = nbFailure)

  override def getOrCreate(): Executor = {
    if (!isValid) throw new Exception("Invalid executor configuration")

    Executor(ExecutorConfig(name = name.get, attempt.filter(_ > 1).getOrElse(1), breakAfterNFailure = breakAfterNbFailure, rateLimitPerSeconds = maxNbCallPerSeconds, backoff = backoff))
  }

  override def limitRate(nbCallPerSeconds: Int): ExecutorBuilder = copy(maxNbCallPerSeconds = nbCallPerSeconds)

}

trait ExecutorBuilder {
  def name(name: String = UUID.randomUUID().toString): ExecutorBuilder

  def limitRate(nbCallPerSeconds: Int): ExecutorBuilder

  def attempt(n: Int): ExecutorBuilder
  def backoff(milliseconds: Long): ExecutorBuilder
  def withExponentialBackoff: ExecutorBuilder

  def breakAfter(nbFailure: Int): ExecutorBuilder

  def getOrCreate(): Executor
}

case class Toto(name: String, age: Int)

case class ExecutionResult[+T](out: Option[T], info: Option[ExecutionInfo]) {
  def isSuccess: Boolean = out.isDefined

  def toTry: Try[T] = out.map(Success.apply).getOrElse(info match {
    case Some(i) if i.skipped ⇒ Failure(CircuitOpenException)
    case Some(i)              ⇒ Failure(new Exception(i.lastError.getOrElse("???")))

  })
}

case class ExecutionInfo(nbAttempt: Int, skipped: Boolean, lastError: Option[String])

case class ExecMetrics(success: Long = 0, skipped: Long = 0, failed: Long = 0, totalAttempts: Long = 0) {

  def isPureSuccess: Boolean = skipped == 0 && failed == 0

  def add(execMetrics: ExecMetrics): ExecMetrics = ExecMetrics(
    success = execMetrics.success + success,
    skipped = execMetrics.skipped + skipped,
    failed = execMetrics.failed + failed,
    totalAttempts = execMetrics.totalAttempts + totalAttempts
  )
}

object PrgSpark {

  import scala.reflect.runtime.universe.TypeTag

  def retryDs[A <: Product: TypeTag, B <: Product: TypeTag, CallRes <: Product: TypeTag](in: Dataset[A])(run: A ⇒ Try[CallRes])(integrate: (A, Try[CallRes]) ⇒ B)(
    nbAttemptStageMax: Int,
    executor:          Executor            = Executor.build.name().breakAfter(20).getOrCreate(),
    recoverOutput:     A ⇒ Option[CallRes] = (a: A) ⇒ None
  ): Dataset[B] = {

    @tailrec
    def innerLoop(
      ds:                Dataset[(A, ExecutionResult[CallRes], B)],
      remainingAttempts: Int,
      oldDs:             Option[Dataset[(A, ExecutionResult[CallRes], B)]]
    ): Dataset[B] = {

      import in.sparkSession.implicits._

      if (remainingAttempts == 0) ds.map(_._3) else {
        ds.persist()
        val execMetrics: ExecMetrics = ds.map(x ⇒ {
          val r: ExecutionResult[CallRes] = x._2
          () match {
            case _ if r.out.isDefined ⇒ ExecMetrics(success = 1)
            case _ ⇒ r.info.get match {
              case i if i.skipped ⇒ ExecMetrics(skipped = 1)
              case i              ⇒ ExecMetrics(failed = 1, totalAttempts = i.nbAttempt)
            }
          }
        }).reduce(_.add(_))

        oldDs.map(_.unpersist())
        if (execMetrics.isPureSuccess) ds.map(_._3) else {
          //TODO : OptimByPartition
          val newDs: Dataset[(A, ExecutionResult[CallRes], B)] = ds.map(t ⇒ {
            val (a, e, b) = t
            if (e.out.isEmpty) {
              val ne: ExecutionResult[CallRes] = executor.run(() ⇒ run(a))
              (a, ne, integrate(a, ne.toTry))
            } else {
              t
            }
          })

          innerLoop(newDs, remainingAttempts - 1, Some(ds))
        }
      }
    }

    import in.sparkSession.implicits._

    val res = in.map(x ⇒ {
      val e = executor.run(() ⇒ run(x))
      (x, e, integrate(x, e.toTry))
    })

    res.persist()

    innerLoop(res, nbAttemptStageMax, None)

  }

  def main(args: Array[String]): Unit = {

    val ss: SparkSession = ???

    import ss.implicits._

    val ds = ss.read.parquet("toto").as[Toto]

    val res: Dataset[(Toto, Boolean)] = PrgSpark.retryDs(ds)(toto ⇒ Try {
      /*

      // wegbet ....

       */

      (1, 2)
    })({
      case (toto, Success(value)) ⇒ (toto.copy(age = toto.age * value._1 + value._2), true)
      case (toto, Failure(_))     ⇒ (toto, false)

    })(nbAttemptStageMax = 10)

  }
}
