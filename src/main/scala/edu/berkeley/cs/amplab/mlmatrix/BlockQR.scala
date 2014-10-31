package edu.berkeley.cs.amplab.mlmatrix

import java.util.concurrent.ThreadLocalRandom
import scala.collection.mutable.ArrayBuffer

import breeze.linalg._

import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._

import edu.berkeley.cs.amplab.mlmatrix.util.Utils

case class RowJoinedPartition(
  val blockIdRow: Int,
  val blockIdCol: Int,
  val yPart: DenseMatrix[Double],
  val aPart: DenseMatrix[Double],
  val yTimesA: DenseMatrix[Double])

class BlockQR extends Logging with Serializable {

  def qMinusS(qPart: DenseMatrix[Double]) = {
    val result = Utils.cloneMatrix(qPart) // if (dup) qPart.dup() else qPart
    val minmn = math.min(qPart.rows, qPart.cols)
    val S = DenseMatrix.eye[Double](minmn)
    for (i <- 0 until minmn) {
      val d = qPart(i, i)
      S(i, i) = -1*math.signum(d)
      result(i, i) = d + math.signum(d)
    }
    (result, S)
  }

  def modifiedLU(qb: RowPartitionedMatrix, rb: DenseMatrix[Double]) = {
    val qPartitionInfo = qb.rdd.sparkContext.broadcast(qb.getPartitionInfo)
    val firstBlockQ = qb(0 until rb.rows, 0 until rb.rows).collect()
    val qs = qMinusS(firstBlockQ)
    val lup = Utils.decomposeLowerUpper(breeze.linalg.LU(qs._1)._1)
    val firstLUS = (lup._1, lup._2, qs._2)

    val lMat = qb.rdd.sparkContext.broadcast(firstLUS._1)
    val uMat = qb.rdd.sparkContext.broadcast(firstLUS._2)

    // Step 4: Construct tMat, rMat and yMat from L,U,S etc.
    val yMat = qb.rdd.mapPartitionsWithIndex { case (part, iter) =>
      if (qPartitionInfo.value.contains(part)) {
        val blockParts = qPartitionInfo.value(part)
        iter.zip(blockParts.iterator).map { case (lm, b) =>
          if (b.startRow < rb.rows) {
            if (b.startRow + lm.mat.rows <= rb.rows) {
              (b.blockId, lMat.value(0 until lm.mat.rows, ::))
            } else {
              // Extract the part of the block which is after rb.rows
              val remPart = lm.mat(rb.rows - b.startRow.toInt until lm.mat.rows, ::)
              val toKeep = lm.mat(0 until rb.rows, ::)
              val updated = (uMat.value.t \ remPart.t).t
              (b.blockId, DenseMatrix.vertcat(lMat.value(b.startRow.toInt until lMat.value.rows, ::), updated))
            }
          } else {
            // TODO: Figure out a better way to do this without so many transpose calls
            (b.blockId, (uMat.value.t \ lm.mat.t).t)
          }
        }
      } else {
        Iterator()
      }
    }

    val x = firstLUS._2 :* -1.0

    val tMat = (x * (firstLUS._3)) * (
      (firstLUS._1((0 until firstLUS._1.cols), ::) \
       DenseMatrix.eye[Double](firstLUS._1.cols)).t)

    val rMat = firstLUS._3 * (rb)

    (yMat, tMat, rMat)
  }

  def qrR(mat: BlockPartitionedMatrix): BlockPartitionedMatrix = {
    // 2D Block QR based on "Reconstructing Householder Vectors from Tall-Skinny QR"
    // http://www.eecs.berkeley.edu/Pubs/TechRpts/2013/EECS-2013-175.pdf
    val numColBlocks = mat.numColBlocks

    var trailingMatrix = mat

    // Collect different RDDs containing block partitions
    // We will do a union of them at the end and then create the output R.
    var rMatrixParts = new ArrayBuffer[RDD[BlockPartition]]

    // Loop over number of column blocks
    (0 until numColBlocks).foreach { colBlock =>
      // Contract is that trailing matrix contains blocks that need to be QR'ed
      // so always get its first column block 
      val cb = trailingMatrix.getColBlock(0).cache()

      // Step 1: Calculate TSQR of first block
      // Step 2: Reconstruct Q from QRTree
      val (qb, rb) = new TSQR().qrQR(cb)

      val localqb = qb.collect()

      // Step 3: LU of Q - S
      val (yMat, tMat, rMat) = modifiedLU(qb, rb)

      // Create an RDD from rMat
      rMatrixParts += mat.rdd.context.makeRDD(Seq(new BlockPartition(colBlock, colBlock, rMat)), 1)

      if (colBlock != numColBlocks - 1) {
        val tMatBC = qb.rdd.sparkContext.broadcast(tMat)
        // Trailing update
        // Contract is that trailing matrix contains blocks that need to be QR'ed
        // so always get its remaining blocks
        val otherColsBlocked = trailingMatrix.getBlockRange(0, trailingMatrix.numRowBlocks,
          1, trailingMatrix.numColBlocks)

        val trailingWithRowBlocks = otherColsBlocked.rdd.map(x => (x.blockIdRow, x))
        val rowJoined = yMat.join(trailingWithRowBlocks).map { x =>
          val rowPart = x._2._1
          val trailingPart = x._2._2
          val res = rowPart.t * (trailingPart.mat)
          (trailingPart.blockIdCol,
            RowJoinedPartition(trailingPart.blockIdRow, trailingPart.blockIdCol,
                               rowPart, trailingPart.mat, res))
        }.cache()
       
        // Sum its outputs
        val colSums = rowJoined.map(x => (x._1, x._2.yTimesA)).reduceByKey { case (x, y) =>
          x + y
        }
       
        // Do the column wise multiply, subtract
        val finalSums = colSums.join(rowJoined).map { x =>
          val colSumVal = x._2._1
          val rowJoinedVal = x._2._2
          val res = rowJoinedVal.aPart - 
            (rowJoinedVal.yPart * (tMatBC.value.t * (colSumVal)))
          BlockPartition(rowJoinedVal.blockIdRow, rowJoinedVal.blockIdCol, res)
        }
       
        rowJoined.unpersist()
       
        val finalBlockMatrix = new BlockPartitionedMatrix(trailingMatrix.numRowBlocks,
          trailingMatrix.numColBlocks - 1, finalSums).cache()
       
        val rPartsMat = finalBlockMatrix(0 until rMat.rows, ::)

        rMatrixParts += rPartsMat.rdd.map { b => 
          BlockPartition(colBlock + b.blockIdRow, colBlock + b.blockIdCol + 1, b.mat)
        }
       
        // TODO: This won't work if we have more than 2B rows ?
        trailingMatrix = finalBlockMatrix(rMat.rows until finalBlockMatrix.numRows.toInt, ::)
      }
    }
    val unionedRDD = rMatrixParts.reduceLeft { (a, b) => a.union(b) }
    new BlockPartitionedMatrix(mat.numRowBlocks, mat.numColBlocks, unionedRDD)
  }
}

object BlockQR extends Logging {

  def main(args: Array[String]) {
    if (args.length < 5) {
      println("Usage: BlockQR <master> <sparkHome> <numRows> <numCols> <rowsPerPart> <colsPerPart>")
      System.exit(0)
    }

    val sparkMaster = args(0)
    val sparkHome = args(1)
    val numRows = args(2).toInt
    val numCols = args(3).toInt
    val rowsPerPart = args(4).toInt
    val colsPerPart = args(5).toInt

    val sc = new SparkContext(sparkMaster, "BlockQR", sparkHome,
      SparkContext.jarOfClass(this.getClass).toSeq)

    val matrixRDD = sc.parallelize(1 to numRows).map { row =>
      Array.fill(numCols)(ThreadLocalRandom.current().nextGaussian())
    }

    matrixRDD.cache().count()

    var begin = System.nanoTime()
    val A = BlockPartitionedMatrix.fromArray(matrixRDD, rowsPerPart, colsPerPart)
    var end = System.nanoTime()
  }
}
