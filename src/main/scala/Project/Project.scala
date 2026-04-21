package Project
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.io.{Codec, Source}
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try, Using}

case class Order(
 transactionDate: String,
 productName: String,
 expiryDate: String,
 quantity: Int,
 unitPrice: Double,
 channel: String,
 paymentMethod: String
)

case class ProcessedOrder(
 order: Order,
 originalPrice: Double,
 discount: Double,
 finalPrice: Double
)

object Project extends App{

  //=================== Testing Database Connection ===================
  val logger = LoggerFactory.getLogger("RulesEngine")
  logger.info("Engine started")

  val connection  = DB.withConnection(conn => conn.isValid(2))

  connection match {
    case Success(true) => logger.info(s"[DB] Connection successful")
    case Success(false) => logger.warn("[DB] Connection opened but NOT valid")
    case Failure(ex) => logger.error(s"[DB] Connection failed: ${ex.getMessage}")
  }

  //=================== Reading File  ===================
  def readFile(fileName: String, codec: String = Codec.default.toString): Try[List[String]] =
    Using(Source.fromFile(fileName, codec))(_.getLines().toList)

  type Rule = (Order => Boolean, Order => Int)

  //=================== Calculation Engine  ===================
  def calculationEngine(filename: String): Try[List[ProcessedOrder]]= {

    //Splitting lines
    def splitLines(line: String) : List[String]=
      line.split(",").toList

    //=================== Helper Functions ===================

    def getOrder(fields: List[String]): Order =
      Order(
        transactionDate = fields.head,
        productName     = fields(1),
        expiryDate      = fields(2),
        quantity        = fields(3).toInt,
        unitPrice       = fields(4).toDouble,
        channel         = fields(5),
        paymentMethod   = fields(6)
      )

    // Calculates the number of days between the transaction date and the expiry date
    def getDaysBetween(t_date: String , e_date: String): Int = {
      val transaction_date = LocalDate.parse(t_date.split("T")(0))
      val expire_date = LocalDate.parse(e_date)
      ChronoUnit.DAYS.between(transaction_date, expire_date).toInt
    }

    // Calculates the original price before any discount is applied
    def calculateOriginalPrice(quantity: Int , unit_price: Double): Double={
      val result = quantity * unit_price
      BigDecimal(result).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
    }

    // Calculates the final price after applying the discount percentage
    def calculateFinalPrice(original_price: Double , discount: Double) : Double = {
      val final_price = original_price - (original_price * (discount/100))
      BigDecimal(final_price).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
    }

    //=================== Qualifying Rules  ===================

    // Rule 1: Product expires in less than 30 days from the transaction date
    def isRule1Qualified(order: Order): Boolean= {
      val days = getDaysBetween(order.transactionDate ,order.expiryDate)
      days >= 0 && days < 30
    }

    // Rule 2: Product is Cheese or Wine
    def isRule2Qualified(order: Order): Boolean= {
      val product: String = order.productName.split(" ")(0)
      product == "Cheese" || product == "Wine"
    }

    // Rule 3: Transaction was placed on 23rd of March.
    def isRule3Qualified(order: Order): Boolean=
      order.transactionDate.slice(5,10) == "03-23"

    // Rule 4: Quantity of 6 or more units purchased
    def isRule4Qualified(order: Order): Boolean=
      order.quantity >= 6

    // Rule 5: Order placed through the App channel
    def isRule5Qualified(order: Order): Boolean=
      order.channel == "App"

    // Rule 6: Payment made using a Visa card
    def isRule6Qualified(order: Order): Boolean=
       order.paymentMethod == "Visa"

    //=================== Calculation Rules ===================

    //Rule 1 discount > days between
    def calculateRule1Discount(order: Order) :Int=
      if (isRule1Qualified(order)) (30 - getDaysBetween(order.transactionDate, order.expiryDate)) else 0

    //Rule 2 discount > product
    //Get discount for the product > if wine>5% , if cheese>10%
    def calculateRule2Discount(order: Order) :Int= {
      if (isRule2Qualified(order)) {
        val product: String = order.productName.split(" ")(0)
        product match {
          case "Cheese" => 10
          case "Wine"   => 5
          case _        => 0
        }
      }
      else 0
    }

    //Rule 3 discount > transaction date
    def calculateRule3Discount(order: Order) :Int =
      if (isRule3Qualified(order)) 50 else 0

    //Rule 4 discount > quantity
    def calculateRule4Discount(order: Order) :Int= {
      if (isRule4Qualified(order)) {
        order.quantity match {
          case q if q >= 6 && q <= 9  => 5
          case q if q >= 10 && q <= 14 => 7
          case _            => 10
        }
      }
      else 0
    }

    //Rule 5 discount > channel
    def calculateRule5Discount(order: Order) :Int= {
      if (isRule5Qualified(order)){
        val buckets = Math.ceil(order.quantity / 5.0).toInt
        buckets * 5
      }
      else 0
    }

    //Rule 6 discount: 5% flat for Visa payments.
    def calculateRule6Discount(order: Order) :Int =
      if (isRule6Qualified(order)) 5 else 0

    //=================== Calculating Final Discount ===================
    val getDiscountRules: List[Rule] = List(
        (isRule1Qualified, calculateRule1Discount),
        (isRule2Qualified, calculateRule2Discount),
        (isRule3Qualified, calculateRule3Discount),
        (isRule4Qualified, calculateRule4Discount),
        (isRule5Qualified, calculateRule5Discount),
        (isRule6Qualified, calculateRule6Discount)
      )

    def getFinalDiscount(r: Order, rules: List[Rule]): Double = {

      val discounts =
        rules
          .filter { case (qualify, _) => qualify(r) }
          .map { case (_, getDiscount) => getDiscount(r) }
          .sortBy(-_)
          .take(2)

      if (discounts.nonEmpty) discounts.sum / discounts.size.toDouble
      else 0.0
    }

    //====================== Processing Pipeline ======================

    def processOrder(orders: List[String]): List[ProcessedOrder]={
      logger.debug(s"[PARSE] First parsed order: ${splitLines(orders.head)}")
      logger.info("[PIPELINE] Starting processing orders...")
      val startTime = System.currentTimeMillis()

      val results = orders.
        map(splitLines).
        map(getOrder).
        map { order =>
          val discount = getFinalDiscount(order,getDiscountRules)
          val originalPrice = calculateOriginalPrice(order.quantity, order.unitPrice)
          val finalPrice = calculateFinalPrice(originalPrice, discount)

          ProcessedOrder(order, originalPrice, discount, finalPrice)
        }


      val endTime = System.currentTimeMillis()
      val durationSec = (endTime - startTime) / 1000.0
      logger.info(s"[PIPELINE] Completed ${results.size} orders in $durationSec sec")

      // Log a sample of the first 5 processed orders for quick sanity checking
      val sample = results.take(5).map { po =>
        s"${po.order.productName} → Original: ${po.originalPrice} → Discount: ${po.discount}% → Final: ${po.finalPrice}"
      }.mkString("\n[SAMPLE RESULTS]:\n", "\n", "\n")

      logger.info(sample)
      results
    }

    // Load file, skip header row, then process
    val orders: Try[List[String]] = readFile(filename)
    orders match {
      case Success(list) => logger.info(s"[FILE] Loaded orders: ${list.size}")
      case Failure(_)    => logger.error("[FILE] Failed to load orders")
    }
    orders.map(_.drop(1)).map(processOrder)
  }


  //=================== Entry Point: Run & Insert ===================
  val filename: String = "TRX1000.csv"
  calculationEngine(filename) match{

    case Failure(ex) =>
      logger.error("RULES ENGINE FINISHED WITH FAILURE")

    // if function succeeded > insert results into table
    case Success(results) => {
      logger.info(s"[DB] Starting Load for ${results.size} records")

      val loading_result = DB.withConnection { conn =>

        logger.info("[DB] Truncating table...")
        DB.truncateOrders(conn)
        logger.info("[DB] Table truncated successfully")

        logger.info(s"[DB] Inserting ${results.size} records...")
        DB.insertOrders(conn, results)
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


