/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2015  
 */
package com.ibm.streamsx.topology.test.api;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;
import com.ibm.streamsx.topology.TStream;
import com.ibm.streamsx.topology.TWindow;
import com.ibm.streamsx.topology.Topology;
import com.ibm.streamsx.topology.function7.Function;
import com.ibm.streamsx.topology.function7.Supplier;
import com.ibm.streamsx.topology.json.JSONStreams;
import com.ibm.streamsx.topology.test.TestTopology;
import com.ibm.streamsx.topology.tester.Condition;
import com.ibm.streamsx.topology.tester.Tester;
import com.ibm.streamsx.topology.tuple.Keyable;

public class WindowTest extends TestTopology {

    public static final class PeriodicStrings implements Supplier<String> {
        /**
         * 
         */
        private static final long serialVersionUID = 1L;
        private int id;

        @Override
        public String get() {
            return Integer.toString(id++) + ":" + Long.toString(System.currentTimeMillis()); 
        }
    }

    @SuppressWarnings("serial")
    private static final class SumInt implements
            Function<List<Number>, Integer> {
        @Override
        public Integer apply(List<Number> v1) {
            int sum = 0;
            int count = 0;
            for (Number i : v1) {
                count++;
                sum += i.intValue();
            }
            if (count > 3)
                throw new IllegalStateException("more than three tuples for last(3)");
            return sum;
        }
    }

    public static void assertWindow(Topology f, TWindow<?> window) {
        TopologyTest.assertFlowElement(f, window);
    }

    @Test
    public void testBasicCount() throws Exception {
        final Topology f = new Topology("CountWindow");
        TStream<String> source = f.strings("a", "b", "c");
        TWindow<String> window = source.last(10);
        assertNotNull(window);
        assertWindow(f, window);
    }

    @Test
    public void testBasicTime() throws Exception {
        final Topology f = new Topology("TimeWindow");
        TStream<String> source = f.strings("a", "b", "c");
        TWindow<String> window = source.last(10, TimeUnit.SECONDS);
        assertNotNull(window);
        assertWindow(f, window);
    }

    @Test
    public void testCountAggregate() throws Exception {
        final Topology f = new Topology("CountAggregate");
        TStream<Number> source = f.numbers(1, 2, 3, 4, 5, 6, 7);
        TWindow<Number> window = source.last(3);
        TStream<Integer> aggregate = window.aggregate(new SumInt(),
                Integer.class);
        
        completeAndValidate(aggregate, 10, "1", "3", "6", "9", "12", "15", "18");
    }

    @Test
    public void testKeyedAggregate() throws Exception {

        final Topology f = new Topology("PartitionedAggregate");
        TStream<StockPrice> source = f.constants(Arrays.asList(PRICES),
                StockPrice.class);

        TStream<StockPrice> aggregate = source.last(2).aggregate(new AveragePrice(), StockPrice.class);

        completeAndValidate(aggregate, 10, "A:1000", "B:4004", "C:2013", "A:1005",
                "A:1010", "B:4005", "A:1010", "C:2007", "B:4008", "C:2003",
                "A:1015", "B:4010", "B:4009", "B:4008", "A:1021", "C:2005",
                "C:2018", "A:1024");
    }

    static final StockPrice[] PRICES = { new StockPrice("A", 1000),
            new StockPrice("B", 4004), new StockPrice("C", 2013),
            new StockPrice("A", 1010), new StockPrice("A", 1011),
            new StockPrice("B", 4007), new StockPrice("A", 1010),
            new StockPrice("C", 2002), new StockPrice("B", 4010),
            new StockPrice("C", 2004), new StockPrice("A", 1020),
            new StockPrice("B", 4010), new StockPrice("B", 4009),
            new StockPrice("B", 4008), new StockPrice("A", 1022),
            new StockPrice("C", 2007), new StockPrice("C", 2030),
            new StockPrice("A", 1026),

    };

    @SuppressWarnings("serial")
    public static class StockPrice implements Keyable<String> {

        private final String ticker;
        private final int price;

        public StockPrice(String ticker, int price) {
            this.ticker = ticker;
            this.price = price;
        }

        @Override
        public String getKey() {
            return ticker;
        }

        public int getPrice() {
            return price;
        }

        public String toString() {
            return getKey() + ":" + getPrice();
        }

    }

    @SuppressWarnings("serial")
    public static class AveragePrice implements
            Function<List<StockPrice>, StockPrice> {

        @Override
        public StockPrice apply(List<StockPrice> tuples) {
            int price = 0;
            int count = 0;
            StockPrice last = null;
            for (StockPrice tuple : tuples) {
                count++;
                price += tuple.getPrice();
                last = tuple;
            }

            if (count == 0)
                return null;

            return new StockPrice(last.getKey(), price / count);
        }

    }
    
    /**
     * Test a continuous aggregation.
     */
    @Test
    public void testContinuousAggregateLastSeconds() throws Exception {
        final Topology t = new Topology();
        TStream<String> source = t.periodicSource(new PeriodicStrings(), 100, TimeUnit.MILLISECONDS, String.class);
        
        TStream<JSONObject> aggregate = source.last(3, TimeUnit.SECONDS).aggregate(new AggregateStrings(), JSONObject.class);
        TStream<String> strings = JSONStreams.serialize(aggregate);
        
        Tester tester = t.getTester();
        
        Condition<List<String>> contents = tester.stringContents(strings);
        
        // 10 tuples per second, each is aggregated, so 15 seconds is around 150 tuples.
        Condition<Long> ending = tester.atLeastTupleCount(strings, 150);
        complete(tester, ending, 30, TimeUnit.SECONDS);
        
        assertTrue(ending.valid());  
        
        long startTs = 0;
        for (String output : contents.getResult()) {
            JSONObject agg = JSONObject.parse(output);
            JSONArray items  = (JSONArray) agg.get("items");
            long ts = (Long) agg.get("ts");
            
            // Should see around 30 tuples per window, once we
            // pass the first three seconds.
            assertTrue("Number of tuples in window:" + items.size(), items.size() <= 45);
            if (agg.containsKey("delta")) {
                long delta = (Long) agg.get("delta");
                assertTrue(delta >= 0);
                
                if (startTs == 0) {
                    startTs = ts;
                } else {
                    long diff = ts - startTs;
                    if (diff > 3000)
                        assertTrue(
                                "Number of tuples in window:" + items.size(),
                                items.size() >= 25);
                }
            }
        }
    }
    
    /**
     * Test a continuous aggregation.
     */
    @Test
    public void testPeriodicAggregateLastSeconds() throws Exception {
        final Topology t = new Topology();
        TStream<String> source = t.periodicSource(new PeriodicStrings(), 100, TimeUnit.MILLISECONDS, String.class);
        
        TStream<JSONObject> aggregate = source.last(3, TimeUnit.SECONDS).aggregate(
                new AggregateStrings(), 1, TimeUnit.SECONDS, JSONObject.class);
        TStream<String> strings = JSONStreams.serialize(aggregate);
        
        Tester tester = t.getTester();
        
        Condition<List<String>> contents = tester.stringContents(strings);
        
        // 10 tuples per second, aggregate every second, so 15 seconds is around 15 tuples.
        Condition<Long> ending = tester.atLeastTupleCount(strings, 15);
        complete(tester, ending, 30, TimeUnit.SECONDS);
        
        assertTrue(ending.valid());  
        
        long startTs = 0;
        for (String output : contents.getResult()) {
            JSONObject agg = JSONObject.parse(output);
            JSONArray items  = (JSONArray) agg.get("items");
            long ts = (Long) agg.get("ts");
            
            // Should see around 30 tuples per window, once we
            // pass the first three seconds.
            assertTrue("Number of tuples in window:" + items.size(), items.size() <= 45);
            if (agg.containsKey("delta")) {
                long delta = (Long) agg.get("delta");
                assertTrue(delta >= 0);
                assertTrue("timeBetweenAggs: " + delta, delta > 800 && delta < 1200);
             
                if (startTs == 0) {
                    startTs = ts;
                } else {
                    long diff = ts - startTs;
                    if (diff > 3000)
                        assertTrue(
                                "Number of tuples in window:" + items.size(),
                                items.size() >= 25);
                }
            }
        }
    }
    
    public static class AggregateStrings implements Function<List<String>, JSONObject> {

        /**
         * 
         */
        private static final long serialVersionUID = 1L;
        
        private transient long lastts;
        private transient int count;

        @Override
        public JSONObject apply(List<String> v) {
            JSONObject agg = new JSONObject();
            JSONArray items = new JSONArray();
            for (String e : v)
                items.add(e);
            agg.put("items", items);
            long ts = System.currentTimeMillis();
            agg.put("ts", ts);
            if (lastts != 0)
                agg.put("delta", ts - lastts);
            
            lastts = ts;
            agg.put("count", ++count);


            return agg;
        }
    }
}