package fs2jms.ibmmq

import cats.effect.{ Resource, Sync }
import cats.implicits._
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import fs2jms.config.{ Config, Endpoint }
import io.chrisdavenport.log4cats.Logger
import javax.jms.QueueConnection

object ibmMQ {

  def makeConnection[F[_]: Sync: Logger](jms: Config): Resource[F, QueueConnection] =
    for {
      connection <- Resource.fromAutoCloseable[F, QueueConnection](
                     Logger[F].info(s"Opening QueueConnection to MQ at ${hosts(jms.endpoints)}...") *>
                       Sync[F].delay {
                         val queueConnectionFactory: MQQueueConnectionFactory = new MQQueueConnectionFactory()
                         queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_CLIENT)
                         queueConnectionFactory.setQueueManager(jms.qm.value)
                         queueConnectionFactory.setConnectionNameList(hosts(jms.endpoints))
                         queueConnectionFactory.setChannel(jms.channel.value)

                         val connection = (jms.username, jms.password).mapN { (username, password) =>
                           queueConnectionFactory.createQueueConnection(username.value, password.value)
                         }.getOrElse(queueConnectionFactory.createQueueConnection)

                         connection.start()
                         connection
                       }
                   )
      _ <- Resource.liftF(Logger[F].info(s"Opened QueueConnection $connection."))
    } yield connection

  private def hosts(endpoints: List[Endpoint]): String = endpoints.map(e => s"${e.host}(${e.port})").mkString(",")

}
