/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.{ postfixOps, reflectiveCalls }
import akka.actor._
import akka.event.Logging.Warning
import scala.concurrent.{ Future, Promise, Await }
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.dispatch.Dispatcher
import org.junit.Before

/**
 * Test whether TestActorRef behaves as an ActorRef should, besides its own spec.
 */
object TestActorRefSpec {

  var counter = 4
  val thread = Thread.currentThread
  var otherthread: Thread = null

  trait TActor extends Actor {
    def receive = new Receive {
      val recv = receiveT
      def isDefinedAt(o: Any) = recv.isDefinedAt(o)
      def apply(o: Any) {
        if (Thread.currentThread ne thread)
          otherthread = Thread.currentThread
        recv(o)
      }
    }
    def receiveT: Receive
  }

  class ReplyActor extends TActor {
    import context.system
    var replyTo: ActorRef = null

    def receiveT = {
      case "complexRequest" ⇒ {
        replyTo = sender
        val worker = TestActorRef(Props[WorkerActor])
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = TestActorRef(Props[WorkerActor])
        worker ! sender
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ sender ! "simpleReply"
    }
  }

  class WorkerActor() extends TActor {
    def receiveT = {
      case "work" ⇒
        sender ! "workDone"
        context stop self
      case replyTo: Promise[_] ⇒ replyTo.asInstanceOf[Promise[Any]].success("complexReply")
      case replyTo: ActorRef   ⇒ replyTo ! "complexReply"
    }
  }

  class SenderActor(replyActor: ActorRef) extends TActor {

    def receiveT = {
      case "complex"  ⇒ replyActor ! "complexRequest"
      case "complex2" ⇒ replyActor ! "complexRequest2"
      case "simple"   ⇒ replyActor ! "simpleRequest"
      case "complexReply" ⇒ {
        counter -= 1
      }
      case "simpleReply" ⇒ {
        counter -= 1
      }
    }
  }

  class Logger extends Actor {
    var count = 0
    var msg: String = _
    def receive = {
      case Warning(_, _, m: String) ⇒ count += 1; msg = m
    }
  }

  class ReceiveTimeoutActor(target: ActorRef) extends Actor {
    context setReceiveTimeout 1.second
    def receive = {
      case ReceiveTimeout ⇒
        target ! "timeout"
        context stop self
    }
  }

  /**
   * Forwarding `Terminated` to non-watching testActor is not possible,
   * and therefore the `Terminated` message is wrapped.
   */
  case class WrappedTerminated(t: Terminated)

}

class TestActorRefSpec extends AkkaSpec("disp1.type=Dispatcher") with DefaultTimeout {

  import TestActorRefSpec._

  @Before override def beforeEach(): Unit = otherthread = null

  private def assertThread(): Unit = otherthread must (be(null) or equal(thread))

  @Test def `must used with TestActorRef`: Unit = {
    val a = TestActorRef(Props(new Actor {
      val nested = TestActorRef(Props(new Actor { def receive = { case _ ⇒ } }))
      def receive = { case _ ⇒ sender ! nested }
    }))
    assertThat(a, notNullValue)
    val nested = Await.result((a ? "any").mapTo[ActorRef], timeout.duration)
    assertThat(nested, notNullValue)
    assertThat(a, not(sameInstance(nested)))
  }

  @Test def `must used with ActorRef`: Unit = {
    val a = TestActorRef(Props(new Actor {
      val nested = context.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
      def receive = { case _ ⇒ sender ! nested }
    }))
    assertThat(a, notNullValue)
    val nested = Await.result((a ? "any").mapTo[ActorRef], timeout.duration)
    assertThat(nested, notNullValue)
    assertThat(a, not(sameInstance(nested)))
  }

  @Test def `must support reply via sender`: Unit = {
    val serverRef = TestActorRef(Props[ReplyActor])
    val clientRef = TestActorRef(Props(classOf[SenderActor], serverRef))

    counter = 4

    clientRef ! "complex"
    clientRef ! "simple"
    clientRef ! "simple"
    clientRef ! "simple"

    assertThat(counter, equalTo(0))

    counter = 4

    clientRef ! "complex2"
    clientRef ! "simple"
    clientRef ! "simple"
    clientRef ! "simple"

    assertThat(counter, equalTo(0))

    assertThread()
  }

  @Test def `must stop when sent a poison pill`: Unit = {
    EventFilter[ActorKilledException]() intercept {
      val a = TestActorRef(Props[WorkerActor])
      val forwarder = system.actorOf(Props(new Actor {
        context.watch(a)
        def receive = {
          case t: Terminated ⇒ testActor forward WrappedTerminated(t)
          case x             ⇒ testActor forward x
        }
      }))
      a.!(PoisonPill)(testActor)
      expectMsgPF(5 seconds) {
        case WrappedTerminated(Terminated(`a`)) ⇒ true
      }
      assertThat(a.isTerminated, equalTo(true))
      assertThread()
    }
  }

  @Test def `must restart when Kill:ed`: Unit = {
    EventFilter[ActorKilledException]() intercept {
      counter = 2

      val boss = TestActorRef(Props(new TActor {
        val ref = TestActorRef(Props(new TActor {
          def receiveT = { case _ ⇒ }
          override def preRestart(reason: Throwable, msg: Option[Any]) { counter -= 1 }
          override def postRestart(reason: Throwable) { counter -= 1 }
        }), self, "child")

        override def supervisorStrategy =
          OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1 second)(List(classOf[ActorKilledException]))

        def receiveT = { case "sendKill" ⇒ ref ! Kill }
      }))

      boss ! "sendKill"

      assertThat(counter, equalTo(0))
      assertThread()
    }
  }

  @Test def `must support futures`: Unit = {
    val a = TestActorRef[WorkerActor]
    val f = a ? "work"
    // CallingThreadDispatcher means that there is no delay
    assertThat(f, equalTo('completed))
    assertThat(Await.result(f, timeout.duration), equalTo("workDone"))
  }

  @Test def `must support receive timeout`: Unit = {
    val a = TestActorRef(new ReceiveTimeoutActor(testActor))
    expectMsg("timeout")
  }

  @Test def `must allow access to internals`: Unit = {
    val ref = TestActorRef(new TActor {
      var s: String = _
      def receiveT = {
        case x: String ⇒ s = x
      }
    })
    ref ! "hallo"
    val actor = ref.underlyingActor
    assertThat(actor.s, equalTo("hallo"))
  }

  @Test def `must set receiveTimeout to None`: Unit = {
    val a = TestActorRef[WorkerActor]
    assertThat(a.underlyingActor.context.receiveTimeout, sameInstance(Duration.Undefined))
  }

  @Test def `must set CallingThreadDispatcher`: Unit = {
    val a = TestActorRef[WorkerActor]
    assertThat(a.underlying.dispatcher.getClass, equalTo(classOf[CallingThreadDispatcher]))
  }

  @Test def `must allow override of dispatcher`: Unit = {
    val a = TestActorRef(Props[WorkerActor].withDispatcher("disp1"))
    assertThat(a.underlying.dispatcher.getClass, equalTo(classOf[Dispatcher]))
  }

  @Test def `must proxy receive for the underlying actor without sender`: Unit = {
    val ref = TestActorRef[WorkerActor]
    ref.receive("work")
    assertThat(ref.isTerminated, equalTo(true))
  }

  @Test def `must proxy receive for the underlying actor with sender`: Unit = {
    val ref = TestActorRef[WorkerActor]
    ref.receive("work", testActor)
    assertThat(ref.isTerminated, equalTo(true))
    expectMsg("workDone")
  }

}