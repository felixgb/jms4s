package jms4s

import cats.data.NonEmptyList
import cats.effect.{ Blocker, Concurrent, ContextShift, Resource, Sync }
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import jms4s.JmsAcknowledgerConsumer.AckAction
import jms4s.JmsAcknowledgerConsumer.AckAction.Destination
import jms4s.JmsAcknowledgerConsumer.JmsAcknowledgerConsumerPool.JmsResource
import jms4s.config.{ DestinationName, QueueName }
import jms4s.jms.{ JmsConnection, JmsMessage, JmsMessageConsumer }
import jms4s.model.SessionType
import jms4s.model.SessionType.ClientAcknowledge

import scala.concurrent.duration.FiniteDuration

trait JmsAcknowledgerConsumer[F[_]] {
  def handle(f: JmsMessage[F] => F[AckAction]): F[Unit]
}

object JmsAcknowledgerConsumer {

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    connection: JmsConnection[F],
    inputDestinationName: DestinationName,
    concurrencyLevel: Int
  ): Resource[F, JmsAcknowledgerConsumer[F]] =
    for {
      input <- Resource.liftF(
                connection.createSession(ClientAcknowledge).use(_.createDestination(inputDestinationName))
              )
      queue <- Resource.liftF(Queue.bounded[F, JmsResource[F]](concurrencyLevel))
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              session  <- connection.createSession(ClientAcknowledge)
              consumer <- session.createConsumer(input)
              _        <- Resource.liftF(queue.enqueue1(JmsResource(consumer, Map.empty)))
            } yield ()
          }
    } yield build(queue, concurrencyLevel, connection.blocker)

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    connection: JmsConnection[F],
    inputDestinationName: DestinationName,
    outputDestinationNames: NonEmptyList[DestinationName],
    concurrencyLevel: Int
  ): Resource[F, JmsAcknowledgerConsumer[F]] =
    for {
      inputDestination <- Resource.liftF(
                           connection
                             .createSession(SessionType.ClientAcknowledge)
                             .use(_.createDestination(inputDestinationName))
                         )
      outputDestinations <- Resource.liftF(
                             outputDestinationNames
                               .traverse(
                                 outputDestinationName =>
                                   connection
                                     .createSession(SessionType.ClientAcknowledge)
                                     .use(_.createDestination(outputDestinationName))
                                     .map(jmsDestination => (outputDestinationName, jmsDestination))
                               )
                           )
      queue <- Resource.liftF(Queue.bounded[F, JmsResource[F]](concurrencyLevel))
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              session  <- connection.createSession(SessionType.ClientAcknowledge)
              consumer <- session.createConsumer(inputDestination)
              producers <- outputDestinations.traverse {
                            case (outputDestinationName, outputDestination) =>
                              session
                                .createProducer(outputDestination)
                                .map(jmsProducer => (outputDestinationName, new JmsProducer(jmsProducer)))
                          }.map(_.toNem)
              _ <- Resource.liftF(queue.enqueue1(JmsResource(consumer, producers.toSortedMap)))
            } yield ()
          }
    } yield build(queue, concurrencyLevel, connection.blocker)

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    connection: JmsConnection[F],
    inputDestinationName: DestinationName,
    outputDestinationName: DestinationName,
    concurrencyLevel: Int
  ): Resource[F, JmsAcknowledgerConsumer[F]] =
    for {
      inputDestination <- Resource.liftF(
                           connection
                             .createSession(SessionType.ClientAcknowledge)
                             .use(_.createDestination(inputDestinationName))
                         )
      outputDestination <- Resource.liftF(
                            connection
                              .createSession(SessionType.ClientAcknowledge)
                              .use(_.createDestination(outputDestinationName))
                          )
      pool <- Resource.liftF(Queue.bounded[F, JmsResource[F]](concurrencyLevel))
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              session     <- connection.createSession(SessionType.ClientAcknowledge)
              consumer    <- session.createConsumer(inputDestination)
              jmsProducer <- session.createProducer(outputDestination)
              producer    = Map(outputDestinationName -> new JmsProducer(jmsProducer))
              _           <- Resource.liftF(pool.enqueue1(JmsResource(consumer, producer)))
            } yield ()
          }
    } yield build(pool, concurrencyLevel, connection.blocker)

  private def build[F[_]: ContextShift: Concurrent](
    pool: Queue[F, JmsResource[F]],
    concurrencyLevel: Int,
    blocker: Blocker
  ): JmsAcknowledgerConsumer[F] =
    (f: JmsMessage[F] => F[AckAction]) =>
      Stream
        .emits(0 until concurrencyLevel)
        .as(
          Stream.eval(
            for {
              resource <- pool.dequeue1
              message  <- resource.consumer.receiveJmsMessage
              res      <- f(message)
              _ <- res match {
                    case AckAction.Ack   => blocker.blockOn(Sync[F].delay(message.wrapped.acknowledge()))
                    case AckAction.NoAck => Sync[F].unit
                    case AckAction.Send(destinations) =>
                      blocker.blockOn(
                        destinations.traverse_ {
                          case Destination(name, delay) =>
                            delay.fold(
                              ifEmpty = resource
                                .producers(name)
                                .publish(message)
                            )(
                              f = d =>
                                resource
                                  .producers(name)
                                  .publish(message, d)
                            )
                        } *> Sync[F].delay(message.wrapped.acknowledge())
                      )
                  }
              _ <- pool.enqueue1(resource)
            } yield ()
          )
        )
        .parJoin(concurrencyLevel)
        .repeat
        .compile
        .drain

  object JmsAcknowledgerConsumerPool {
    private[jms4s] case class JmsResource[F[_]] private[jms4s] (
      consumer: JmsMessageConsumer[F],
      producers: Map[DestinationName, JmsProducer[F]]
    )
  }

  sealed abstract class AckAction extends Product with Serializable

  object AckAction {

    private[jms4s] case object Ack extends AckAction

    // if the client wants to ack groups of messages, it'll pass a sequence of NoAck and then a cumulative Ack
    private[jms4s] case object NoAck extends AckAction

    private[jms4s] case class Send(destinations: NonEmptyList[Destination]) extends AckAction

    private[jms4s] case class Destination(queueName: DestinationName, delay: Option[FiniteDuration])

    val ack: AckAction   = Ack
    val noAck: AckAction = NoAck

    def sendToAndAck(queueNames: QueueName*): Send =
      Send(NonEmptyList.fromListUnsafe(queueNames.toList.map(name => Destination(name, None))))

    def sendWithDelayToAndAck(queueNames: (QueueName, FiniteDuration)*): Send = Send(
      NonEmptyList.fromListUnsafe(
        queueNames.toList.map(x => Destination(x._1, Some(x._2)))
      )
    )
  }
}
