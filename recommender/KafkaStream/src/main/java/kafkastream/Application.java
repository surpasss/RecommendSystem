package kafkastream;/**
 * @author： ZYJ
 * @Description:
 * @date: 2021/3/8 16:07
 * @version:
 */

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.processor.TopologyBuilder;

import java.util.Properties;

/**
 * @ClassName Application
 * @Author ZYJ
 * @DATE 2021/3/8 16:07
 * @Version 1.0
 */
public class Application {
    public static void main(String[] args) {
        String brokers = "localhost:9092";
        String zookeepers = "localhost:2181";

        //定义输入和输出的topic
        String from = "log";
        String to = "recommender";

        //定义kafka streaming的配置
        Properties settings = new Properties();
        settings.put(StreamsConfig.APPLICATION_ID_CONFIG, "logFilter");
        settings.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        settings.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, zookeepers);

        //创建kafka stream配置对象
        StreamsConfig config = new StreamsConfig(settings);
        //创建一个拓扑建构器
        TopologyBuilder builder = new TopologyBuilder();
        //定义流处理的拓扑结构
        builder.addSource("SOURCE", from)
                .addProcessor("PROCESS", () -> new LogProcessor(), "SOURCE")
                .addSink("SINK", to, "PROCESS");
        KafkaStreams streams = new KafkaStreams(builder, config);
        streams.start();
        System.out.println("Kafka stream started!>>>>>>>>>>>>>>>");

    }

}
