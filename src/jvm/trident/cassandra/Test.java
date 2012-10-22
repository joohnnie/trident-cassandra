package trident.cassandra;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.LocalDRPC;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import backtype.storm.utils.Utils;
import storm.trident.TridentState;
import storm.trident.TridentTopology;
import storm.trident.operation.builtin.Count;
import storm.trident.operation.builtin.FilterNull;
import storm.trident.operation.builtin.MapGet;
import storm.trident.operation.builtin.Sum;
import storm.trident.state.StateFactory;
import storm.trident.testing.FixedBatchSpout;
import storm.trident.testing.Split;

/**
 * @author slukjanov
 */
public class Test {
    public static void main(String[] args) throws Exception {
        FixedBatchSpout spout = new FixedBatchSpout(new Fields("sentence"), 3,
                new Values("the cow jumped over the moon"),
                new Values("the man went to the store and bought some candy"),
                new Values("four score and seven years ago"),
                new Values("how many apples can you eat"));
        spout.setCycle(true);

        TridentTopology topology = new TridentTopology();

        StateFactory cassandraStateFactory = CassandraState.transactional("localhost");

        TridentState wordCounts =
                topology.newStream("spout1", spout)
                        .each(new Fields("sentence"), new Split(), new Fields("word"))
                        .groupBy(new Fields("word"))
                        .persistentAggregate(cassandraStateFactory, new Count(), new Fields("count"))
                        .parallelismHint(6);

        LocalDRPC client = new LocalDRPC();
        topology.newDRPCStream("words", client)
                .each(new Fields("args"), new Split(), new Fields("word"))
                .groupBy(new Fields("word"))
                .stateQuery(wordCounts, new Fields("word"), new MapGet(), new Fields("count"))
                .each(new Fields("count"), new FilterNull())
                .aggregate(new Fields("count"), new Sum(), new Fields("sum"));

        LocalCluster cluster = new LocalCluster();
        Config config = new Config();
        config.setMaxSpoutPending(100);
        config.setMaxSpoutPending(25);
        cluster.submitTopology("test", config, topology.build());

        for (int i = 0; i < 30; i++) {
            Utils.sleep(1000);
            System.out.println(client.execute("words", "cat dog the man"));
            System.out.println(client.execute("words", "cat"));
            System.out.println(client.execute("words", "dog"));
            System.out.println(client.execute("words", "the"));
            System.out.println(client.execute("words", "man"));
            System.out.println("============================");
//            prints the JSON-encoded result, e.g.: "[[5078]]"
        }

        cluster.shutdown();
        client.shutdown();
    }
}
