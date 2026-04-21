package Project
import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}
import java.time.Instant
import java.sql.Timestamp
import com.typesafe.config.ConfigFactory

object DB {
  val config = ConfigFactory.load()

  val url : String= config.getString("db.url")
  val user : String= config.getString("db.user")
  val password :String =  config.getString("db.password")

  //[A] means it can work with any type and return it
  def withConnection[A](f: Connection => A): Try[A] = {
    Using(DriverManager.getConnection(url, user, password)) { conn =>
      f(conn)
    }
  }

  def insertOrders( conn: Connection, orders: List[ProcessedOrder] ): Int = {

    val sql =
      """INSERT INTO orders_processed
        |(transaction_date, product_name, expiry_date, quantity, unit_price,
        | channel, payment_method, original_price, discount, final_price)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.stripMargin

    val stmt = conn.prepareStatement(sql)

    orders.foreach { po =>

      stmt.setTimestamp(1, Timestamp.from(Instant.parse(po.order.transactionDate)))
      stmt.setString(2, po.order.productName)
      stmt.setDate(3, java.sql.Date.valueOf(po.order.expiryDate))
      stmt.setInt(4, po.order.quantity)
      stmt.setDouble(5, po.order.unitPrice)
      stmt.setString(6, po.order.channel)
      stmt.setString(7, po.order.paymentMethod)
      stmt.setDouble(8, po.originalPrice)
      stmt.setDouble(9, po.discount)
      stmt.setDouble(10, po.finalPrice)

      stmt.addBatch()
    }

    val result = stmt.executeBatch()
    stmt.close()
    result.sum
  }

  def truncateOrders(conn: Connection): Int = {
    val stmt = conn.createStatement()
    val sql = "TRUNCATE TABLE orders_processed"

    val result = stmt.executeUpdate(sql)
    stmt.close()

    result
  }

}
