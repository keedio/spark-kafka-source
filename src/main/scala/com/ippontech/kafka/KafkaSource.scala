package com.ippontech.kafka

import com.ippontech.kafka.stores.OffsetsStore
import com.typesafe.scalalogging.slf4j.LazyLogging
import kafka.message.MessageAndMetadata
import kafka.serializer.Decoder
import org.apache.commons.lang.StringUtils
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka.KafkaUtils

import scala.reflect.ClassTag

object KafkaSource extends LazyLogging {

  def kafkaStream[K: ClassTag, V: ClassTag, KD <: Decoder[K] : ClassTag, VD <: Decoder[V] : ClassTag]
  (ssc: StreamingContext, brokers: String, offsetsStore: OffsetsStore, topic: String): InputDStream[(K, V)] = {
    kafkaStream[K,V,KD,VD](ssc, brokers, offsetsStore, topic, null)
  }
  
  // Kafka input stream
  def kafkaStream[K: ClassTag, V: ClassTag, KD <: Decoder[K] : ClassTag, VD <: Decoder[V] : ClassTag]
  (ssc: StreamingContext, brokers: String, offsetsStore: OffsetsStore, topic: String, offsetReset: String): InputDStream[(K, V)] = {
    
    val topics = Set(topic)
    val storedOffsets = offsetsStore.readOffsets(topic)
    val kafkaStream = storedOffsets match {
      case None =>
        // start from the latest offsets
        val kafkaParams = if (StringUtils.isNotEmpty(offsetReset)){
          require(Array("smallest", "largest").contains(offsetReset))
          Map("metadata.broker.list" -> brokers, "auto.offset.reset" -> offsetReset)
        } else {
          Map("metadata.broker.list" -> brokers, "auto.offset.reset" -> "smallest")
        }
        
        KafkaUtils.createDirectStream[K, V, KD, VD](ssc, kafkaParams, topics)
      case Some(fromOffsets) =>
        val kafkaParams = Map("metadata.broker.list" -> brokers)
        // start from previously saved offsets
        val messageHandler = (mmd: MessageAndMetadata[K, V]) => (mmd.key, mmd.message)
        KafkaUtils.createDirectStream[K, V, KD, VD, (K, V)](ssc, kafkaParams, fromOffsets, messageHandler)
    }

    // save the offsets
    kafkaStream.foreachRDD(rdd => offsetsStore.saveOffsets(topic, rdd))

    kafkaStream
  }

}
