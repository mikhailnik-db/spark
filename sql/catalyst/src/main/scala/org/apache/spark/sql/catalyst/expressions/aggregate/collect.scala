/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions.aggregate

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Growable

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.DataTypeMismatch
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.trees.{BinaryLike, UnaryLike}
import org.apache.spark.sql.catalyst.types.PhysicalDataType
import org.apache.spark.sql.catalyst.util.{ArrayData, GenericArrayData, TypeUtils}
import org.apache.spark.sql.errors.QueryErrorsBase
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.BoundedPriorityQueue

/**
 * A base class for collect_list and collect_set aggregate functions.
 *
 * We have to store all the collected elements in memory, and so notice that too many elements
 * can cause GC paused and eventually OutOfMemory Errors.
 */
abstract class Collect[T <: Growable[Any] with Iterable[Any]] extends TypedImperativeAggregate[T] {

  val child: Expression

  override def nullable: Boolean = false

  override def dataType: DataType = ArrayType(child.dataType, false)

  override def defaultResult: Option[Literal] = Option(Literal.create(Array(), dataType))

  protected def convertToBufferElement(value: Any): Any

  override def update(buffer: T, input: InternalRow): T = {
    val value = child.eval(input)

    // Do not allow null values. We follow the semantics of Hive's collect_list/collect_set here.
    // See: org.apache.hadoop.hive.ql.udf.generic.GenericUDAFMkCollectionEvaluator
    if (value != null) {
      buffer += convertToBufferElement(value)
    }
    buffer
  }

  override def merge(buffer: T, other: T): T = {
    buffer ++= other
  }

  protected val bufferElementType: DataType

  private lazy val projection = UnsafeProjection.create(
    Array[DataType](ArrayType(elementType = bufferElementType, containsNull = false)))
  private lazy val row = new UnsafeRow(1)

  override def serialize(obj: T): Array[Byte] = {
    val array = new GenericArrayData(obj.toArray)
    projection.apply(InternalRow.apply(array)).getBytes()
  }

  override def deserialize(bytes: Array[Byte]): T = {
    val buffer = createAggregationBuffer()
    row.pointTo(bytes, bytes.length)
    row.getArray(0).foreach(bufferElementType, (_, x: Any) => buffer += x)
    buffer
  }
}

/**
 * Collect a list of elements.
 */
@ExpressionDescription(
  usage = "_FUNC_(expr) - Collects and returns a list of non-unique elements.",
  examples = """
    Examples:
      > SELECT _FUNC_(col) FROM VALUES (1), (2), (1) AS tab(col);
       [1,2,1]
  """,
  note = """
    The function is non-deterministic because the order of collected results depends
    on the order of the rows which may be non-deterministic after a shuffle.
  """,
  group = "agg_funcs",
  since = "2.0.0")
case class CollectList(
    child: Expression,
    mutableAggBufferOffset: Int = 0,
    inputAggBufferOffset: Int = 0) extends Collect[mutable.ArrayBuffer[Any]]
  with UnaryLike[Expression] {

  def this(child: Expression) = this(child, 0, 0)

  override lazy val bufferElementType = child.dataType

  override def convertToBufferElement(value: Any): Any = InternalRow.copyValue(value)

  override def withNewMutableAggBufferOffset(newMutableAggBufferOffset: Int): ImperativeAggregate =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): ImperativeAggregate =
    copy(inputAggBufferOffset = newInputAggBufferOffset)

  override def createAggregationBuffer(): mutable.ArrayBuffer[Any] = mutable.ArrayBuffer.empty

  override def prettyName: String = "collect_list"

  override def eval(buffer: mutable.ArrayBuffer[Any]): Any = {
    new GenericArrayData(buffer.toArray)
  }

  override protected def withNewChildInternal(newChild: Expression): CollectList =
    copy(child = newChild)
}

/**
 * Collect a set of unique elements.
 */
@ExpressionDescription(
  usage = "_FUNC_(expr) - Collects and returns a set of unique elements.",
  examples = """
    Examples:
      > SELECT _FUNC_(col) FROM VALUES (1), (2), (1) AS tab(col);
       [1,2]
  """,
  note = """
    The function is non-deterministic because the order of collected results depends
    on the order of the rows which may be non-deterministic after a shuffle.
  """,
  group = "agg_funcs",
  since = "2.0.0")
case class CollectSet(
    child: Expression,
    mutableAggBufferOffset: Int = 0,
    inputAggBufferOffset: Int = 0)
  extends Collect[mutable.HashSet[Any]] with QueryErrorsBase with UnaryLike[Expression] {

  def this(child: Expression) = this(child, 0, 0)

  override lazy val bufferElementType = child.dataType match {
    case BinaryType => ArrayType(ByteType)
    case other => other
  }

  override def convertToBufferElement(value: Any): Any = child.dataType match {
    /*
     * collect_set() of BinaryType should not return duplicate elements,
     * Java byte arrays use referential equality and identity hash codes
     * so we need to use a different catalyst value for arrays
     */
    case BinaryType => UnsafeArrayData.fromPrimitiveArray(value.asInstanceOf[Array[Byte]])
    case _ => InternalRow.copyValue(value)
  }

  override def eval(buffer: mutable.HashSet[Any]): Any = {
    val array = child.dataType match {
      case BinaryType =>
        buffer.iterator.map(_.asInstanceOf[ArrayData].toByteArray()).toArray
      case _ => buffer.toArray
    }
    new GenericArrayData(array)
  }

  override def checkInputDataTypes(): TypeCheckResult = {
    if (!child.dataType.existsRecursively(_.isInstanceOf[MapType])) {
      TypeCheckResult.TypeCheckSuccess
    } else {
      DataTypeMismatch(
        errorSubClass = "UNSUPPORTED_INPUT_TYPE",
        messageParameters = Map(
          "functionName" -> toSQLId(prettyName),
          "dataType" -> toSQLType(MapType)
        )
      )
    }
  }

  override def withNewMutableAggBufferOffset(newMutableAggBufferOffset: Int): ImperativeAggregate =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): ImperativeAggregate =
    copy(inputAggBufferOffset = newInputAggBufferOffset)

  override def prettyName: String = "collect_set"

  override def createAggregationBuffer(): mutable.HashSet[Any] = mutable.HashSet.empty

  override protected def withNewChildInternal(newChild: Expression): CollectSet =
    copy(child = newChild)
}

/**
 * Collect the top-k elements. This expression is dedicated only for Spark-ML.
 * @param reverse when true, returns the smallest k elements.
 */
case class CollectTopK(
    child: Expression,
    num: Int,
    reverse: Boolean = false,
    mutableAggBufferOffset: Int = 0,
    inputAggBufferOffset: Int = 0) extends Collect[BoundedPriorityQueue[Any]]
  with UnaryLike[Expression] {
  assert(num > 0)

  def this(child: Expression, num: Int) = this(child, num, false, 0, 0)
  def this(child: Expression, num: Int, reverse: Boolean) = this(child, num, reverse, 0, 0)

  override protected lazy val bufferElementType: DataType = child.dataType
  override protected def convertToBufferElement(value: Any): Any = InternalRow.copyValue(value)

  private def ordering: Ordering[Any] = if (reverse) {
    TypeUtils.getInterpretedOrdering(child.dataType).reverse
  } else {
    TypeUtils.getInterpretedOrdering(child.dataType)
  }

  override def createAggregationBuffer(): BoundedPriorityQueue[Any] =
    new BoundedPriorityQueue[Any](num)(ordering)

  override def eval(buffer: BoundedPriorityQueue[Any]): Any =
    new GenericArrayData(buffer.toArray.sorted(ordering.reverse))

  override def prettyName: String = "collect_top_k"

  override protected def withNewChildInternal(newChild: Expression): CollectTopK =
    copy(child = newChild)

  override def withNewMutableAggBufferOffset(newMutableAggBufferOffset: Int): CollectTopK =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): CollectTopK =
    copy(inputAggBufferOffset = newInputAggBufferOffset)
}

@ExpressionDescription(
  usage = "_FUNC_(expr) - Returns the concatenated input values," +
    " separated by the delimiter string.",
  examples = """
    Examples:
      > SELECT _FUNC_(col) FROM VALUES ('a'), ('b'), ('c') AS tab(col);
       a,b,c
      > SELECT _FUNC_(col) FROM VALUES (NULL), ('a'), ('b') AS tab(col);
       a,b
      > SELECT _FUNC_(col) FROM VALUES ('a'), ('a') AS tab(col);
       a,a
      > SELECT _FUNC_(DISTINCT col) FROM VALUES ('a'), ('a'), ('b') AS tab(col);
       a,b
      > SELECT _FUNC_(col, '|') FROM VALUES ('a'), ('b') AS tab(col);
       a|b
      > SELECT _FUNC_(col) FROM VALUES (NULL), (NULL) AS tab(col);
       NULL
  """,
  group = "agg_funcs",
  since = "4.0.0")
case class ListAgg(
    child: Expression,
    delimiter: Expression = Literal.create(",", StringType),
    orderExpression: Expression,
    reverse: Boolean = false,
    mutableAggBufferOffset: Int = 0,
    inputAggBufferOffset: Int = 0) extends Collect[mutable.ArrayBuffer[Any]]
  with BinaryLike[Expression] {

  def this(child: Expression) =
    this(child, Literal.create(",", StringType), child, false, 0, 0)
  def this(child: Expression, delimiter: Expression) =
    this(child, delimiter, child, false, 0, 0)

  override protected def convertToBufferElement(value: Any): Any = InternalRow.copyValue(value)

  override protected lazy val bufferElementType: DataType = {
    StructType(Seq(
      StructField("value", child.dataType),
      StructField("sortOrder", orderExpression.dataType)))
  }

  override def eval(buffer: mutable.ArrayBuffer[Any]): Any = {
    if (buffer.nonEmpty) {
      val ordering = PhysicalDataType.ordering(orderExpression.dataType)
      val sorted = if (reverse) {
        buffer.asInstanceOf[mutable.ArrayBuffer[InternalRow]].toSeq.sortBy(_.get(1,
          orderExpression.dataType))(ordering.asInstanceOf[Ordering[AnyRef]].reverse).map(_.get(0,
          child.dataType))
      } else {
        buffer.asInstanceOf[mutable.ArrayBuffer[InternalRow]].toSeq.sortBy(_.get(1,
          orderExpression.dataType))(ordering.asInstanceOf[Ordering[AnyRef]]).map(_.get(0,
          child.dataType))
      }
      UTF8String.fromString(sorted.map(_.toString)
        .mkString(delimiter.eval().asInstanceOf[UTF8String].toString))
    } else {
      UTF8String.fromString("")
    }
  }

  override def update(buffer: ArrayBuffer[Any], input: InternalRow): ArrayBuffer[Any] = {
    val value = child.eval(input)
    if (value != null) {
      buffer += InternalRow.apply(convertToBufferElement(value),
        convertToBufferElement(orderExpression.eval(input)))
    }
    buffer
  }

  override def createAggregationBuffer(): mutable.ArrayBuffer[Any] = mutable.ArrayBuffer.empty

  override def withNewMutableAggBufferOffset(
      newMutableAggBufferOffset: Int) : ImperativeAggregate =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): ImperativeAggregate =
    copy(inputAggBufferOffset = newInputAggBufferOffset)

  override def nullable: Boolean = true

  override def dataType: DataType = StringType

  override def left: Expression = child

  override def right: Expression = orderExpression

  override protected def withNewChildrenInternal(
      newLeft: Expression,
      newRight: Expression): Expression = {
    copy(child = newLeft, orderExpression = newRight)
  }
}
