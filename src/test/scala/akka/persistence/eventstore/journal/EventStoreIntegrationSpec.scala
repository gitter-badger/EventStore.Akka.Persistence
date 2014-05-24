package akka.persistence.eventstore.journal

import akka.persistence.journal.JournalSpec
import com.typesafe.config.ConfigFactory
import java.util.UUID

class EventStoreIntegrationSpec extends JournalSpec {
  lazy val config = ConfigFactory.load()

  private var _pid: String = _

  protected override def beforeEach() = {
    val uuid = UUID.randomUUID().toString
    _pid = s"processor-$uuid"
    super.beforeEach()
  }

  override def pid = _pid
}
