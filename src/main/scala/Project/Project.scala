package Project
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.io._
import scala.io.{Codec, Source}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try, Using}
import java.sql.DriverManager;


object Project extends App{

  //======================================= Database Connection ======================================================
  val url = "jdbc:oracle:thin:@localhost:1521:XE"
  val user = "scala_project"
  val password = "123"
  val connection  = Using(DriverManager.getConnection(url, user , password)){ conn =>
    conn.isValid(2)
  }

  connection match {
    case scala.util.Success(true) =>
      println("Connection successful!")

    case scala.util.Success(false) =>
      println("Connection opened but not valid")

    case scala.util.Failure(ex) =>
      println(s"Connection failed: ${ex.getMessage}")
  }


  //========================================== Reading File  =========================================================

  def readFile(fileName: String, codec: String = Codec.default.toString): List[String] = {
    Source.fromFile(fileName, codec).getLines().toList
  }

  val orders : List[String] = readFile("src/main/scala/Project/TRX1000.csv").drop(1)

  def splitLines(s: String) : List[String]={
    s.split(",").toList
  }

//=========================================== Extracting Data ========================================================
  def getTransactionDate(t: List[String]): String = t.head.split("T")(0)
  def getProductName(t: List[String]): String = t(1).split(" ")(0)
  def getExpiryDate(t: List[String]): String = t(2)
  def getQuantity(t: List[String]): Int = t(3).toInt
  def getUnitPrice(t: List[String]): Double = t(4).toDouble

  def getData(t: List[String]): (String , String , String , Int, Double) = {
    val transactinon_date = t.head
    val product =  t(1).split(" ")(0)
    val expiry_date = t(2)
    val quantity = t(3).toInt
    val unit_price = t(4).toDouble
    (transactinon_date , product, expiry_date , quantity, unit_price)
  }

  def getDaysBetween(t_date: String , e_date: String): Int = {
    val transaction_date = LocalDate.parse(t_date.split("T")(0))
    val expire_date = LocalDate.parse(e_date)
    ChronoUnit.DAYS.between(transaction_date, expire_date).toInt
  }

  def calculateOriginalPrice(quantity: Int , unit_price: Double): Double={
    //val result = getQuantity(t) * getUnitPrice(t)
    val result = quantity * unit_price
    BigDecimal(result).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

//============================================= Is Qualified ?  ======================================================
def calculateAllRules(t: (String , String , String , Int, Double)): List[Int] = {
  List(
    calculateRule1Discount(t._1 , t._3),
    calculateRule2Discount(t._2),
    calculateRule3Discount(t._1),
    calculateRule4Discount(t._4)
  )
}

  def isRule1Qualified(t_date : String , e_date: String): Boolean= {
    val daysBetween = getDaysBetween(t_date,e_date)
    daysBetween >= 0 && daysBetween < 30
  }


  def isRule2Qualified(product: String): Boolean= {
    product == "Cheese" || product == "Wine"
  }

  def isRule3Qualified(t_date : String): Boolean= {
    val transaction_date = t_date.split("T")(0)
    transaction_date == "2023-03-22"
  }

  def isRule4Qualified(quantity: Int): Boolean= {
    if (quantity < 6 || quantity == 15) false
    else true
  }


//======================================= Calculating Discounts  ======================================================
  def calculateRule1Discount(t_date : String, e_date: String) :Int= {
    if (isRule1Qualified(t_date, e_date)) (30 - getDaysBetween(t_date,e_date))
    else 0
  }

  //Get discount for the product > if wine>5% , if cheese>10%
  def calculateRule2Discount(product: String) :Int= {
    if (isRule2Qualified(product)) {
      product match {
        case "Cheese" => 10
        case "Wine"   => 5
        case _        => 0
      }
    } else 0
  }

  def calculateRule3Discount(t_date : String) :Int= {
    if (isRule3Qualified(t_date)) 50
    else 0
  }


  // Get discount for quantity
  def calculateRule4Discount(quantity: Int) :Int= {
    if (isRule4Qualified(quantity)) {
      quantity match {
        case q if q >= 6 && q <= 9  => 5
        case q if q >= 10 && q <= 14 => 7
        case q if q > 15             => 10
        case _                       => 0
      }
    } else 0
  }

  def getMax2Discounts(l : List[Int]): List[Int] = {
    val top2 = l.sorted(Ordering[Int].reverse).take(2)
    top2
  }

  def getAvgDiscount(l: List[Int]): Double={
    l.sum / l.size.toDouble
  }


//======================================= Calculating Final Price  ====================================================

  def calculateFinalPrice(price: Double , discount: Double) : Double = {
    val final_price = price - (price * (discount/100))
    BigDecimal(final_price).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

//========================================= Pipeline & Loading  ======================================================
  val logger = LoggerFactory.getLogger("RulesEngine")
  logger.info("Engine started")


  val final_result = orders.
    map(splitLines).
    map(getData).
    groupBy(_._1).
    mapValues(_.map(calculateAllRules)).
    //mapValues(_.flatten.count(_ != 0)).
    mapValues(_.flatten.sorted(Ordering[Int].reverse).take(2)).
    mapValues(getAvgDiscount).
    toMap
    //foreach(println)

  logger.info(s"Engine Ended Successfully")
  //logger.info(s"Sample = ${final_result.toSeq.sortBy(_._1).take(5)}\n")
  logger.info(
    final_result
      .take(5)
      .mkString("\nSample:\n", "\n", "\n")
  )


  val list = List(6, 10, 0, 7)
  println(getAvgDiscount(getMax2Discounts(list)))
  println(calculateFinalPrice(calculateOriginalPrice(6, 122.47),getAvgDiscount(getMax2Discounts(list))))


//  val result = {
//    orders.
//      map(splitLines)
//  }
//
//  val writer = new FileWriter(new File("src/main/scala/Project/result.txt"))
//  try {
//    // result is a List of Arrays, so we iterate and join them into strings
//    result.foreach { lineArray =>
//      writer.write(lineArray.mkString(",") + "\n")
//    }
//  } finally {
//    writer.close()
//  }




}


