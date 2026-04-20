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

  def insertOrder(
                   conn: Connection,
                   order: (String, String, String, Int, Double, String, String),
                   originalPrice: Double,
                   discount: Double,
                   finalPrice: Double
                 ): Int = {

    val sql =
      """INSERT INTO orders_processed
        |(transaction_date, product_name, expiry_date, quantity, unit_price,
        | channel, payment_method, original_price, discount, final_price)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.stripMargin

    val stmt = conn.prepareStatement(sql)

    stmt.setTimestamp(1, Timestamp.from(Instant.parse(order._1)))
    stmt.setString(2, order._2)
    stmt.setDate(3, java.sql.Date.valueOf(order._3))
    stmt.setInt(4, order._4)
    stmt.setDouble(5, order._5)
    stmt.setString(6, order._6)
    stmt.setString(7, order._7)
    stmt.setDouble(8, originalPrice)
    stmt.setDouble(9, discount)
    stmt.setDouble(10, finalPrice)

    val rows = stmt.executeUpdate()
    stmt.close()
    rows
  }

  def truncateOrders(conn: Connection): Int = {
    val stmt = conn.createStatement()
    val sql = "TRUNCATE TABLE orders_processed"

    val result = stmt.executeUpdate(sql)
    stmt.close()

    result
  }

}
