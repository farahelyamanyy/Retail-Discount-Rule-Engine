package Project
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.io.{Codec, Source}
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try, Using}

object Project extends App{

  //======================================= Testing Database Connection ===============================================
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

  def readFile(fileName: String, codec: String = Codec.default.toString): Try[List[String]] = {
    Using(Source.fromFile(fileName, codec)){ source =>
      source.getLines().toList
    }
  }

  type order= (String , String , String , Int, Double,String, String)
  type OrderWithDiscount = (String, String, String, Int, Double, String, String, Double)
  type result =  (order, Double, Double, Double)
  type Rule = (order => Boolean, order => Int)

  def calculationEngine(filename: String): Try[List[result]]= {
    def calculate(orders: List[String]): List[result]={

      //Splitting lines
      def splitLines(s: String) : List[String]={
        s.split(",").toList
      }

      logger.debug(s"[PARSE] First parsed order: ${splitLines(orders.head)}")

      //=========================================== Extracting Data ==================================================

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

      //Calculating days between Transaction date & Expiration date
      def getDaysBetween(t_date: String , e_date: String): Int = {
        val transaction_date = LocalDate.parse(t_date.split("T")(0))
        val expire_date = LocalDate.parse(e_date)
        ChronoUnit.DAYS.between(transaction_date, expire_date).toInt
      }

      //Calculating the original price before discount
      def calculateOriginalPrice(quantity: Int , unit_price: Double): Double={
        val result = quantity * unit_price
        BigDecimal(result).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
      }

      //=========================================== Qualifying Rules  =================================================

      //Checking if order qualified for rule 1 discount
      def isRule1Qualified(order: order): Boolean= {
        val transaction_date: String = order._1
        val expiry_date: String = order._3
        val daysBetween = getDaysBetween(transaction_date,expiry_date)
        daysBetween >= 0 && daysBetween < 30
      }

      //Checking if order qualified for rule 2 discount
      def isRule2Qualified(order: order): Boolean= {
        val product: String = order._2.split(" ")(0)
        product == "Cheese" || product == "Wine"
      }

      //Checking if order qualified for rule 3 discount > placed on 23rd of March
      def isRule3Qualified(order: order): Boolean= {
        val transaction_date: String = order._1.split("T")(0)
        transaction_date == "2023-03-23"
      }

      //Checking if order qualified for rule 4 discount
      def isRule4Qualified(order: order): Boolean= {
        val quantity: Int = order._4
        quantity >= 6
      }

      //Checking if order qualified for rule 5 discount > orders made through the App
      def isRule5Qualified(order: order): Boolean= {
        val channel: String = order._6
        channel == "App"
      }

      //Checking if order qualified for rule 6 discount > orders made using Visa cards
      def isRule6Qualified(order: order): Boolean= {
        val payment_method: String = order._7
        payment_method == "Visa"
      }

      //========================================= Calculation Rules =================================================

      //Calculating discount for rule 1 > days between
      def calculateRule1Discount(order: order) :Int=
        if (isRule1Qualified(order)) (30 - getDaysBetween(order._1, order._3)) else 0

      //Calculating discount for rule 2 > product
      //Get discount for the product > if wine>5% , if cheese>10%
      def calculateRule2Discount(order: order) :Int= {
        if (isRule2Qualified(order)) {
          val product: String = order._2.split(" ")(0)
          product match {
            case "Cheese" => 10
            case "Wine"   => 5
            case _        => 0
          }
        }
        else 0
      }

      //Calculating discount for rule 3 > transaction date
      def calculateRule3Discount(order: order) :Int =
        if (isRule3Qualified(order)) 50 else 0


      //Calculating discount for rule 4 > quantity
      def calculateRule4Discount(order: order) :Int= {
        if (isRule4Qualified(order)) {
          val quantity: Int = order._4
          quantity match {
            case q if q >= 6 && q <= 9  => 5
            case q if q >= 10 && q <= 14 => 7
            case _            => 10
          }
        }
        else 0
      }

      //Calculating discount for rule 5 > channel
      def calculateRule5Discount(order: order) :Int= {
        if (isRule5Qualified(order)){
          val quantity: Int = order._4
          val buckets = Math.ceil(quantity / 5.0).toInt
          buckets * 5
        }
        else 0
      }

      //Calculating discount for rule 6 > payment method
      def calculateRule6Discount(order: order) :Int =
        if (isRule6Qualified(order)) 5 else 0


      //==================================== Calculating Final Discount & Price  ====================================
      def getDiscountRules(): List[Rule] = {
        List(
          (isRule1Qualified, calculateRule1Discount),
          (isRule2Qualified, calculateRule2Discount),
          (isRule3Qualified, calculateRule3Discount),
          (isRule4Qualified, calculateRule4Discount),
          (isRule5Qualified, calculateRule5Discount),
          (isRule6Qualified, calculateRule6Discount)
        )
      }

      def getOrdersWithDiscount(r: order, rules: List[Rule]): OrderWithDiscount = {

        val discounts =
          rules
            .filter { case (qualify, _) => qualify(r) }
            .map { case (_, getDiscount) => getDiscount(r) }
            .sortBy(-_)
            .take(2)

        val discount =
          if (discounts.nonEmpty) discounts.sum / discounts.size.toDouble
          else 0.0

        (r._1, r._2, r._3, r._4, r._5, r._6, r._7, discount)
      }


      // Calculating final price after applying the final discount
      def calculateFinalPrice(original_price: Double , discount: Double) : Double = {
        val final_price = original_price - (original_price * (discount/100))
        BigDecimal(final_price).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
      }

      //=============================================== Pipeline ====================================================

      logger.info("[PIPELINE] Starting processing orders...")
      val startTime = System.currentTimeMillis()

      val results = orders.
        map(splitLines).
        map(getData).
        map { order =>
          val orderWithDiscount = getOrdersWithDiscount(order,getDiscountRules())
          val discount = orderWithDiscount._8
          val original = calculateOriginalPrice(order._4, order._5)
          val finalPrice = calculateFinalPrice(original, discount)

          (order, original, discount, finalPrice)
        }


      val endTime = System.currentTimeMillis()
      logger.info(s"[PIPELINE] Completed ${results.size} orders in ${endTime - startTime} ms")

      logger.info(
        results.take(5).map { case (order,original, discount, finalPrice) =>
            s"${order._2} → Original Price before discount: $original → Discount: $discount% → Final: $finalPrice"
          }
          .mkString("\n[SAMPLE RESULTS]:\n", "\n", "\n")
      )
      results
    }

    val orders: Try[List[String]] = readFile(filename)
    orders match {
      case Success(list) => logger.info(s"[FILE] Loaded orders: ${list.size}")
      case Failure(_)    => logger.error("[FILE] Failed to load orders")
    }
    orders.map(_.drop(1)).map(calculate)
  }

  //======================================= Calling fun & Inserting ==================================================

  val filename: String = "TRX1000.csv"
  calculationEngine(filename) match{
    // if function failed > log the error
    case scala.util.Failure(ex) =>
      logger.error("RULES ENGINE FINISHED WITH FAILURE")

    // if function succeeded > insert results into table
    case Success(results) => {
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
  }
}


