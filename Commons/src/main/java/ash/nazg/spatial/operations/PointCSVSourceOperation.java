/**
 * Copyright (C) 2020 Locomizer team and Contributors
 * This project uses New BSD license with do no evil clause. For full text, check the LICENSE file in the root directory.
 */
package ash.nazg.spatial.operations;

import ash.nazg.config.InvalidConfigValueException;
import ash.nazg.config.tdl.Description;
import ash.nazg.config.tdl.TaskDescriptionLanguage;
import ash.nazg.spark.Operation;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaRDDLike;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.*;
import java.util.stream.Collectors;

import static ash.nazg.spatial.config.ConfigurationParameters.*;

@SuppressWarnings("unused")
public class PointCSVSourceOperation extends Operation {
    @Description("By default, don't set Point _radius attribute")
    public static final Double DEF_DEFAULT_RADIUS = null;
    @Description("By default, don't set Point _radius attribute")
    public static final String DEF_CSV_RADIUS_COLUMN = null;

    public static final String VERB = "pointCsvSource";

    private String inputName;
    private char inputDelimiter;
    private int latColumn;
    private int lonColumn;

    private Integer radiusColumn;
    private Double defaultRadius;

    private String outputName;
    private Map<String, Integer> outputColumns;

    @Override
    @Description("Take a CSV file and produce a Polygon RDD")
    public String verb() {
        return VERB;
    }

    @Override
    public TaskDescriptionLanguage.Operation description() {
        return new TaskDescriptionLanguage.Operation(verb(),
                new TaskDescriptionLanguage.DefBase[]{
                        new TaskDescriptionLanguage.Definition(OP_DEFAULT_RADIUS, Double.class, DEF_DEFAULT_RADIUS),
                        new TaskDescriptionLanguage.Definition(DS_CSV_RADIUS_COLUMN, DEF_CSV_RADIUS_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_CSV_LAT_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_CSV_LON_COLUMN),
                },

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.CSV},
                                true
                        )
                ),

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.Point},
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

        defaultRadius = describedProps.defs.getTyped(OP_DEFAULT_RADIUS);

        Map<String, Integer> inputColumns = dataStreamsProps.inputColumns.get(inputName);
        final List<String> outColumns = Arrays.asList(dataStreamsProps.outputColumns.get(outputName));
        outputColumns = inputColumns.entrySet().stream()
                .filter(c -> (outColumns.size() == 0) || outColumns.contains(c.getKey()))
                .collect(Collectors.toMap(c -> c.getKey().replaceFirst("^[^.]+\\.", ""), Map.Entry::getValue));

        String prop;

        prop = describedProps.defs.getTyped(DS_CSV_RADIUS_COLUMN);
        radiusColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(DS_CSV_LAT_COLUMN);
        latColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(DS_CSV_LON_COLUMN);
        lonColumn = inputColumns.get(prop);
    }

    @Override
    public Map<String, JavaRDDLike> getResult(Map<String, JavaRDDLike> input) {
        final int _latColumn = latColumn;
        final int _lonColumn = lonColumn;
        final Integer _radiusColumn = radiusColumn;
        final Double _defaultRadius = defaultRadius;
        final char _inputDelimiter = inputDelimiter;
        final Map<String, Integer> _outputColumns = outputColumns;
        final GeometryFactory geometryFactory = new GeometryFactory();

        JavaRDD<Point> output = ((JavaRDD<Object>) input.get(inputName))
                .mapPartitions(it -> {
                    CSVParser parser = new CSVParserBuilder().withSeparator(_inputDelimiter).build();

                    List<Point> result = new ArrayList<>();

                    Text latAttr = new Text(GEN_CENTER_LAT);
                    Text lonAttr = new Text(GEN_CENTER_LON);
                    Text radiusAttr = new Text(GEN_RADIUS);

                    while (it.hasNext()) {
                        Object line = it.next();
                        String l = line instanceof String ? (String) line : String.valueOf(line);

                        String[] row = parser.parseLine(l);

                        double lat = new Double(row[_latColumn]);
                        double lon = new Double(row[_lonColumn]);

                        MapWritable properties = new MapWritable();

                        for (Map.Entry<String, Integer> col : _outputColumns.entrySet()) {
                            properties.put(new Text(col.getKey()), new Text(row[col.getValue()]));
                        }

                        Double radius = _defaultRadius;
                        if (_radiusColumn != null) {
                            radius = new Double(row[_radiusColumn]);
                        }

                        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
                        point.setUserData(properties);
                        properties.put(latAttr, new DoubleWritable(lat));
                        properties.put(lonAttr, new DoubleWritable(lon));
                        if (radius != null) {
                            properties.put(radiusAttr, new DoubleWritable(radius));
                        }

                        result.add(point);
                    }

                    return result.iterator();
                });

        return Collections.singletonMap(outputName, output);
    }
}
