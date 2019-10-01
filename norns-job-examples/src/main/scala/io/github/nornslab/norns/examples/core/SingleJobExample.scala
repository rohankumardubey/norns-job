package io.github.nornslab.norns.examples.core

import io.github.nornslab.norns.core.NornsJob
import io.github.nornslab.norns.core.api.{Job, JobContext}

/**
  * @author Li.Wei by 2019/8/30
  */
object SingleJobExample {
  def main(args: Array[String]): Unit = NornsJob.work(classOf[SingleJobExample])
}

class SingleJobExampleContext extends JobContext {
  def foo: String = "foo"
}

class SingleJobExample extends Job[SingleJobExampleContext] {

  private val _jc = new SingleJobExampleContext

  override def context: SingleJobExampleContext = _jc

  /** job 运行 */
  override def start(): Unit = info(s"$name running... context.foo=${context.foo}...")
}

