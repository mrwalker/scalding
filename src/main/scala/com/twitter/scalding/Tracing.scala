package com.twitter.scalding

import scala.collection.JavaConverters._

import cascading.flow.FlowDef
import cascading.pipe.Pipe
import cascading.tuple.{Fields,Tuple,TupleEntry}

import com.googlecode.javaewah.{EWAHCompressedBitmap => CBitSet}

import com.twitter.algebird.BFHash
import com.twitter.algebird.Operators._

object Tracing {
  implicit var tracing : Tracing = new NullTracing()
  
  def init(args : Args) : Unit = {
    if(args.boolean("write_sources")) {
      if(args.boolean("bf")) {
        tracing = new BloomFilterInputTracing(
          args.getOrElse("bf_hashes", 5.toString).toInt,
          args.getOrElse("bf_width", (1 << 24).toString).toInt,
          args.getOrElse("tracing_field", "__source_data__"))
      } else {
        tracing = new MapInputTracing(args.getOrElse("tracing_field", "__source_data__"))
      }
    }
  }

  def clear : Unit = {
    tracing = new NullTracing()
  }
}

abstract class Tracing {
  // Called after Source.read by TracingFileSource
  def afterRead(src : Source, pipe : Pipe) : Pipe

  // Called by RichPipe.write
  def onWrite(pipe : Pipe) : Pipe

  // Called by JoinAlgorithms
  def beforeJoin(pipe : Pipe, side : Boolean) : Pipe
  def afterJoin(pipe : Pipe) : Pipe

  // Called by RichPipe.groupBy
  def onGroupBy(groupbuilder : GroupBuilder, pipe : Pipe) : GroupBuilder
  def onGroupByNoMerge(groupbuilder : GroupBuilder, pipe : Pipe) : GroupBuilder

  // Called by SourceTracingJob.buildFlow
  def onFlowComplete : Map[Source, Pipe]

  // The fields which get tracked (so that RichPipe doesnt nuke these fields
  // in  e.g., mapTo and project)
  def tracingFields : Option[Fields]

  // Let Richpipe know whether a pipe needs fields to be preserved.
  def isTraced(pipe : Pipe) : Boolean
}

// This class does no tracing.
class NullTracing extends Tracing {
  override def afterRead(src : Source, pipe : Pipe) : Pipe = pipe
  override def onWrite(pipe : Pipe) : Pipe = pipe
  override def beforeJoin(pipe : Pipe, side : Boolean) : Pipe = pipe
  override def afterJoin(pipe : Pipe) : Pipe = pipe
  override def onGroupBy(groupbuilder : GroupBuilder, pipe : Pipe) : GroupBuilder = groupbuilder
  override def onGroupByNoMerge(groupbuilder : GroupBuilder, pipe : Pipe) : GroupBuilder = groupbuilder
  def onFlowComplete : Map[Source, Pipe] = Map[Source, Pipe]()
  override def tracingFields : Option[Fields] = None
  override def isTraced(pipe : Pipe) = false
}

// This class traces input records throughout the computation by placing
// the source file tuple contents into a special field, and tracing this through
// the computation.
abstract class BaseInputTracing[T](val fieldName : String) extends Tracing with Serializable {
  import Dsl._

  val field = new Fields(fieldName)

  override def tracingFields : Option[Fields] = Some(field)

  protected var sources = Set[TracingFileSource]()
  protected var headpipes = Set[Pipe]()
  
  def isTraced(pipe : Pipe) : Boolean = {
    headpipes.contains(pipe) || (pipe.getHeads.size > 0 && pipe.getHeads.toList.map{ p : Pipe => headpipes.contains(p) }.reduce{_||_})
  }

  def prepare(src : TracingFileSource, pipe : Pipe) : Pipe

  override def afterRead(src : Source, pipe : Pipe) : Pipe = {
    src match {
      case tf : TracingFileSource => {
        sources += tf
        headpipes += pipe
        prepare(tf, pipe)
      }
      case _ => {
        pipe
      }
    }
  }

  protected var lefttracing : Option[Boolean] = None
  protected var righttracing : Option[Boolean] = None
  
  // Currently theres no way for these calls to get interleaved so it is safe to assume that
  // two calls to beforejoin always preceed a call to afterjoin.
  override def beforeJoin(pipe : Pipe, right : Boolean) : Pipe = {
    if(right) {
      require(righttracing == None)
      righttracing = Some(isTraced(pipe))
      if(righttracing.get)
        pipe.rename(field -> new Fields(fieldName+"_"))
      else
        pipe
    } else {
      require(lefttracing == None)
      lefttracing = Some(isTraced(pipe))
      pipe
    }
  }

  override def afterJoin(pipe : Pipe) : Pipe = {
    require(lefttracing != None && righttracing != None)
    val ret = 
      if(lefttracing.get) {
        if(righttracing.get) {
          pipe.map((fieldName, fieldName+"_") -> fieldName){ m : (T, T) => mergeSafe(m._1, m._2)}
        } else {
          pipe
        }
      } else {
        if(righttracing.get) {
          pipe.rename(new Fields(fieldName+"_") -> field)
        } else {
          pipe
        }
      }
    righttracing = None
    lefttracing = None
    ret
  }

  override def onGroupBy(groupbuilder : GroupBuilder, pipe : Pipe) : GroupBuilder = {
    if(isTraced(pipe))
      groupbuilder.reduce[T](field -> field)(merge)
    else
      groupbuilder
  }

  override def onGroupByNoMerge(groupbuilder : GroupBuilder, pipe : Pipe) : GroupBuilder = {
    if(isTraced(pipe))
      groupbuilder.reduce[T](field -> field){ (a : T, b : T) => a }
    else
      groupbuilder
  }

  def merge(a : T, b : T) : T

  def mergeSafe(a : T, b : T) : T = {
    if(a == null) {
      b
    } else if(b == null) {
      a
    } else {
      merge(a,b)
    }
  }
}

// This class throws the entire input tuples into the tracing field.  The field 
// contains a map of source_string -> List(input tuples from that source).  
// 
// When the flow is complete we can just expand this back out and write to disk.
class MapInputTracing(fieldName : String) extends BaseInputTracing[Map[String,List[Tuple]]](fieldName) with Serializable{

  import Dsl._

  protected var tailpipes = Map[String, Pipe]()

  def prepare(tf : TracingFileSource, pipe : Pipe) : Pipe = {
    val fp = tf.toString
    pipe.map(tf.hdfsScheme.getSourceFields -> field){ te : TupleEntry => Map(fp -> List[Tuple](te.getTuple)) }
  }

  override def onWrite(pipe : Pipe) : Pipe = {
    if(isTraced(pipe)) {
      // Nuke the implicit tracing object to turn off tracing for this step.
      Tracing.tracing = new NullTracing()
      sources.foreach { ts : TracingFileSource =>
        val n = ts.toString
        val p = pipe.flatMapTo(fieldName -> ts.hdfsScheme.getSourceFields){ m : Map[String, List[Tuple]] => m.getOrElse(n, List[Tuple]()) }
        if(tailpipes.contains(n))
          tailpipes += (n -> (RichPipe(p) ++ tailpipes(n)))
        else
          tailpipes += (n -> p)
      }
      // Resume tracing
      Tracing.tracing = this
    }
    pipe
  }

  override def onFlowComplete : Map[Source, Pipe] = {
    // Nuke the implicit tracing object to turn off tracing for this step.
    Tracing.tracing = new NullTracing()
    var ret = Map[Source, Pipe]()
    sources.foreach { ts : TracingFileSource => 
      val n = ts.toString
      if(tailpipes.contains(n)) {
        ret += (ts.subset -> RichPipe(tailpipes(n)).unique(ts.hdfsScheme.getSourceFields))
      }
    }
    ret
  }

  override def merge(a : Map[String,List[Tuple]], b : Map[String,List[Tuple]]) : Map[String,List[Tuple]] = a + b
}


// As Above but instead of using a Map of string -> List[Tuple], we do a map of
// string -> BloomFilter, then when the flows complete we go back and scan the input for
// those elements in the filter.
class BloomFilterInputTracing(val bfhashes : Int, val bfwidth: Int, fieldName : String) extends BaseInputTracing[Map[String, CBitSet]](fieldName) {

  import Dsl._

  require(math.ceil(bfwidth/64.0) == bfwidth/64)

  protected var tailpipes = Map[String, Pipe]()
  protected var origpipes = Map[String, Pipe]()

  protected val bfhash : BFHash = BFHash(bfhashes, bfwidth)

  def hash(str : String) : List[Int] = bfhash(str).toList

  def prepare(tf : TracingFileSource, pipe : Pipe) : Pipe = {
    val fp = tf.toString
    origpipes += (fp -> pipe)
    pipe.map(tf.hdfsScheme.getSourceFields -> field){ te : TupleEntry => Map[String, CBitSet](fp -> CBitSet.bitmapOf(hash(te.getTuple.toString).sorted : _*)) }
  }

  override def onWrite(pipe : Pipe) : Pipe = {
    if(isTraced(pipe)) {
      // Nuke the implicit tracing object to turn off tracing for this step.
      Tracing.tracing = new NullTracing()
      sources.foreach { ts : TracingFileSource =>
        val n = ts.toString
        val p = pipe.mapTo(fieldName -> 'bf){ m : Map[String, CBitSet] => m.getOrElse(n, CBitSet.bitmapOf()) }
                    .groupAll{ _.reduce[CBitSet]('bf -> 'bf){ (x : CBitSet, y : CBitSet) => x.or(y)} } // TODO: unknown how bad this is.
        if(tailpipes.contains(n))
          tailpipes += (n -> tailpipes(n).crossWithTiny(p.rename('bf->'bf2)).map(('bf, 'bf2) -> 'bf){ x : (CBitSet, CBitSet) => x._1.or(x._2) })
        else
          tailpipes += (n -> p)
      }
      // Resume tracing
      Tracing.tracing = this
    }
    pipe
  }

  override def onFlowComplete : Map[Source, Pipe] = {
    // Nuke the implicit tracing object to turn off tracing for this step.
    Tracing.tracing = new NullTracing()
    var ret = Map[Source, Pipe]()
    var expanded = Map[String, Pipe]()
    sources.foreach { ts : TracingFileSource => 
      val n = ts.toString
      if(tailpipes.contains(n) && origpipes.contains(n)) {
        if(!expanded.contains(n)) {
          expanded += (n -> tailpipes(n).map[CBitSet,BitSet]('bf -> 'bf){ c : CBitSet =>
                              val b = new BitSet(new Array[Long](bfwidth/64))
                              c.asScala.foreach{ i : java.lang.Integer => b.set(i.toInt) }
                              b })
        }
        val p = origpipes(n)
                  .map(Fields.ALL -> 'tuplestr){ te : TupleEntry => te.getTuple.toString }
                  .crossWithTiny(expanded(n))
                  .filter(('tuplestr, 'bf)){ x : (String, BitSet) => x._2.contains(hash(x._1)) }
                  .discard('tuplestr, 'bf)
        ret += (ts.subset -> p)
      }
    }
    ret
  }

  override def merge(a : Map[String, CBitSet], b : Map[String, CBitSet]) : Map[String, CBitSet] = {
    var m : Map[String, CBitSet] = a;
    b.foreach{ x : (String, CBitSet) => m += (x._1 -> (if(m.contains(x._1)) x._2.or(m(x._1)) else x._2)) }
    m
  }
}
  
class BitSet(val bits : Array[Long]) extends Serializable {
  def set(loc : Int) : Unit = bits(loc/64) |= 1L << (loc % 64)
  def contains(locs : List[Int]) : Boolean = locs.map{i : Int => (bits(i/64) & (1L << (i % 64))) != 0L}.reduce{_&&_}
}
