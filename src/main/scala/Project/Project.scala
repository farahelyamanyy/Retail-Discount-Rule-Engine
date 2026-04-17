package Project
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.io.{Codec, Source}
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try, Using}

object Project extends App{

  //======================================= Database Connection ======================================================
  val logger = LoggerFactory.getLogger("RulesEngine")
  logger.info("Engine started")

  val connection  = DB.withConnection(conn => conn.isValid(2))

  connection match {
    case scala.util.Success(true) =>
      logger.info(s"[DB] Connection successful")

    case scala.util.Success(false) =>
      logger.warn("[DB] Connection opened but NOT valid")

    case scala.util.Failure(ex) =>
      logger.error(s"[DB] Connection failed: ${ex.getMessage}")
  }

  //========================================== Reading File  =========================================================

  def readFile(fileName: String, codec: String = Codec.default.toString): List[String] = {
    Source.fromFile(fileName, codec).getLines().toList
  }

  val orders : List[String] = readFile("src/main/scala/Project/TRX1000.csv").drop(1)
  logger.info(s"[FILE] Loaded orders: ${orders.size}")

  def splitLines(s: String) : List[String]={
    s.split(",").toList
  }

  logger.debug(s"[PARSE] First parsed order: ${splitLines(orders.head)}")
  //=========================================== Extracting Data ========================================================
  def getData(t: List[String]): (String , String , String , Int, Double,String, String) = {
    val transactinon_date: String= t.head
    val product: String =  t(1)
    val expiry_date: String = t(2)
    val quantity: Int= t(3).toInt
    val unit_price: Double= t(4).toDouble
    val channel: String = t(5)
    val payment_method: String = t(6)
    (transactinon_date , product, expiry_date , quantity, unit_price, channel , payment_method)
  }

  def getDaysBetween(t_date: String , e_date: String): Int = {
    val transaction_date = LocalDate.parse(t_date.split("T")(0))
    val expire_date = LocalDate.parse(e_date)
    ChronoUnit.DAYS.between(transaction_date, expire_date).toInt
  }

  def calculateOriginalPrice(quantity: Int , unit_price: Double): Double={
    val result = quantity * unit_price
    BigDecimal(result).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  //=========================================== Qualifying Rules  =====================================================
  def isRule1Qualified(order: (String , String , String , Int, Double,String,String)): Boolean= {
    val transaction_date: String = order._1
    val expiry_date: String = order._3
    val daysBetween = getDaysBetween(transaction_date,expiry_date)
    daysBetween >= 0 && daysBetween < 30
  }


  def isRule2Qualified(order: (String , String , String , Int, Double,String,String)): Boolean= {
    val product: String = order._2.split(" ")(0)
    product == "Cheese" || product == "Wine"
  }

  def isRule3Qualified(order: (String , String , String , Int, Double,String,String)): Boolean= {
    val transaction_date: String = order._1.split("T")(0)
    transaction_date == "2023-03-22"
  }

  def isRule4Qualified(order: (String , String , String , Int, Double,String,String)): Boolean= {
    val quantity: Int = order._4
    if (quantity < 6 || quantity == 15) false
    else true
  }


  //========================================= Calculation Rules =====================================================
  def calculateRule1Discount(order: (String , String , String , Int, Double,String,String)) :Int= {
    if (isRule1Qualified(order)) (30 - getDaysBetween(order._1, order._3))
    else 0
  }

  //Get discount for the product > if wine>5% , if cheese>10%
  def calculateRule2Discount(order: (String , String , String , Int, Double,String,String)) :Int= {
    val product: String = order._2.split(" ")(0)
    if (isRule2Qualified(order)) {
      product match {
        case "Cheese" => 10
        case "Wine"   => 5
        case _        => 0
      }
    } else 0
  }

  def calculateRule3Discount(order: (String , String , String , Int, Double,String,String)) :Int= {
    if (isRule3Qualified(order)) 50
    else 0
  }


  // Get discount for quantity
  def calculateRule4Discount(order: (String , String , String , Int, Double,String,String)) :Int= {
    val quantity: Int = order._4
    if (isRule4Qualified(order)) {
      quantity match {
        case q if q >= 6 && q <= 9  => 5
        case q if q >= 10 && q <= 14 => 7
        case q if q > 15             => 10
        case _                       => 0
      }
    } else 0
  }

  def calculateAllRules(order: (String , String , String , Int, Double,String,String)): List[Int] = {
    List(
      calculateRule1Discount(order),
      calculateRule2Discount(order),
      calculateRule3Discount(order),
      calculateRule4Discount(order)
    )
  }


//==================================== Calculating Final Discount & Price  ============================================

  def getMax2Discounts(l : List[Int]): List[Int] = {
    val top2 = l.sorted(Ordering[Int].reverse).take(2)
    top2
  }

  def getAvgDiscount(l: List[Int]): Double={
    l.sum / l.size.toDouble
  }

  def calc_final_discount(l:List[Int]): Double={
    val nonZero = l.filter(_ != 0)

    nonZero.length match {
      case n if n > 1 =>
        getAvgDiscount(getMax2Discounts(nonZero))

      case 1 =>
        nonZero.head.toDouble

      case _ =>
        0.0   // no discount
    }
  }

  def calculateFinalPrice(original_price: Double , discount: Double) : Double = {
    val final_price = original_price - (original_price * (discount/100))
    BigDecimal(final_price).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  //=============================================== Pipeline ==========================================================

  logger.info("[PIPELINE] Starting processing orders...")
  val startTime = System.currentTimeMillis()

  val results =
    orders.
      map(splitLines).
      map(getData).
      map { order =>
        val discounts = calculateAllRules(order)
        val discount = calc_final_discount(discounts)
        val original = calculateOriginalPrice(order._4, order._5)
        val finalPrice = calculateFinalPrice(original, discount)

        (order, original, discount, finalPrice)
      }

  val endTime = System.currentTimeMillis()
  logger.info(s"[PIPELINE] Completed ${results.size} orders in ${endTime - startTime} ms")

  logger.info(
    results.take(5).map { case (order,original, discount, finalPrice) =>
        s"${order._2} → Discount: $discount% → Final: $finalPrice"
      }
      .mkString("\n[SAMPLE RESULTS]:\n", "\n", "\n")
  )


  //========================================== Database Insert ========================================================
  logger.info(s"[DB] Starting Load for ${results.size} records")

  val loading_result = DB.withConnection { conn =>

    logger.info("[DB] Truncating table...")
    DB.truncateOrders(conn)
    logger.info("[DB] Table truncated successfully")

    logger.info(s"[DB] Inserting ${results.size} records...")

    results.foreach { case (order, orig, disc, finalP) =>
      DB.insertOrder(conn, order, orig, disc, finalP)
    }
  }

  loading_result match {
    case scala.util.Success(_) =>
      logger.info("[DB] Data inserted successfully!")
      logger.info("RULES ENGINE FINISHED SUCCESSFULLY")

    case scala.util.Failure(ex) =>
      logger.error(s"[DB] Insert failed: ${ex.getMessage}")
      logger.error("RULES ENGINE FINISHED WITH FAILURE")
  }
}


