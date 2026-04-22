package Project

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.io.{Codec, Source}
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try, Using}
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport

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

object Project extends App {
  val logger = LoggerFactory.getLogger("RulesEngine")
  logger.info("Engine started")

  // Configuration Constants
  val filename: String = "TRX10M.csv"
  val batchSize: Int = 50000 // Number of rows to process in memory at once

  // Define Rule Type
  type Rule = (Order => Boolean, Order => Int)

  //=================== Helper Functions ===================

  //Splitting lines
  def splitLines(line: String): List[String] = line.split(",").toList

  def getOrder(fields: List[String]): Order =
    Order(
      transactionDate = fields.head,
      productName = fields(1),
      expiryDate = fields(2),
      quantity = fields(3).toInt,
      unitPrice = fields(4).toDouble,
      channel = fields(5),
      paymentMethod = fields(6)
    )

  // Calculates the number of days between the transaction date and the expiry date
  def getDaysBetween(t_date: String, e_date: String): Int = {
    val transaction_date = LocalDate.parse(t_date.split("T")(0))
    val expire_date = LocalDate.parse(e_date)
    ChronoUnit.DAYS.between(transaction_date, expire_date).toInt
  }

  // Calculates the original price before any discount is applied
  def calculateOriginalPrice(quantity: Int, unit_price: Double): Double = {
    val result = quantity * unit_price
    BigDecimal(result).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  // Calculates the final price after applying the discount percentage
  def calculateFinalPrice(original_price: Double, discount: Double): Double = {
    val final_price = original_price - (original_price * (discount / 100))
    BigDecimal(final_price).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  //=================== Qualifying Rules ===================

  // Rule 1: Product expires in less than 30 days from the transaction date
  def isRule1Qualified(order: Order): Boolean = {
    val days = getDaysBetween(order.transactionDate, order.expiryDate)
    days >= 0 && days < 30
  }

  // Rule 2: Product is Cheese or Wine
  def isRule2Qualified(order: Order): Boolean = {
    val product = order.productName.split(" ")(0)
    product == "Cheese" || product == "Wine"
  }

  // Rule 3: Transaction was placed on 23rd of March
  def isRule3Qualified(order: Order): Boolean = order.transactionDate.slice(5, 10) == "03-23"

  // Rule 4: Quantity of 6 or more units purchased
  def isRule4Qualified(order: Order): Boolean = order.quantity >= 6

  // Rule 5: Order placed through the App channel
  def isRule5Qualified(order: Order): Boolean = order.channel == "App"

  // Rule 6: Payment made using a Visa card
  def isRule6Qualified(order: Order): Boolean = order.paymentMethod == "Visa"

  //=================== Calculation Rules ===================

  //Rule 1 discount > days between
  def calculateRule1Discount(order: Order): Int =
    if (isRule1Qualified(order)) (30 - getDaysBetween(order.transactionDate, order.expiryDate)) else 0

  //Rule 2 discount > product
  //Get discount for the product > if wine>5% , if cheese>10%
  def calculateRule2Discount(order: Order): Int = {
    if (isRule2Qualified(order)) {
      order.productName.split(" ")(0) match {
        case "Cheese" => 10
        case "Wine"   => 5
        case _        => 0
      }
    } else 0
  }

  //Rule 3 discount > transaction date
  def calculateRule3Discount(order: Order): Int = if (isRule3Qualified(order)) 50 else 0

  //Rule 4 discount > quantity
  def calculateRule4Discount(order: Order): Int = {
    if (isRule4Qualified(order)) {
      order.quantity match {
        case q if q >= 6 && q <= 9   => 5
        case q if q >= 10 && q <= 14 => 7
        case _                       => 10
      }
    } else 0
  }

  //Rule 5 discount > channel
  def calculateRule5Discount(order: Order): Int = {
    if (isRule5Qualified(order)) Math.ceil(order.quantity / 5.0).toInt * 5 else 0
  }

  //Rule 6 discount: 5% flat for Visa payments
  def calculateRule6Discount(order: Order): Int = if (isRule6Qualified(order)) 5 else 0

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
    val discounts = rules
      .filter { case (qualify, _) => qualify(r) }
      .map { case (_, getDiscount) => getDiscount(r) }
      .sortBy(-_)
      .take(2)

    if (discounts.nonEmpty) discounts.sum / discounts.size.toDouble else 0.0
  }

  //=================== Main Processing Pipeline ===================

  def runEngine(): Unit = {
    val forkJoinPool = new java.util.concurrent.ForkJoinPool(8)

    //== Testing DB Connection First ==
    //== if succeeded truncate, process, insert ==
    DB.withConnection(conn => conn.isValid(2)) match {
      case Failure(ex) => logger.error(s"[DB] Could not establish connection: ${ex.getMessage}. Aborting.")

      case Success(true) =>
        logger.info("[DB] Connection successful")

        logger.info("[DB] Truncating table...")
        DB.withConnection(DB.truncateOrders)
        logger.info("[DB] Table truncated successfully")

        logger.info(s"[DB] Starting Processing and Loading total orders")
        val startTime = System.currentTimeMillis()

        // Using Source.fromFile as an Iterator to avoid loading 10M lines into memory
        val processResult = Using(Source.fromFile(filename)(Codec.UTF8)) { source =>
          val lines = source.getLines().buffered
          if (lines.hasNext) lines.next() // Drop header

          // Peek at first row for log
          if (lines.hasNext) logger.debug(s"[PARSE] First parsed order: ${splitLines(lines.head)}")

          lines.grouped(batchSize).zipWithIndex.foreach { case (batch, index) =>
            // par > splits work across all CPU cores
            val parBatch = batch.par
            parBatch.tasksupport = new ForkJoinTaskSupport(forkJoinPool)

            val processedBatch = parBatch
              .map(splitLines)
              .map(getOrder)
              .map { order =>
                val discount = getFinalDiscount(order, getDiscountRules)
                val originalPrice = calculateOriginalPrice(order.quantity, order.unitPrice)
                val finalPrice = calculateFinalPrice(originalPrice, discount)

                ProcessedOrder(order, originalPrice, discount, finalPrice)
              }.toList // Converts the parallel result back to a standard List for the DB

            // 2. Insert the processed batch into the Database
            logger.info(s"[PIPELINE] Processed chunk ${index + 1} of $batchSize records and Inserting...")
            DB.withConnection { conn =>
              DB.insertOrders(conn, processedBatch)
            }

            if ((index + 1) % 10 == 0) {
              logger.info(s"[PROGRESS] Processed and inserted ${(index + 1) * batchSize} records...")
            }
          }
        }

        forkJoinPool.shutdown()

        processResult match {
          case Success(_) =>
            logger.info("[DB] Data inserted successfully!")
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            logger.info(s"RULES ENGINE FINISHED SUCCESSFULLY in $duration sec")
          case Failure(ex) =>
            logger.error(s"RULES ENGINE FAILED during processing: ${ex.getMessage}")
        }
    }
  }

  // Entry Point
  runEngine()
}
