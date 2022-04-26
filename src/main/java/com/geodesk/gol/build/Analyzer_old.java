package com.geodesk.gol.build;

import com.clarisma.common.text.Format;
import com.geodesk.core.Mercator;
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.Tags;
import com.geodesk.io.osm.Members;
import com.geodesk.io.osm.Nodes;
import com.geodesk.io.osm.OsmPbfReader;
import com.clarisma.common.collect.Linked;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * In this first phase of the import process, we read the entire
 * planet file and gather statistics about it. Specifically:
 *
 *    - the most commonly used strings, and their usage
 *      (key, value, or role), in order to build the
 *      string tables
 *
 *    - the densities of tiles at zoom level 12, so that
 *      we can determine the appropriate tile structure
 *
 *    - overall statistical information, such as the
 *      number of nodes, ways, and relations
 *
 *  Inputs needed:
 *
 *    - Planet file (in .osm.pbf format)
 *
 *  Outputs generated:
 *
 *    - String Summary: A text file that contains a list of strings
 *      in descending order of their total use, with usage broken
 *      out by keys, values, and roles (tab-separated, with header)
 *
 *    - Statistics: A text file with "key: value" pairs of
 *      various counters.
 *
 *    - Tile Densities: A comma-separated text file of the density
 *      (node count) of each tile at zoom level 12. Empty tiles
 *      are omitted.
 *
 *  TODO: Filter strings
 *  TODO: Make formats more uniform? Tab, colon, comma?
 *  TODO: trim strings as we encounter them? Right now, we only trim them
 *    when writing the summary
 */
public class Analyzer_old extends OsmPbfReader
{
    // private static final Logger log = LogManager.getLogger();

    //
    // Global counters for general statistics.
    //
    private long totalNodeCount;
    private long totalTaggedNodeCount;
    private long totalWayCount;
    private long totalWayNodeCount;
    private long totalRelationCount;
    private long totalSuperRelationCount;
    private long totalEmptyRelationCount;
    private long totalMemberCount;
    private long totalTagCount;
    private long globalMaxNodeId;
    private long globalMaxWayId;
    private long globalMaxRelationId;

    //
    // Node counters
    //
    private int[] globalNodesPerTile;

    //
    // String-Table Construction
    //
    private Map<String, StringCounter> globalStrings = new HashMap<>();

    /**
     * The minimum number of times a string must be used in order
     * for it to remain in the internal string table. As the
     * internal table fills up, we keep increasing this number.
     */
    private int minLocalStringCount = 2;

    /**
     * The counter for the most recently encountered string,
     * forming the head of a linked list of counters.
     *
     * As the internal table fills up, we toss out the strings
     * that are least likely to end up in the final string table.
     * The most obvious indicator is low frequency of occurrence.
     * Since strings are not evenly distributed, we might miss
     * clusters of frequently-used strings that occur after the
     * internal table has already reached capacity. In order to
     * prevent this, we start eliminating low-usage strings that
     * we haven't seen in a long time, in order to allow later
     * arrivals to "catch up."
     */
    private StringCounter mostRecentString;

    /**
     * The next string counter to consider evicting if it does
     * not meet the minimum occurrence count.
     */
    private StringCounter evictionCandidate;

    /**
     * The maximum number of strings to hold in the internal
     * table. Once we reach this threshold, we start culling
     * strings below `minStringcount`, starting with the least
     * recently encountered string.
     */
    private int maxInternalStringTableSize = 1_000_000;

    /**
     * The minimum number of occurrences a string must have in order
     * to be written to the string summary
     */
    private int minFinalStringCount = 100;

    /**
     * The maximum number of strings a worker thread will accumulate
     * before handing the set of string counts to the output thread.
     */
    private int stringBatchSize = 64 * 1024;

    /**
     * A counter that keeps track of how many times a string was used.
     * Counts of key use and value use are broken out separately; the
     * (much rarer) use of a string as a role can be inferred by
     * subtracting `keys` and `value` from `total`, eliminating the
     * need for this field.
     *
     * By default, `StringCounter` objects are sorted in descending
     * order of the string's total use.
     *
     */
    private static class StringCounter extends Linked<StringCounter> implements Comparable<StringCounter>
    {
        String string;
        long keys;
        long values;
        long total;

        public StringCounter(String s)
        {
            string = s;
        }

        public int compareTo(StringCounter other)
        {
            if(total > other.total) return -1;
            if(total < other.total) return 1;
            return 0;
        }
    }

    /**
     * Removes the least-used strings in order to trim the size of the in-memory string table
     * to its limit.
     */
    private void evictLeastUsedString()
    {
        for(;;)
        {
            StringCounter c = evictionCandidate;
            evictionCandidate = evictionCandidate.prev();
            if(c == mostRecentString)
            {
                // TODO: check this approach
                minLocalStringCount++;
                // log.debug("Minimum instance count is now {}", minLocalStringCount);
                continue;
            }
            if(c.total < minLocalStringCount)
            {
                globalStrings.remove(c.string);
                c.remove();
                return;
            }
        }
    }

    private StringCounter getStringCounter(String s)
    {
        StringCounter counter = globalStrings.get(s);
        if(counter == null)
        {
            if(globalStrings.size() == maxInternalStringTableSize)
            {
                evictLeastUsedString();
            }
            counter = new StringCounter(s);
            globalStrings.put(s, counter);
        }
        if(counter != mostRecentString)
        {
            if(evictionCandidate == mostRecentString)
            {
                evictionCandidate = mostRecentString.prev();
            }
            mostRecentString.prepend(counter);
            mostRecentString = counter;
        }
        return counter;
    }

    private static final int COUNT_KEYS = 0;
    private static final int COUNT_VALUES = 1;
    private static final int COUNT_ROLES = 2;

    private class Batch implements Runnable
    {
        ObjectIntMap<String> strings;
        int[] counts;

        Batch(ObjectIntMap<String> strings, int[] counts)
        {
            this.strings = strings;
            this.counts = counts;
        }

        @Override public void run()
        {
            strings.forEachKeyValue((s, row) ->
            {
                StringCounter counter = getStringCounter(s);
                int n = row * 3;
                int keys = counts[n + COUNT_KEYS];
                int values = counts[n + COUNT_VALUES];
                int roles = counts[n + COUNT_ROLES];
                counter.keys += keys;
                counter.values += values;
                counter.total += keys + values + roles;
            });
        }
    }

    @Override protected WorkerThread createWorker()
    {
        return new AnalyzerThread();
    }

    private class AnalyzerThread extends WorkerThread
    {
        private long nodeCount;
        private long taggedNodeCount;
        private long wayCount;
        private long wayNodeCount;
        private long relationCount;
        private long superRelationCount;
        private long emptyRelationCount;
        private long memberCount;
        private long tagCount;
        private long maxNodeId;
        private long maxWayId;
        private long maxRelationId;
        private MutableObjectIntMap<String> strings;
        private int[] stringCounts;
        private MutableIntIntMap nodesPerTile = new IntIntHashMap();

        AnalyzerThread()
        {
            newBatch();
        }

        private void newBatch()
        {
            strings = new ObjectIntHashMap<>(stringBatchSize + stringBatchSize / 2);
            stringCounts = new int[stringBatchSize * 3];
        }

        private void flush()
        {
            try
            {
                output(new Batch(strings, stringCounts));
            }
            catch(InterruptedException ex)
            {
                // TODO
            }
            newBatch();
        }

        private void countString(String s, int what)
        {
            int row = strings.getIfAbsentPut(s, strings.size());
            stringCounts[row * 3 + what]++;
            if(strings.size() == stringBatchSize) flush();
        }

        private int countTagStrings(Tags tags)
        {
            int numberOfTags = 0;
            StringCounter counter;
            while(tags.next())
            {
                countString(tags.key(), COUNT_KEYS);
                countString(tags.stringValue(), COUNT_VALUES);
                numberOfTags++;
            }
            return numberOfTags;
        }

        @Override protected void node(long id, int lon, int lat, Tags tags)
        {
            nodeCount++;
            maxNodeId = id;
            int nodeTagCount = countTagStrings(tags);
            if(nodeTagCount > 0) taggedNodeCount++;
            tagCount += nodeTagCount;

            int x = Mercator.xFromLon100nd(lon);
            int y = Mercator.yFromLat100nd(lat);
            int tile = Tile.rowFromYZ(y, 12) * 4096 + Tile.columnFromXZ(x, 12);
            nodesPerTile.addToValue(tile, 1);
        }

        @Override protected void way(long id, Tags tags, Nodes nodes)
        {
            wayCount++;
            wayNodeCount += nodes.size();
            tagCount += tags.size();
            maxWayId = id;
            countTagStrings(tags);
        }

        @Override protected void relation(long id, Tags tags, Members members)
        {
            relationCount++;
            tagCount += tags.size();
            maxRelationId = id;
            countTagStrings(tags);
            boolean isSuperRelation = false;
            int thisMemberCount = 0;
            while(members.next())
            {
                if(members.type() == FeatureType.RELATION)
                {
                    if (members.id() != id) isSuperRelation = true;
                    // We ignore self-references, since they are
                    // removed by subsequent steps
                }
                countString(members.role(), COUNT_ROLES);
                thisMemberCount++;
            }
            memberCount += thisMemberCount;
            if(thisMemberCount == 0) emptyRelationCount++;
            if(isSuperRelation) superRelationCount++;
        }

        @Override protected void postProcess()
        {
            flush();
            synchronized (Analyzer_old.this)
            {
                totalNodeCount += nodeCount;
                totalTaggedNodeCount += taggedNodeCount;
                totalWayCount += wayCount;
                totalWayNodeCount += wayNodeCount;
                totalRelationCount += relationCount;
                totalSuperRelationCount += superRelationCount;
                totalEmptyRelationCount += emptyRelationCount;
                totalMemberCount += memberCount;
                totalTagCount += tagCount;
                if (maxNodeId > globalMaxNodeId) globalMaxNodeId = maxNodeId;
                if (maxWayId > globalMaxWayId) globalMaxWayId = maxWayId;
                if (maxRelationId > globalMaxRelationId) globalMaxRelationId = maxRelationId;

                nodesPerTile.forEachKeyValue((tile, count) ->
                {
                    globalNodesPerTile[tile] += count;
                });
            }
        }
    }


    private static String cleanString(String s)
    {
        s = s.trim();
        // s.replaceAll("[\\\\t\\\\n\\\\r\\\\f\\\\v]", " ")
        return s;
    }

    public void writeStringSummary(String stringFileName) throws FileNotFoundException
    {
        StringCounter[] counters = globalStrings.values().toArray(new StringCounter[0]);
        Arrays.sort(counters);
        PrintWriter out = new PrintWriter(stringFileName);
        out.println("String\tTotal\tKeys\tValues\tRoles");
        for(StringCounter c: counters)
        {
            if(c.total < minFinalStringCount) continue;
            String s = cleanString(c.string);
            if(s.isEmpty()) continue;
            out.format("%s\t%d\t%d\t%d\t%d\n", s, c.total, c.keys, c.values,
                c.total - c.keys - c.values);
        }
        out.close();
    }

    public void writeNodeDensities(String fileName) throws IOException
    {
        PrintWriter out = new PrintWriter(fileName);
        for(int row=0; row<4096; row++)
        {
            for(int col=0; col<4096; col++)
            {
                int count = globalNodesPerTile[row * 4096 + col];
                if(count > 0)
                {
                    out.format("%d,%d,%d\n", col, row, count);
                }
            }
        }
        out.close();
    }


    public void analyze(String sourceFilename) throws IOException
    {
        globalNodesPerTile = new int[4096 * 4096];      // TODO: delay creation
        mostRecentString = new StringCounter("yes");
        globalStrings.put(mostRecentString.string, mostRecentString);
        evictionCandidate = mostRecentString;
        read(sourceFilename);
    }

    public void writeStatistics(String reportFileName) throws IOException
    {
        PrintWriter out = new PrintWriter(reportFileName);
        out.format("nodes:           %d\n", totalNodeCount);
        out.format("tagged-nodes:    %d\n", totalTaggedNodeCount);
        out.format("ways:            %d\n", totalWayCount);
        out.format("way-nodes:       %d\n", totalWayNodeCount);
        out.format("relations:       %d\n", totalRelationCount);
        out.format("super-relations: %d\n", totalSuperRelationCount);
        out.format("empty-relations: %d\n", totalEmptyRelationCount);
        out.format("members:         %d\n", totalMemberCount);
        out.format("tags:            %d\n", totalTagCount);
        out.format("max-node-id:     %d\n", globalMaxNodeId);
        out.format("max-way-id:      %d\n", globalMaxWayId);
        out.format("max-relation-id: %d\n", globalMaxRelationId);
        out.close();
    }

    /*
    public static void main(String[] args) throws Exception
    {
        String fileName = "c:\\geodesk\\mapdata\\south-america-2021-02-02.osm.pbf";
        log.info("V3 Analyzing {}...", fileName);
        Stopwatch timer = new Stopwatch();
        Analyzer analyzer = new Analyzer();
        analyzer.analyze(fileName);
        analyzer.writeStatistics("c:\\geodesk\\sa2\\stats.txt");
        analyzer.writeStringSummary("c:\\geodesk\\sa2\\string-counts.txt");
        analyzer.writeNodeDensities("c:\\geodesk\\sa2\\node-counts.txt");
        log.info("Analyzed in {}\n", Format.formatTimespan(timer.stop()));
    }

     */

    public static void main(String[] args) throws Exception
    {
        String fileName = args[0];
        Analyzer_old analyzer = new Analyzer_old();
        analyzer.analyze(fileName);
        Path workPath = Path.of(args[1]);
        analyzer.writeStatistics(workPath.resolve("stats.txt").toString());
        analyzer.writeStringSummary(workPath.resolve("string-counts.txt").toString());
        analyzer.writeNodeDensities(workPath.resolve("node-counts.txt").toString());
        // TODO: verbosity
        System.err.println("Analyzed in " + Format.formatTimespan(analyzer.timeElapsed()));
    }

}
