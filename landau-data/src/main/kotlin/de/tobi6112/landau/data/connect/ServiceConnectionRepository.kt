/** Contains Repository */
package de.tobi6112.landau.data.connect

import de.tobi6112.landau.core.connect.Service
import de.tobi6112.landau.core.connect.ServiceConnection
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/** Connection repository */
interface ServiceConnectionRepository {
  /**
   * Saves connection
   *
   * @param connection connection
   */
  fun saveServiceConnection(connection: ServiceConnection)

  /**
   * Checks whether the identifier is already taken in a service
   *
   * @param service service
   * @param identifier identifier
   * @return true if taken, false if not
   */
  fun isIdentifierAlreadyTaken(service: Service, identifier: String): Boolean

  /**
   * Checks whether the user is already connected with a service
   *
   * @param discordId user id
   * @param service service
   * @return true if connected, false if not
   */
  fun isUserAlreadyConnected(discordId: Long, service: Service): Boolean
}

/** Default implementation */
class DefaultServiceConnectionRepository : ServiceConnectionRepository {
  override fun saveServiceConnection(connection: ServiceConnection) {
    return transaction {
      ServiceConnectionTable.insert {
        it[id] = UUID.randomUUID()
        it[discordId] = connection.discordId
        it[service] = connection.service
        it[serviceIdentifier] = connection.serviceIdentifier
      }
    }
  }

  override fun isIdentifierAlreadyTaken(service: Service, identifier: String): Boolean {
    return !transaction {
      ServiceConnectionTable.select {
        (ServiceConnectionTable.serviceIdentifier eq identifier) and
            (ServiceConnectionTable.service eq service)
      }
          .empty()
    }
  }

  override fun isUserAlreadyConnected(discordId: Long, service: Service): Boolean {
    return !transaction {
      ServiceConnectionTable.select {
        (ServiceConnectionTable.discordId eq discordId) and
            (ServiceConnectionTable.service eq service)
      }
          .empty()
    }
  }
}
