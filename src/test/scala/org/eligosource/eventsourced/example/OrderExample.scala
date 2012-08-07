package org.eligosource.eventsourced.example

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._

// domain object
case class Order(id: Int, details: String, validated: Boolean)

object Order {
  def apply(details: String) = new Order(-1, details, false)
}

// domain events
case class OrderSubmitted(order: Order)
case class OrderAccepted(order: Order)
case class OrderValidated(order: Order)

object OrderExample1 extends App {
  implicit val system = ActorSystem("example")

  // create a journal
  val journalDir = new java.io.File("target/example")
  val journal = system.actorOf(Props(new Journal(journalDir)))

  // create a destination for output events
  val destination = system.actorOf(Props[Destination])

  // create an event-sourced processor
  val processor = system.actorOf(Props[Processor])

  // create and configure an event-sourcing component
  // with event processor and a named output channel
  val orderComponent = Component(1, journal)
    .addDefaultOutputChannelToActor("dest", destination)
    .setProcessor(processor)

  // recover processor state from journaled events
  orderComponent.init()

  // send some events
  orderComponent.inputProducer ! OrderSubmitted(Order("foo"))
  orderComponent.inputProducer ! OrderSubmitted(Order("bar"))

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // event-sourced processor
  class Processor extends Actor {
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case OrderSubmitted(order) => {
        val id = orders.size
        val upd = order.copy(id = id)
        orders = orders + (id -> upd)
        sender ! Publish("dest", OrderAccepted(upd))
      }
    }
  }

  // output event destination
  class Destination extends Actor {
    def receive = {
      case msg: Message => { println("received event %s" format msg.event); sender ! Ack }
    }
  }
}

object OrderExample2 extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journalDir = new java.io.File("target/example")
  val journal = system.actorOf(Props(new Journal(journalDir)))

  // create a destination for output events
  val destination = system.actorOf(Props[Destination])

  // create and configure an event-sourcing component
  // with event processor and a named output channel
  val orderComponent = Component(1, journal)
    .addDefaultOutputChannelToActor("dest", destination)
    .setProcessor(outputChannels => system.actorOf(Props(new Processor(outputChannels))))

  // recover processor state from journaled events
  orderComponent.init()

  // send some events
  orderComponent.inputChannel ! Message(OrderSubmitted(Order("foo")))
  orderComponent.inputChannel ! Message(OrderSubmitted(Order("bar")))

  // and expect a reply
  orderComponent.inputProducer ? OrderSubmitted(Order("baz")) onSuccess {
    case order: Order => println("received order %s" format order)
  }

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // event-sourced processor
  class Processor(outputChannels: Map[String, ActorRef]) extends Actor {
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case msg: Message => msg.event match {
        case OrderSubmitted(order) => {
          val id = orders.size
          val upd = order.copy(id = id)
          orders = orders + (id -> upd)
          msg.sender.foreach(_ ! upd)
          outputChannels("dest") ! msg.copy(event = OrderAccepted(upd))
        }
      }
    }
  }

  // output event destination
  class Destination extends Actor {
    def receive = {
      case msg: Message => { println("received event %s" format msg.event); sender ! Ack }
    }
  }
}

