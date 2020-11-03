package ash.nazg.spatial.operations;

import ash.nazg.commons.functions.TrackComparator;
import ash.nazg.commons.functions.TrackPartitioner;
import ash.nazg.config.InvalidConfigValueException;
import ash.nazg.config.tdl.Description;
import ash.nazg.config.tdl.TaskDescriptionLanguage;
import ash.nazg.spark.Operation;
import ash.nazg.spatial.SegmentedTrack;
import ash.nazg.spatial.TrackSegment;
import ash.nazg.spatial.config.ConfigurationParameters;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaRDDLike;
import org.apache.spark.broadcast.Broadcast;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import scala.Tuple2;
import scala.Tuple4;

import java.util.*;
import java.util.stream.Collectors;

import static ash.nazg.spatial.config.ConfigurationParameters.*;

@SuppressWarnings("unused")
public class TrackCSVSourceOperation extends Operation {
    @Description("By default, create single-segmented tracks")
    public static final String DEF_CSV_TRACKID_COLUMN = null;

    public static final String VERB = "trackCsvSource";

    private String inputName;
    private char inputDelimiter;
    private Map<String, Integer> inputColumns;
    private int latColumn;
    private int lonColumn;
    private int useridColumn;
    private int tsColumn;
    private Integer trackColumn;

    private String outputName;
    private Map<String, Integer> outputColumns;

    @Override
    @Description("Source user Tracks from CSV file with signal data")
    public String verb() {
        return VERB;
    }

    @Override
    public TaskDescriptionLanguage.Operation description() {
        return new TaskDescriptionLanguage.Operation(verb(),
                new TaskDescriptionLanguage.DefBase[]{
                        new TaskDescriptionLanguage.Definition(DS_CSV_TIMESTAMP_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_CSV_LAT_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_CSV_LON_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_CSV_USERID_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_CSV_TRACKID_COLUMN, DEF_CSV_TRACKID_COLUMN),
                },

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.CSV},
                                true
                        )
                ),

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.Track},
                                true
                        )
                )
        );
    }

    @Override
    public void configure(Properties properties, Properties variables) throws InvalidConfigValueException {
        super.configure(properties, variables);

        inputName = describedProps.inputs.get(0);
        outputName = describedProps.outputs.get(0);

        inputDelimiter = dataStreamsProps.inputDelimiter(inputName);

        inputColumns = dataStreamsProps.inputColumns.get(inputName);
        List<String> outColumns = Arrays.asList(dataStreamsProps.outputColumns.get(outputName));
        outputColumns = inputColumns.entrySet().stream()
                .filter(c -> (outColumns.size() == 0) || outColumns.contains(c.getKey()))
                .collect(Collectors.toMap(c -> c.getKey().replaceFirst("^[^.]+\\.", ""), Map.Entry::getValue));

        String prop;

        prop = describedProps.defs.getTyped(ConfigurationParameters.DS_CSV_USERID_COLUMN);
        useridColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(ConfigurationParameters.DS_CSV_TRACKID_COLUMN);
        trackColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(ConfigurationParameters.DS_CSV_LAT_COLUMN);
        latColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(ConfigurationParameters.DS_CSV_LON_COLUMN);
        lonColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(ConfigurationParameters.DS_CSV_TIMESTAMP_COLUMN);
        tsColumn = inputColumns.get(prop);
    }

    @Override
    public Map<String, JavaRDDLike> getResult(Map<String, JavaRDDLike> input) {
        final char _inputDelimiter = inputDelimiter;
        final int _latColumn = latColumn;
        final int _lonColumn = lonColumn;
        final int _useridColumn = useridColumn;
        final int _tsColumn = tsColumn;
        final Integer _trackColumn = trackColumn;
        final Map<String, Integer> _outputColumns = outputColumns;

        JavaRDD<Object> signalsInput = (JavaRDD<Object>) input.get(inputName);
        int _numPartitions = signalsInput.getNumPartitions();

        JavaPairRDD<Tuple2<Text, Double>, Tuple4<Double, Double, Text, Text>> signals = signalsInput
                .mapPartitionsToPair(it -> {
                    List<Tuple2<Tuple2<Text, Double>, Tuple4<Double, Double, Text, Text>>> ret = new ArrayList<>();
                    CSVParser parser = new CSVParserBuilder().withSeparator(_inputDelimiter)
                            .build();

                    while (it.hasNext()) {
                        Object o = it.next();
                        String l = o instanceof String ? (String) o : String.valueOf(o);
                        String[] row = parser.parseLine(l);

                        Text userId = new Text(row[_useridColumn]);
                        Double lat = new Double(row[_latColumn]);
                        Double lon = new Double(row[_lonColumn]);
                        Double timestamp = new Double(row[_tsColumn]);

                        Text track = (_trackColumn != null) ? new Text(row[_trackColumn]) : null;

                        ret.add(new Tuple2<>(new Tuple2<>(userId, timestamp), new Tuple4<>(lat, lon, track, new Text(l))));
                    }

                    return ret.iterator();
                })
                .repartitionAndSortWithinPartitions(new TrackPartitioner(_numPartitions), new TrackComparator()) // pre-sort by timestamp
                ;

        HashMap<Integer, Integer> useridCountPerPartition = new HashMap<>(signals
                .mapPartitionsWithIndex((idx, it) -> {
                    List<Tuple2<Integer, Integer>> num = new ArrayList<>();

                    Set<Text> userids = new HashSet<>();
                    while (it.hasNext()) {
                        Text userid = it.next()._1._1;
                        userids.add(userid);
                    }

                    num.add(new Tuple2<>(idx, userids.size()));

                    return num.iterator();
                }, true)
                .mapToPair(t -> t)
                .collectAsMap()
        );

        Broadcast<HashMap<Integer, Integer>> num = ctx.broadcast(useridCountPerPartition);

        final GeometryFactory geometryFactory = new GeometryFactory();

        JavaRDD<SegmentedTrack> output = signals.mapPartitionsWithIndex((idx, it) -> {
            int useridCount = num.getValue().get(idx);
            boolean isSegmented = (_trackColumn != null);

            Text useridAttr = new Text(GEN_USERID);
            Text trackidAttr = new Text(GEN_TRACKID);
            Text tsAttr = new Text("_ts");

            Map<Text, Integer> useridOrd = new HashMap<>();

            CSVParser parser = new CSVParserBuilder().withSeparator(_inputDelimiter).build();

            Text[] userids = new Text[useridCount];
            List<MapWritable>[] allSegProps = new List[useridCount];
            List<List<Point>>[] allPoints = new List[useridCount];
            int n = 0;
            while (it.hasNext()) {
                Tuple2<Tuple2<Text, Double>, Tuple4<Double, Double, Text, Text>> line = it.next();

                Text userid = line._1._1;
                int current;
                if (useridOrd.containsKey(userid)) {
                    current = useridOrd.get(userid);
                } else {
                    useridOrd.put(userid, n);
                    userids[n] = userid;
                    current = n;

                    n++;
                }

                List<MapWritable> segProps = allSegProps[current];
                List<List<Point>> trackPoints = allPoints[current];
                if (segProps == null) {
                    segProps = new ArrayList<>();
                    allSegProps[current] = segProps;
                    trackPoints = new ArrayList<>();
                    allPoints[current] = trackPoints;
                }

                List<Point> segPoints;
                String trackId = null;
                if (isSegmented) {
                    trackId = line._2._3().toString();

                    String lastTrackId = null;
                    MapWritable lastSegment;
                    if (segProps.size() != 0) {
                        lastSegment = segProps.get(segProps.size() - 1);
                        lastTrackId = lastSegment.get(trackidAttr).toString();
                    }

                    if (trackId.equals(lastTrackId)) {
                        segPoints = trackPoints.get(trackPoints.size() - 1);
                    } else {
                        MapWritable props = new MapWritable();
                        props.put(useridAttr, userid);
                        props.put(trackidAttr, new Text(trackId));

                        segProps.add(props);
                        segPoints = new ArrayList<>();
                        trackPoints.add(segPoints);
                    }
                } else {
                    if (segProps.size() == 0) {
                        MapWritable props = new MapWritable();
                        props.put(useridAttr, userid);

                        segProps.add(props);
                        segPoints = new ArrayList<>();
                        trackPoints.add(segPoints);
                    } else {
                        segPoints = trackPoints.get(0);
                    }
                }

                Point point = geometryFactory.createPoint(new Coordinate(line._2._2(), line._2._1()));
                MapWritable pointProps = new MapWritable();
                String[] row = parser.parseLine(line._2._4().toString());
                for (Map.Entry<String, Integer> col : _outputColumns.entrySet()) {
                    pointProps.put(new Text(col.getKey()), new Text(row[col.getValue()]));
                }
                pointProps.put(tsAttr, new DoubleWritable(line._1._2));
                point.setUserData(pointProps);

                segPoints.add(point);
            }

            List<SegmentedTrack> result = new ArrayList<>(useridCount);

            for (n = 0; n < useridCount; n++) {
                Text userid = userids[n];

                List<List<Point>> points = allPoints[n];
                TrackSegment[] segments = new TrackSegment[points.size()];
                for (int i = 0; i < points.size(); i++) {
                    List<Point> segPoints = points.get(i);
                    segments[i] = new TrackSegment(segPoints.toArray(new Point[0]), geometryFactory);
                    segments[i].setUserData(allSegProps[n].get(i));
                }

                SegmentedTrack trk = new SegmentedTrack(segments, geometryFactory);

                MapWritable props = new MapWritable();
                props.put(useridAttr, userid);
                trk.setUserData(props);

                result.add(trk);
            }

            return result.iterator();
        }, true);

        return Collections.singletonMap(outputName, output);
    }
}
